// Store acceptance through the real stack and a real browser. Generic checks here; store journeys in journeys.mjs. One
// file, one stack: the store's ports are fixed, so test files must not run side by side.
import assert from 'node:assert/strict';
import test from 'node:test';
import {ORIGIN, kit, launchBrowser, seedStore, startStore, store} from './harness.mjs';
import {journeys} from './journeys.mjs';

test(`${store.key}: storefront through Hive`, {timeout: 1800000}, async t => {
  const stack = await startStore(t);
  await seedStore(stack.send);
  const browser = await launchBrowser();
  t.after(() => browser.close());

  await t.test('public pages render on the server with no cookie, in the store language and direction', async () => {
    const response = await fetch(`${ORIGIN}/${store.defaultLocale}`);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('set-cookie'), null);
    assert.match(await response.text(), new RegExp(`<html lang="${store.defaultLocale}" dir="(rtl|ltr)"`));
  });

  await t.test('sitemap and robots describe this store', async () => {
    assert.match(await (await fetch(`${ORIGIN}/sitemap.xml`)).text(), new RegExp(`<loc>${ORIGIN}/${store.defaultLocale}/products</loc>`));
    assert.match(await (await fetch(`${ORIGIN}/robots.txt`)).text(), /Sitemap: /);
  });

  await t.test('admin operations stay closed to anonymous callers on the store origin', async () => {
    const response = await fetch(`${ORIGIN}/api/routes/commerce/v1/admin/orders`, {redirect: 'manual'});
    assert.ok([401, 403].includes(response.status), String(response.status));
  });

  await t.test('the engine refuses a caller that did not come through Hive', async () => {
    assert.ok([401, 403].includes((await fetch(`${kit.ENGINE}/v1/store/categories`)).status));
  });

  await journeys(t, {stack, browser});
});
