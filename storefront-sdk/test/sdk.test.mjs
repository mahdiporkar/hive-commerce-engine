import assert from 'node:assert/strict';
import test from 'node:test';
import {browserTokenStore, breadcrumbJsonLd, CommerceError, createShop, directionOf, localized, productJsonLd, publicCatalog, searchParams, sitemapXml} from '../dist/index.js';

const json = (body, status = 200) => new Response(JSON.stringify(body), {status});
const memory = () => { let v = null; return {get: () => v, set: t => { v = t; }, clear: () => { v = null; }}; };

test('public catalog never sends cookies and encodes facets', async () => {
  const calls = [];
  const catalog = publicCatalog({bffOrigin: 'http://bff', fetchImpl: async (url, init) => { calls.push({url, init}); return json({items: [], total: 0}); }});
  await catalog.search({q: 'ruby', category: 'c1', filters: {cut: ['oval', 'round']}, min: {carat: 1}, page: 2});
  assert.equal(calls[0].url, 'http://bff/api/routes/commerce/v1/store/products?q=ruby&category=c1&page=2&f.cut=oval%2Cround&min.carat=1');
  assert.equal(calls[0].init.credentials, 'omit');
  assert.equal(calls[0].init.headers.Cookie, undefined);
  assert.equal(searchParams(), '');
});

test('unsafe calls fetch and send the CSRF token once; safe calls do not', async () => {
  const calls = [];
  const shop = createShop({tokens: memory(), fetchImpl: async (url, init = {}) => {
    calls.push({url, init});
    return url === '/auth/csrf' ? json({headerName: 'X-XSRF', token: 'tok'}) : json({cartId: 'c', lines: []});
  }});
  await shop.cart();
  assert.equal(calls.filter(c => c.url === '/auth/csrf').length, 0);
  await shop.addItem('o1');
  await shop.addItem('o2');
  assert.equal(calls.filter(c => c.url === '/auth/csrf').length, 1);
  const add = calls.at(-1);
  assert.equal(add.init.headers['X-XSRF'], 'tok');
  assert.equal(add.init.credentials, 'same-origin');
});

test('the guest cart token travels only as the query parameter and is dropped once merged', async () => {
  const tokens = memory();
  const urls = [];
  let token = 'guest-token';
  const shop = createShop({tokens, fetchImpl: async (url, init = {}) => {
    urls.push(url);
    if (url === '/auth/csrf') return json({headerName: 'X', token: 't'});
    return json({cartToken: token, cart: {cartId: 'c', lines: []}});
  }});
  await shop.ensureCart();
  assert.equal(tokens.get(), 'guest-token');
  await shop.addItem('o1');
  assert.match(urls.at(-1), /carts\/current\/items\?cart=guest-token$/);
  token = null;
  await shop.ensureCart();
  assert.equal(tokens.get(), null);
});

test('server errors keep their code and field violations', async () => {
  const shop = createShop({tokens: memory(), fetchImpl: async () => json({code: 'VALIDATION', message: 'bad', violations: [{field: 'quantity', message: 'too many'}]}, 422)});
  await assert.rejects(shop.orders(), e => e instanceof CommerceError && e.status === 422 && e.code === 'VALIDATION' && e.violations[0].field === 'quantity');
});

test('an anonymous session is null, not an error', async () => {
  assert.equal(await createShop({tokens: memory(), fetchImpl: async () => json({authenticated: false})}).session(), null);
  assert.equal(await createShop({tokens: memory(), fetchImpl: async () => json({}, 401)}).session(), null);
  assert.deepEqual(await createShop({tokens: memory(), fetchImpl: async () => json({identity: {id: 'u'}})}).session(), {id: 'u'});
});

test('browser token store survives storage being unavailable', () => {
  const store = browserTokenStore();
  assert.equal(store.get(), null);
  store.set('x');
  store.clear();
});

test('JSON-LD states only catalog facts', () => {
  const ld = productJsonLd({product: {productId: 'p', title: {fa: 'یاقوت', en: 'Ruby'}}, offers: [{price: 100, currency: 'IRR', available: 0, digital: false, condition: 'NEW'}, {price: 5, currency: 'USD', available: 0, digital: true}]}, {url: 'https://s/p', locale: 'fa'});
  assert.equal(ld.name, 'یاقوت');
  assert.equal(ld.offers[0].availability, 'https://schema.org/OutOfStock');
  assert.equal(ld.offers[1].availability, 'https://schema.org/InStock', 'digital goods have no stock count');
  assert.equal(ld.aggregateRating, undefined);
  assert.equal(breadcrumbJsonLd([{name: 'a', url: 'u'}]).itemListElement[0].position, 1);
});

test('locale helpers and sitemap escaping', () => {
  assert.equal(directionOf('fa-IR'), 'rtl');
  assert.equal(directionOf('ar'), 'rtl');
  assert.equal(directionOf('en'), 'ltr');
  assert.equal(localized({en: 'x'}, 'fa'), 'x');
  assert.equal(localized(null, 'fa'), '');
  assert.match(sitemapXml(['https://s/?a=1&b=2']), /a=1&amp;b=2/);
});
