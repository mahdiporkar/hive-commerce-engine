// What this store needs from the engine release it pins (or from ENGINE_VERSION during an upgrade check). No running
// stack: a release that fails here is never deployed for this store (Scenario F).
import assert from 'node:assert/strict';
import {readdirSync, readFileSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import test from 'node:test';
import {fileURLToPath, pathToFileURL} from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = path => JSON.parse(readFileSync(join(ROOT, path), 'utf8'));
const version = process.env.ENGINE_VERSION ?? read('engine.lock.json').version;
const engine = join('.engine', version);
const store = read('storefront/config/store.json');

const walk = dir => readdirSync(dir, {withFileTypes: true}).flatMap(e => e.name === 'node_modules' || e.name === '.next' ? [] : e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)]);

test('the store configuration and catalog pass this release\'s builder', async () => {
  const {validateCatalog, validateStore} = await import(pathToFileURL(join(ROOT, engine, 'builder/package/index.mjs')).href);
  assert.deepEqual(validateStore(store), []);
  assert.deepEqual(validateCatalog(store, {...read('storefront/config/taxonomy.json'), ...read('seed/products.json')}), []);
});

test('every operation the storefront SDK calls is published by this release, with the Hive access the storefront relies on', () => {
  const service = JSON.parse(readFileSync(join(ROOT, engine, 'hive/templates/commerce-service.json'), 'utf8'));
  const operation = (method, path) => service.operations.find(o => o.method === method && o.pathPattern === path);
  const expected = [
    ['GET', '/v1/store/categories', 'PUBLIC'], ['GET', '/v1/store/products', 'PUBLIC'], ['GET', '/v1/store/products/{slug}', 'PUBLIC'],
    ['POST', '/v1/shop/carts', 'HYBRID'], ['GET', '/v1/shop/carts/current', 'HYBRID'], ['POST', '/v1/shop/carts/current/items', 'HYBRID'],
    ['POST', '/v1/shop/checkouts', 'HYBRID'], ['POST', '/v1/shop/payments/{paymentId}/verify', 'HYBRID'], ['GET', '/v1/shop/orders', 'HYBRID'],
    ['GET', '/v1/shop/library', 'HYBRID'], ['POST', '/v1/shop/library/{entitlementId}/downloads', 'HYBRID'],
    ['POST', '/v1/seller/applications', 'HYBRID'], ['GET', '/v1/seller/me', 'HYBRID']];
  for (const [method, path, access] of expected) assert.equal(operation(method, path)?.access, access, `${method} ${path}`);
  const openapi = read(join(engine, 'openapi.json'));
  for (const [method, path] of expected) assert.ok(openapi.paths[path]?.[method.toLowerCase()], `${method} ${path} is missing from the API contract`);
});

test('this release ships the admin bundles and Hive templates the store registers', () => {
  const modules = readdirSync(join(ROOT, engine, 'admin-mfes/modules'));
  for (const area of ['catalog', 'orders', 'sellers', 'finance', 'digital', 'settings']) assert.ok(modules.includes(`commerce-admin-${area}`), area);
  for (const moduleKey of modules) {
    const mf = JSON.parse(readFileSync(join(ROOT, engine, 'admin-mfes/modules', moduleKey, 'mf-manifest.json'), 'utf8'));
    assert.equal(mf.contractVersion, '1.1.0', moduleKey);
    assert.match(mf.artifact.integrity, /^sha384-/, moduleKey);
  }
  for (const role of JSON.parse(readFileSync(join(ROOT, engine, 'hive/templates/roles.json'), 'utf8')).roles) assert.ok(role.key.startsWith('commerce-'), role.key);
});

test('this repository duplicates no engine logic and holds no engine source', () => {
  const files = walk(ROOT).filter(f => !f.includes('.engine') && !f.includes(`${'.git'}/`) && !f.includes('.git\\') && !f.includes('target'));
  const mine = files.filter(f => /\.(java|ts|tsx|mjs)$/.test(f));
  for (const file of mine.filter(f => f.endsWith('.java'))) assert.ok(!/package io\.hivecommerce\.(?!spi)/.test(readFileSync(file, 'utf8')), `${file} sits in an engine package`);
  // The storefront reaches the engine through the SDK only: no direct route calls, no price or tax arithmetic of its own.
  for (const file of mine.filter(f => f.includes('storefront') && /\.tsx?$/.test(f))) {
    const text = readFileSync(file, 'utf8');
    assert.ok(!text.includes('/api/routes/'), `${file} bypasses the SDK`);
    assert.ok(!/\b(unitPrice|price|amount)\s*\*/.test(text), `${file} computes money`);
  }
});

test('no secret is committed', () => {
  const files = walk(ROOT).filter(f => !f.includes('.engine') && !f.includes('.git') && !f.endsWith('package-lock.json'));
  for (const file of files) {
    const text = readFileSync(file, 'utf8');
    assert.ok(!/-----BEGIN [A-Z ]*PRIVATE KEY-----/.test(text), file);
    if (file.endsWith('env.example')) for (const line of text.split('\n').filter(l => /PASSWORD|SECRET|ACCESS_KEY/.test(l) && !l.startsWith('#'))) assert.match(line, /=replace-me$/, line);
  }
});
