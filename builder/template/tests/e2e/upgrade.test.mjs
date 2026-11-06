// Scenario F for this store: the running version is upgraded to a candidate engine on the same database and storage, keeps
// working, and rolls back with the data intact. Run only as an upgrade check:
//   ENGINE_CANDIDATE=0.2.0 node --test tests/e2e/upgrade.test.mjs   (both releases fetched into .engine/)
import assert from 'node:assert/strict';
import {join} from 'node:path';
import test from 'node:test';
import {ENGINE_JAR, ROOT, USERS, api, kit, seedStore, startStore, store, version} from './harness.mjs';
import {registerStore} from '../../tools/register.mjs';

const candidate = process.env.ENGINE_CANDIDATE;
const candidateJar = join(ROOT, '.engine', candidate ?? 'none', 'commerce-engine.jar');
const route = path => `${kit.BFF}/api/routes/commerce${path}`;

async function until(check, what, attempts = 120) {
  for (let n = 0; n < attempts; n++) {
    if (await check()) return;
    await new Promise(r => setTimeout(r, 500));
  }
  assert.fail(`timed out waiting for ${what}`);
}

test(`${store.key}: engine ${version} upgrades to ${candidate} and rolls back`, {skip: !candidate, timeout: 1500000}, async t => {
  const stack = await startStore(t);
  await seedStore(stack.send);
  const buyer = await kit.signIn(USERS.buyer.username, USERS.buyer.password);
  const shop = api(buyer.bff);

  const buySomething = async () => {
    const page = await (await fetch(route('/v1/store/products?size=50'))).json();
    for (const card of page.items) {
      const detail = await (await fetch(route(`/v1/store/products/${card.slug}`))).json();
      const offer = detail.offers.find(o => o.digital || o.available > 0);
      if (!offer) continue;
      assert.equal((await shop('POST', '/v1/shop/carts/current/items', {offerId: offer.offerId, quantity: 1})).status, 200);
      const checkout = (await shop('POST', '/v1/shop/checkouts', {idempotencyKey: `upgrade-${crypto.randomUUID()}`})).body;
      const ref = checkout.redirectUrl.slice(checkout.redirectUrl.lastIndexOf('/') + 1);
      assert.equal((await fetch(`${kit.ENGINE}/hooks/fake-psp/sessions/${ref}/approve`, {method: 'POST'})).status, 200);
      assert.equal((await shop('POST', `/v1/shop/payments/${checkout.paymentId}/verify`)).body.status, 'CAPTURED');
      await until(async () => ['PAID', 'COMPLETED'].includes((await shop('GET', `/v1/shop/orders/${checkout.orderId}`)).body.status), 'the order to be paid');
      return checkout.orderId;
    }
    assert.fail('nothing left to buy');
  };
  const migrations = () => Number(stack.engineSql("select count(*) from information_schema.tables where table_name = 'flyway_schema_history'"));
  const catalogSize = async () => (await (await fetch(route('/v1/store/products?size=50'))).json()).total;

  const firstOrder = await buySomething();
  const before = {orders: (await shop('GET', '/v1/shop/orders')).body.length, products: await catalogSize(), histories: migrations()};

  await t.test(`upgrade: ${candidate} starts on ${version} data, migrates forward, keeps every order`, async () => {
    await stack.restartEngine(candidateJar);
    await registerStore({authorization: `http://127.0.0.1:${kit.PORTS.authorization}`, password: stack.provisioningPassword, engineUrl: kit.ENGINE, modulesUrl: kit.MODULES_URL, engineVersion: candidate});
    assert.equal(await catalogSize(), before.products);
    assert.equal((await shop('GET', '/v1/shop/orders')).body.length, before.orders);
    assert.equal((await shop('GET', `/v1/shop/orders/${firstOrder}`)).status, 200);
    await buySomething();
    assert.equal((await shop('GET', '/v1/shop/orders')).body.length, before.orders + 1);
  });

  await t.test(`rollback: ${version} starts again on the migrated database, and orders from both versions are intact`, async () => {
    await stack.restartEngine(ENGINE_JAR);
    assert.equal(await catalogSize(), before.products);
    assert.equal((await shop('GET', '/v1/shop/orders')).body.length, before.orders + 1);
    assert.equal((await shop('GET', `/v1/shop/orders/${firstOrder}`)).status, 200);
    await buySomething();
    assert.equal((await shop('GET', '/v1/shop/orders')).body.length, before.orders + 2);
    const balance = await stack.send('GET', `/v1/admin/finance/balances?currency=${store.currency}`);
    assert.equal(Number(balance.body.sum), 0, 'the ledger still balances');
  });

  await t.test(`the admin bundles roll back too: the previous artifact version can be activated again`, async () => {
    const {existsSync, readFileSync, readdirSync} = await import('node:fs');
    const modulesOf = release => join(ROOT, '.engine', release, 'admin-mfes/modules');
    for (const moduleKey of readdirSync(modulesOf(candidate))) {
      const manifest = join(modulesOf(version), moduleKey, 'mf-manifest.json');
      // A module only the candidate has is switched off; the rest return to their previous bundle.
      const result = existsSync(manifest)
        ? await stack.provision('POST', `/admin/modules/${moduleKey}/artifacts/${JSON.parse(readFileSync(manifest, 'utf8')).manifestVersion}/activate`)
        : await stack.provision('POST', `/admin/modules/${moduleKey}/deactivate`);
      assert.ok(result.status < 300, `${moduleKey}: ${JSON.stringify(result.body)}`);
    }
    const previous = JSON.parse(readFileSync(join(modulesOf(version), 'commerce-admin-catalog', 'mf-manifest.json'), 'utf8'));
    const served = await stack.operator.bff(`/api/mfe/commerce-admin-catalog/${previous.manifestVersion}/entry.js`);
    assert.equal(served.status, 200, 'the previous bundle is served again through Hive');
  });
});
