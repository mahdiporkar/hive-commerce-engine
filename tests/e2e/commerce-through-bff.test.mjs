// Phase 1 exit test: secure product creation through the real Hive BFF (Layer 1: Hive resource actions in OpenFGA;
// Layer 2: Commerce identity, tenant and scope checks). Run: node --test tests/e2e/ (Docker, Java 21, Node 22+).
import assert from 'node:assert/strict';
import test from 'node:test';
import {createHmac} from 'node:crypto';
import {APPLICATION, BFF, ENGINE, FAKE_PSP_SECRET, cookieClient, signIn, startEnvironment, template} from './support.mjs';

const route = path => `/api/routes/commerce${path}`;
const suffix = Date.now().toString(36);

async function registerCommerce(provision) {
  const step = async (name, method, path, body, accept = [200, 201]) => {
    const response = await provision(method, path, body);
    assert.ok(accept.includes(response.status), `${name}: HTTP ${response.status} ${JSON.stringify(response.body)}`);
    return response.body;
  };
  await step('application', 'POST', '/admin/applications', {key: APPLICATION, displayName: 'Commerce e2e'});
  await step('module', 'POST', '/admin/modules', {applicationKey: APPLICATION, moduleKey: 'commerce-api', displayName: 'Commerce API', definitionMode: 'MANIFEST'});
  const manifest = template('commerce-api/resource-manifest.json', {applicationKey: APPLICATION});
  await step('resource manifest', 'POST', '/admin/modules/commerce-api/resource-manifests', manifest);
  await step('publish manifest', 'POST', `/admin/modules/commerce-api/resource-manifests/${manifest.manifestVersion}/publish`);
  const service = template('commerce-service.json', {applicationKey: APPLICATION, engineBaseUrl: ENGINE});
  await step('service target', 'POST', '/admin/service-targets', service.target);
  await step('proxy route', 'POST', '/admin/proxy-routes', service.route);
  for (const operation of service.operations) await step(`operation ${operation.key}`, 'POST', `/admin/proxy-routes/${service.route.key}/operations`, operation);
  await step('role', 'POST', '/admin/roles', {key: 'catalog-manager', displayName: 'Catalog manager', description: 'Creates and publishes catalog entries'});
  for (const action of ['read', 'create', 'configure', 'update', 'publish']) {
    await step(`grant ${action}`, 'POST', '/admin/grants', {subject: 'role:catalog-manager', applicationKey: APPLICATION, resourceKey: 'commerce.api.catalog', action});
  }
  await step('role order-manager', 'POST', '/admin/roles', {key: 'order-manager', displayName: 'Order manager', description: 'Views and fulfills orders'});
  for (const action of ['view', 'fulfill']) {
    await step(`grant orders ${action}`, 'POST', '/admin/grants', {subject: 'role:order-manager', applicationKey: APPLICATION, resourceKey: 'commerce.api.orders', action});
  }
}

/**
 * Waits until Hive's server-side decision lets the operator through: a read of an unknown product changes from 403
 * (denied at the BFF) to 404 (answered by the engine). The grant reaches OpenFGA through Hive's outbox asynchronously.
 */
