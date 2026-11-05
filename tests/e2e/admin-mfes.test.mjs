// Registers one store with hive/register.mjs, as a client repository does, and proves that the admin bundles are served
// through Hive's artifact gateway under their pinned integrity and that navigation follows the role grants.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync, readdirSync} from 'node:fs';
import {resolve} from 'node:path';
import test from 'node:test';
import {register} from '../../hive/register.mjs';
import {APPLICATION, BFF, ENGINE, MODULES_URL, PORTS, ROOT, cookieClient, serveDirectory, signIn, startEnvironment} from './support.mjs';

const MODULES = resolve(ROOT, 'admin-mfes/dist/modules');

test('hive/register.mjs: idempotent registration, admin bundles through the BFF, navigation follows grants', {timeout: 900000}, async t => {
  const {provision, provisioningPassword} = await startEnvironment(t);
  const host = await serveDirectory(MODULES, PORTS.modules);
  t.after(() => host.close());
  const options = {authorization: `http://127.0.0.1:${PORTS.authorization}`, password: provisioningPassword, application: APPLICATION, displayName: 'Commerce e2e',
    engineUrl: ENGINE, modulesUrl: MODULES_URL, modulesDir: MODULES};

  const first = await register(options);
  assert.ok(first.length > 100, 'all objects applied');
  const second = await register(options);
  assert.ok(second.every(s => s.status < 500), 'a second run is safe');
  assert.ok(second.filter(s => s.name.startsWith('operation ')).every(s => s.status === 409), 'existing operations are kept');

  const alice = await signIn('alice.catalog', 'test-only-password-alice');
  const bob = await signIn('bob.shopper', 'test-only-password-bob');
  const assigned = await provision('POST', '/admin/roles/commerce-catalog-manager/assignments', {subject: `user:${alice.session.identity.id}`});
  assert.ok([200, 201].includes(assigned.status), JSON.stringify(assigned.body));

  await t.test('the granted operator gets the catalog module; the runtime context never reveals the registered address', async () => {
    let context;
    for (let n = 0; n < 60; n++) {
      context = await (await alice.bff(`/api/me/context?applicationKey=${APPLICATION}`)).text();
      if (context.includes('commerce-admin-catalog')) break;
      await new Promise(r => setTimeout(r, 500));
    }
    assert.ok(context.includes('commerce-admin-catalog'), context.slice(0, 500));
    assert.ok(!context.includes(MODULES_URL), 'the registered address stays server-side');
    assert.ok(context.includes('/api/mfe/commerce-admin-catalog/'));
  });

  await t.test('every bundle is served by the gateway byte for byte, matching its manifest integrity', async () => {
    for (const moduleKey of readdirSync(MODULES)) {
      const manifest = JSON.parse(readFileSync(resolve(MODULES, moduleKey, 'mf-manifest.json'), 'utf8'));
      const response = await alice.bff(`/api/mfe/${moduleKey}/${manifest.manifestVersion}/entry.js`);
      assert.equal(response.status, 200, moduleKey);
      const bytes = Buffer.from(await response.arrayBuffer());
      assert.equal(`sha384-${createHash('sha384').update(bytes).digest('base64')}`, manifest.artifact.integrity, moduleKey);
    }
  });

  await t.test('the module list follows Hive grants: a shopper sees no admin module', async () => {
    const context = await (await bob.bff(`/api/me/context?applicationKey=${APPLICATION}`)).text();
    assert.ok(!context.includes('commerce-admin-finance'), context.slice(0, 500));
    assert.ok(!context.includes('commerce-admin-catalog'), context.slice(0, 500));
  });

  await t.test('the route registered by the tool reaches the engine; an anonymous admin call stops at Hive', async () => {
    const shared = await cookieClient(BFF)('/api/routes/commerce/v1/store/categories');
    assert.equal(shared.status, 200);
    const denied = await bob.bff('/api/routes/commerce/v1/admin/products');
    assert.equal(denied.status, 403);
  });
});
