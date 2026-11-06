// Mounts the real built bundle in a DOM with the Hive micro-app contract and a stubbed server.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import test from 'node:test';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {JSDOM} from 'jsdom';

const DIST = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist', 'modules');
const AREAS = ['catalog', 'orders', 'payments', 'customers', 'sellers', 'finance', 'digital', 'settings'];

const dom = new JSDOM('<!doctype html><div id="host"></div>', {url: 'http://127.0.0.1/admin'});
for (const k of ['window', 'document', 'HTMLElement', 'Node', 'Event', 'MutationObserver']) globalThis[k] = k === 'window' ? dom.window : dom.window[k];
globalThis.IS_REACT_ACT_ENVIRONMENT = false;
Object.defineProperty(globalThis, 'navigator', {value: dom.window.navigator, configurable: true});

const context = area => ({contractVersion: '1.1.0', moduleKey: `commerce-admin-${area}`, applicationKey: 'test', instanceId: 'i', slotId: 's', route: '/', params: {}, locale: 'en', direction: 'LTR',
  context: {}, permissions: {can: () => true}, events: {publish() {}, subscribe: () => () => {}}, signal: new AbortController().signal, basePath: '/', navigate() {}});

const settle = async () => { for (let i = 0; i < 20; i++) await new Promise(r => setTimeout(r, 10)); };
const setValue = (element, value) => {
  const proto = element.tagName === 'SELECT' ? dom.window.HTMLSelectElement.prototype : dom.window.HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(element, value);
  element.dispatchEvent(new dom.window.Event(element.tagName === 'SELECT' ? 'change' : 'input', {bubbles: true}));
};

for (const area of AREAS) {
  test(`${area}: bundle honours the Hive contract and its manifests are consistent`, async () => {
    const dir = join(DIST, `commerce-admin-${area}`);
    const app = (await import(pathToFileURL(join(dir, 'entry.js')).href)).default;
    assert.equal(app.contractVersion, '1.1.0');
    assert.equal(typeof app.create, 'function');
    const mf = JSON.parse(readFileSync(join(dir, 'mf-manifest.json'), 'utf8'));
    assert.match(mf.artifact.integrity, /^sha384-/);
    assert.equal(mf.styleIsolation, 'SHADOW_DOM');
    assert.equal(mf.routes[0].resource, `commerce.admin.${area}`);
  });
}

test('catalog: the product form is generated from the schema and posts typed values', async () => {
  const posts = [];
  const type = {key: 'gem', kind: 'PHYSICAL', name: {en: 'Gem'}, versions: [{version: 1, status: 'PUBLISHED', attributes: [
    {key: 'carat', type: 'DECIMAL', required: true, variantDefining: false, options: []},
    {key: 'weight', type: 'MEASUREMENT', required: false, variantDefining: false, options: [], units: ['g', 'kg']},
    {key: 'cut', type: 'ENUM', required: true, variantDefining: true, options: ['oval', 'round']}]}]};
  globalThis.fetch = async (url, init = {}) => {
    const json = body => new Response(JSON.stringify(body), {status: 200, headers: {'Content-Type': 'application/json'}});
    if (url === '/auth/csrf') return json({headerName: 'X-CSRF', token: 't'});
    if (init.method === 'POST') { posts.push({url, headers: init.headers, body: JSON.parse(init.body)}); return json({id: 'new'}); }
    if (url.endsWith('/v1/admin/attributes')) return json([{key: 'carat', labels: {en: 'Carat weight'}, type: 'DECIMAL', options: []}, {key: 'cut', labels: {en: 'Cut'}, type: 'ENUM', options: ['oval', 'round'], optionLabels: {oval: {en: 'Oval'}}}]);
    if (url.endsWith('/v1/admin/product-types')) return json([type]);
    return json([]);
  };
  const app = (await import(pathToFileURL(join(DIST, 'commerce-admin-catalog', 'entry.js')).href)).default;
  const host = document.getElementById('host');
  const instance = app.create();
  await instance.mount(host, context('catalog'));
  await settle();
  setValue(host.querySelector('select[name=product-type]'), 'gem');
  await settle();

  const fields = host.querySelector('[data-testid=generated-fields]');
  assert.ok(fields.textContent.includes('Carat weight'), 'label comes from the attribute library');
  assert.ok(fields.querySelector('input[name=carat][type=number]'), 'DECIMAL renders a number input');
  assert.ok(fields.querySelector('select[aria-label="weight unit"]'), 'MEASUREMENT renders a unit selector');
  const cut = host.querySelector('select[name=cut]');
  assert.ok(cut, 'variant-defining ENUM renders under the variant');
  assert.ok(cut.textContent.includes('Oval'), 'option labels are localized');

  setValue(host.querySelector('input[name=title]'), 'Oval ruby');
  setValue(host.querySelector('input[name=slug]'), 'oval-ruby');
  setValue(fields.querySelector('input[name=carat]'), '1.25');
  setValue(host.querySelector('input[name=sku-0]'), 'RUBY-1');
  setValue(cut, 'oval');
  await settle();
  const create = [...host.querySelectorAll('button')].find(b => b.textContent === 'Create' && b.closest('section').querySelector('select[name=product-type]'));
  create.click();
  await settle();
  const post = posts.find(p => p.url.endsWith('/v1/admin/products'));
  assert.ok(post, 'the product was posted through the BFF route');
  assert.equal(post.url, '/api/routes/commerce/v1/admin/products');
  assert.equal(post.headers['X-CSRF'], 't', 'unsafe calls carry the CSRF header');
  assert.deepEqual(post.body.attributes, {carat: 1.25});
  assert.deepEqual(post.body.variants, [{sku: 'RUBY-1', attributes: {cut: 'oval'}}]);
  await instance.unmount();
});

test('selecting a product tells other modules through Hive events; the digital module listens and never imports catalog', async () => {
  const listeners = new Map();
  const events = {publish: (name, payload) => (listeners.get(name) ?? []).forEach(l => l({name, payload})), subscribe: (name, l) => { listeners.set(name, [...(listeners.get(name) ?? []), l]); return () => {}; }};
  globalThis.fetch = async url => new Response(JSON.stringify(url.endsWith('/v1/admin/products') ? [{id: 'p-1', title: {en: 'One'}, slug: 'one', variants: [], status: 'DRAFT', schemaVersion: 1, productType: 'x'}] : []), {status: 200});
  const load = async area => (await import(pathToFileURL(join(DIST, `commerce-admin-${area}`, 'entry.js')).href)).default;
  const host = document.getElementById('host');
  host.innerHTML = '<div id="a"></div><div id="b"></div>';
  const digital = (await load('digital')).create(), catalog = (await load('catalog')).create();
  await digital.mount(host.querySelector('#a'), {...context('digital'), events});
  await catalog.mount(host.querySelector('#b'), {...context('catalog'), events});
  await settle();
  [...host.querySelectorAll('button')].find(b => b.textContent === 'Select').click();
  await settle();
  assert.equal(host.querySelector('#a input[name=product-id]').value, 'p-1');
  await digital.unmount();
  await catalog.unmount();
  host.innerHTML = '';
});

test('a denied call is shown to the operator, never swallowed', async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({code: 'ACCESS_DENIED', message: 'Not allowed'}), {status: 403});
  const app = (await import(pathToFileURL(join(DIST, 'commerce-admin-settings', 'entry.js')).href)).default;
  const host = document.getElementById('host');
  const instance = app.create();
  await instance.mount(host, context('settings'));
  await settle();
  assert.ok(host.querySelector('[role=alert]').textContent.includes('ACCESS_DENIED'));
  await instance.unmount();
});