async function waitUntilAllowed(bff) {
  for (let n = 0; n < 60; n++) {
    const r = await bff(route(`/v1/admin/products/${crypto.randomUUID()}`));
    if (r.status === 404) return;
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  assert.fail('the role grant never became effective');
}

async function send(client, method, path, body) {
  const headers = {'Content-Type': 'application/json', ...(method === 'GET' ? {} : await client.csrf())};
  const response = await client(route(path), {method, headers, ...(body === undefined ? {} : {body: JSON.stringify(body)})});
  const text = await response.text();
  return {status: response.status, body: text ? JSON.parse(text) : null, correlationId: response.headers.get('x-correlation-id')};
}

test('Through Hive: secure catalog administration (Phase 1) and Scenario A checkout (Phase 2)', {timeout: 900000}, async t => {
  const {provision, engineSql, engineLog} = await startEnvironment(t);
  await registerCommerce(provision);

  const alice = await signIn('alice.catalog', 'test-only-password-alice');
  const bob = await signIn('bob.shopper', 'test-only-password-bob');
  for (const role of ['catalog-manager', 'order-manager']) {
    const assigned = await provision('POST', `/admin/roles/${role}/assignments`, {subject: `user:${alice.session.identity.id}`});
    assert.ok([200, 201].includes(assigned.status), JSON.stringify(assigned.body));
  }
  await waitUntilAllowed(alice.bff);

  const material = `material_${suffix}`, color = `color_${suffix}`, type = `generic-${suffix}`;
  let productId;

  await t.test('granted operator creates attributes, a product type and a product through the BFF', async () => {
    for (const body of [
      {key: material, type: 'ENUM', labels: {en: 'Material', fa: 'جنس'}, options: [{code: 'wood', labels: {en: 'Wood'}}, {code: 'clay', labels: {en: 'Clay'}}]},
      {key: color, type: 'COLOR', labels: {en: 'Color'}, options: [{code: 'red', labels: {en: 'Red'}}, {code: 'blue', labels: {en: 'Blue'}}]}]) {
      const r = await send(alice.bff, 'POST', '/v1/admin/attributes', body);
      assert.equal(r.status, 201, JSON.stringify(r.body));
    }
    let r = await send(alice.bff, 'POST', '/v1/admin/product-types', {key: type, name: {en: 'Generic'},
      attributes: [{key: material, required: true, variantDefining: false}, {key: color, required: true, variantDefining: true}]});
    assert.equal(r.status, 201, JSON.stringify(r.body));
    r = await send(alice.bff, 'POST', `/v1/admin/product-types/${type}/versions/1/publish`);
    assert.equal(r.status, 200, JSON.stringify(r.body));
    r = await send(alice.bff, 'POST', '/v1/admin/products', {productType: type, title: {en: 'Bowl', fa: 'کاسه'}, slug: `bowl-${suffix}`,
      attributes: {[material]: 'clay'}, variants: [{sku: `BOWL-${suffix}-R`, attributes: {[color]: 'red'}}, {sku: `BOWL-${suffix}-B`, attributes: {[color]: 'blue'}}]});
    assert.equal(r.status, 201, JSON.stringify(r.body));
    assert.equal(r.body.variants.length, 2);
    productId = r.body.id;
    assert.ok(r.correlationId && engineLog().includes(r.correlationId), 'the engine handled the allowed request');

    const read = await send(alice.bff, 'GET', `/v1/admin/products/${productId}`);
    assert.equal(read.status, 200);
    assert.equal(read.body.title.fa, 'کاسه');
  });

  await t.test('the engine bound the forwarded Hive identity to the Keycloak subject', async () => {
    const row = engineSql(`select hive_user_id from platform.identity_binding where subject = '${alice.session.identity.subject}'`);
    assert.equal(row, alice.session.identity.id);
    assert.equal(engineSql(`select created_by from catalog.product where id = '${productId}'`), alice.session.identity.id);
  });

  await t.test('signed-in user without the grant is denied by Hive before the engine is contacted', async () => {
    const before = engineSql('select count(*) from catalog.attribute_definition');
    for (const [method, path, body] of [
      ['POST', '/v1/admin/attributes', {key: `x_${suffix}`, type: 'STRING', labels: {en: 'X'}}],
      ['POST', '/v1/admin/products', {productType: type, title: {en: 'X'}, slug: `x-${suffix}`, attributes: {[material]: 'wood'}, variants: [{sku: `X-${suffix}`, attributes: {[color]: 'red'}}]}],
      ['GET', `/v1/admin/products/${productId}`]]) {
      const r = await send(bob.bff, method, path, body);
      assert.equal(r.status, 403, `${method} ${path}: ${JSON.stringify(r.body)}`);
      assert.equal(r.body.code, 'ACCESS_DENIED');
      assert.ok(r.body.correlationId, 'denial carries a correlation id');
      assert.ok(!engineLog().includes(r.body.correlationId), `${method} ${path} must not reach the engine`);
    }
    assert.equal(engineSql('select count(*) from catalog.attribute_definition'), before);
  });

  await t.test('anonymous caller is denied by Hive before the engine is contacted', async () => {
    const anonymous = (await import('./support.mjs')).cookieClient(BFF);
    const r = await send(anonymous, 'POST', '/v1/admin/products', {});
    assert.equal(r.status, 401);
    assert.equal(r.body.code, 'AUTHENTICATION_REQUIRED');
    assert.ok(!engineLog().includes(r.body.correlationId));
  });

  await t.test('unsafe call without the CSRF header is refused by the BFF', async () => {
    const response = await alice.bff(route('/v1/admin/attributes'), {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}'});
    assert.equal(response.status, 403);
  });

  await t.test('a forged X-Hive-User-Id sent directly to the engine is rejected', async () => {
    const response = await fetch(`${ENGINE}/v1/admin/products`, {method: 'POST', headers: {'Content-Type': 'application/json',
      'X-Hive-User-Id': alice.session.identity.id, 'X-Hive-Tenant-Id': 'default', 'X-Hive-Route': 'commerce', 'X-Hive-Operation': 'admin-product-create'}, body: '{}'});
    assert.equal(response.status, 401);
    assert.equal((await response.json()).code, 'IDENTITY_UNVERIFIED');
  });

  // ---------------------------------------------------------------- Phase 2: Scenario A through Hive
  const until = async (probe, what) => {
    for (let n = 0; n < 80; n++) { const value = await probe(); if (value) return value; await new Promise(r => setTimeout(r, 250)); }
    assert.fail(`timed out waiting for ${what}`);
  };
  let gemOrder, winner, loser;

  await t.test('Scenario A: two shoppers race for one unique item through the BFF; exactly one order is placed', async () => {
    const product = (await send(alice.bff, 'GET', `/v1/admin/products/${productId}`)).body;
    const offer = await send(alice.bff, 'POST', '/v1/admin/offers', {variantId: product.variants[0].id, amount: '850000000', currency: 'IRR', quantity: 1, uniqueItem: true});
    assert.equal(offer.status, 201, JSON.stringify(offer.body));
    assert.equal((await send(alice.bff, 'POST', `/v1/admin/products/${productId}/publish`)).status, 200);
    const anonymous = cookieClient(BFF);
    await until(async () => {
      const detail = await send(anonymous, 'GET', `/v1/store/products/bowl-${suffix}`);
      return detail.status === 200 && detail.body.offers.some(o => o.offerId === offer.body.offerId && o.available === 1);
    }, 'stock to open');

    const carol = await signIn('carol.buyer', 'test-only-password-carol');
    const dave = await signIn('dave.buyer', 'test-only-password-dave');
    for (const shopper of [carol, dave]) {
      const added = await send(shopper.bff, 'POST', '/v1/shop/carts/current/items', {offerId: offer.body.offerId, quantity: 1});
      assert.equal(added.status, 200, JSON.stringify(added.body));
    }
    const results = await Promise.all([carol, dave].map(shopper =>
      send(shopper.bff, 'POST', '/v1/shop/checkouts', {idempotencyKey: `scenario-a-${crypto.randomUUID()}`})));
    assert.deepEqual(results.map(r => r.status).sort(), [201, 409], JSON.stringify(results.map(r => r.body)));
    const won = results.find(r => r.status === 201);
    assert.equal(results.find(r => r.status === 409).body.code, 'OUT_OF_STOCK');
    winner = results[0] === won ? carol : dave;
    loser = winner === carol ? dave : carol;
    gemOrder = won.body;

    // Shopper pays at the PSP; the browser returns, but only a verified result changes the order.
    const ref = gemOrder.redirectUrl.split('/').pop();
    assert.equal((await fetch(`${ENGINE}/hooks/fake-psp/sessions/${ref}/approve`, {method: 'POST'})).status, 200);
    assert.equal((await send(winner.bff, 'GET', `/v1/shop/orders/${gemOrder.orderId}`)).body.status, 'PLACED');
    // The PSP's signed webhook reaches the engine directly (ingress rule for /hooks/, never through the BFF).
    const event = JSON.stringify({eventId: `evt-${crypto.randomUUID()}`, ref, outcome: 'PAID'});
    const signature = createHmac('sha256', FAKE_PSP_SECRET).update(event).digest('hex');
    const hook = await fetch(`${ENGINE}/hooks/payments/fake`, {method: 'POST', headers: {'Content-Type': 'application/json', 'X-Fake-Signature': signature}, body: event});
    assert.equal((await hook.json()).status, 'processed');
    const replay = await fetch(`${ENGINE}/hooks/payments/fake`, {method: 'POST', headers: {'Content-Type': 'application/json', 'X-Fake-Signature': signature}, body: event});
    assert.equal((await replay.json()).status, 'duplicate');
    // Webhooks are not reachable through the BFF (no route operation exists for them).
    const viaBff = await alice.bff(route('/hooks/payments/fake'), {method: 'POST', headers: {'Content-Type': 'application/json', ...(await alice.bff.csrf())}, body: event});
    assert.equal(viaBff.status, 404);
    await until(async () => (await send(winner.bff, 'GET', `/v1/shop/orders/${gemOrder.orderId}`)).body.status === 'PAID', 'order to be paid');
  });

  await t.test('shoppers see only their own orders; anonymous callers are refused by Commerce on HYBRID operations', async () => {
    const foreign = await send(loser.bff, 'GET', `/v1/shop/orders/${gemOrder.orderId}`);
    assert.equal(foreign.status, 404);
    assert.equal((await send(loser.bff, 'GET', '/v1/shop/orders')).body.length, 0);
    const anonymous = await send(cookieClient(BFF), 'GET', '/v1/shop/orders');
    assert.equal(anonymous.status, 401);
    assert.equal(anonymous.body.code, 'AUTHENTICATION_REQUIRED');
    assert.ok(engineLog().includes(anonymous.body.correlationId), 'HYBRID: the BFF forwards and Commerce decides');
  });

  await t.test('operator fulfills through the BFF; an ungranted user cannot see store orders', async () => {
    const fulfillments = await send(alice.bff, 'GET', `/v1/admin/orders/${gemOrder.orderId}/fulfillments`);
    assert.equal(fulfillments.status, 200, JSON.stringify(fulfillments.body));
    const id = fulfillments.body[0].fulfillmentId;
    assert.equal((await send(alice.bff, 'POST', `/v1/admin/fulfillments/${id}/ship`, {carrier: 'Iran Post', trackingCode: 'IR-E2E'})).status, 200);
    assert.equal((await send(alice.bff, 'POST', `/v1/admin/fulfillments/${id}/deliver`)).status, 200);
    await until(async () => (await send(winner.bff, 'GET', `/v1/shop/orders/${gemOrder.orderId}`)).body.status === 'COMPLETED', 'order to complete');
    const denied = await send(bob.bff, 'GET', '/v1/admin/orders');
    assert.equal(denied.status, 403);
    assert.ok(!engineLog().includes(denied.body.correlationId));
  });

  await t.test('revoking the role assignment in Hive denies the operator without any engine change', async () => {
    const assignments = await provision('GET', '/admin/roles/catalog-manager/assignments');
    const assignment = assignments.body.find(a => a.subject === `user:${alice.session.identity.id}` && !a.revokedAt);
    assert.ok(assignment, 'alice holds the role');
    assert.equal((await provision('DELETE', `/admin/roles/catalog-manager/assignments/${assignment.id}`)).status, 204);
    let r;
    for (let n = 0; n < 60; n++) {
      r = await send(alice.bff, 'GET', `/v1/admin/products/${productId}`);
      if (r.status === 403) break;
      await new Promise(resolve => setTimeout(resolve, 500));
    }
    assert.equal(r.status, 403, 'revocation became effective');
    assert.equal(r.body.code, 'ACCESS_DENIED');
    assert.ok(!engineLog().includes(r.body.correlationId));
  });
});
