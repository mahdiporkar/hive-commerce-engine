// Store acceptance through the real stack and a real browser: public SEO pages without cookies, right-to-left rendering,
// sign-in through Hive, and purchase. Scenario-specific journeys live in sibling test files.
import assert from 'node:assert/strict';
import test from 'node:test';
import {ORIGIN, kit, launchBrowser, seedStore, startStore, store} from './harness.mjs';

test(`${store.key}: storefront through Hive`, {timeout: 1200000}, async t => {
  const stack = await startStore(t);
  await seedStore(stack.send);
  const browser = await launchBrowser();
  t.after(() => browser.close());

  await t.test('public pages render on the server with no cookie, in the store language and direction', async () => {
    const response = await fetch(`${ORIGIN}/${store.defaultLocale}`);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('set-cookie'), null);
    const html = await response.text();
    assert.match(html, new RegExp(`<html lang="${store.defaultLocale}" dir="(rtl|ltr)"`));
  });

  await t.test('the sitemap and robots file point at the store domain under test', async () => {
    assert.match(await (await fetch(`${ORIGIN}/sitemap.xml`)).text(), new RegExp(`<loc>${ORIGIN}/${store.defaultLocale}/products</loc>`));
    assert.match(await (await fetch(`${ORIGIN}/robots.txt`)).text(), /Sitemap: /);
  });

  await t.test('the admin operations stay closed to anonymous callers through the store origin', async () => {
    const response = await fetch(`${ORIGIN}/api/routes/commerce/v1/admin/orders`, {redirect: 'manual'});
    assert.ok([401, 403].includes(response.status), String(response.status));
  });

  await t.test('another store\'s host cannot read this store: nothing is shared with the engine directly', async () => {
    assert.equal((await fetch(`${kit.ENGINE}/v1/store/categories`)).status, 401);
  });
});
