import assert from 'node:assert/strict';
import {existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {dirname, join} from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {contrast, engineEnvironment, newStore, schemaDiff, seedCatalog, TEMPLATE_DIR, validateCatalog, validateStore} from '../index.mjs';

const store = {key: 'demo-store', displayName: {en: 'Demo', fa: 'نمونه'}, locales: ['fa', 'en'], defaultLocale: 'fa', currency: 'IRR', mode: 'SINGLE_VENDOR',
  capabilities: {physicalProducts: true, digitalProducts: false, sellerOnboarding: false, commissions: false},
  theme: {accent: '#112233', accentText: '#ffffff', surface: '#ffffff', surfaceAlt: '#eeeeee', text: '#000000', muted: '#555555', border: '#cccccc', radius: 8}, domain: 'demo.example', portBase: 31000};
const taxonomy = {attributes: [{key: 'size', type: 'ENUM', labels: {en: 'Size'}, options: [{code: 's', labels: {en: 'S'}}]}, {key: 'note', type: 'STRING', labels: {en: 'Note'}}],
  productTypes: [{key: 'thing', name: {en: 'Thing'}, kind: 'PHYSICAL', attributes: [{key: 'note', required: true}, {key: 'size', required: true, variantDefining: true}]}],
  categories: [{ref: 'a', slug: 'a', name: {en: 'A'}}, {ref: 'b', slug: 'b', name: {en: 'B'}, parent: 'a'}]};
const products = {products: [{slug: 'one', productType: 'thing', category: 'b', title: {en: 'One'}, attributes: {note: 'x'}, variants: [{sku: 'S1', attributes: {size: 's'}}], offer: {amount: 10, quantity: 3}}]};

test('a valid store and catalog pass', () => {
  assert.deepEqual(validateStore(store), []);
  assert.deepEqual(validateCatalog(store, {...taxonomy, ...products}), []);
});

test('capabilities must agree with the commerce mode', () => {
  assert.ok(validateStore({...store, mode: 'MULTI_VENDOR'}).some(p => p.includes('sellerOnboarding')));
  assert.ok(validateStore({...store, capabilities: {...store.capabilities, commissions: true}}).some(p => p.includes('single-vendor')));
  assert.ok(validateStore({...store, capabilities: {...store.capabilities, physicalProducts: false}}).some(p => p.includes('physical or digital')));
  assert.ok(validateStore({...store, defaultLocale: 'de'}).some(p => p.includes('defaultLocale')));
  assert.ok(validateStore({...store, theme: {...store.theme, accent: 'red'}}).some(p => p.includes('theme.accent')));
});

test('catalog review finds broken references before anything reaches an engine', () => {
  const problems = validateCatalog(store, {
    attributes: [{key: 'x', type: 'ENUM', labels: {en: 'X'}}, {key: 'm', type: 'MEASUREMENT', labels: {en: 'M'}}, {key: 't', type: 'STRING', labels: {en: 'T'}}],
    productTypes: [{key: 'd', name: {en: 'D'}, kind: 'DIGITAL', attributes: [{key: 'ghost'}, {key: 't', variantDefining: true}]}],
    categories: [{ref: 'c', slug: 'c', name: {en: 'C'}, parent: 'later'}],
    products: [{slug: 'p', productType: 'nope', title: {en: 'P'}}]});
  for (const expected of ['x: ENUM needs options', 'm: MEASUREMENT needs units', 'd: the store has no digital', 'unknown attribute ghost', 't cannot define variants', 'parent later', 'unknown product type nope'])
    assert.ok(problems.some(p => p.includes(expected)), expected);
});

test('the engine environment follows from the configuration', () => {
  const env = engineEnvironment(store);
  assert.equal(env.COMMERCE_STORE_MODE, 'SINGLE_VENDOR');
  assert.equal(env.COMMERCE_CAP_DIGITAL, 'false');
  assert.equal(env.COMMERCE_STORE_LOCALES, 'fa,en');
});

test('seeding goes through the API in dependency order and is safe to repeat', async () => {
  const calls = [];
  let firstRun = true;
  const send = async (method, path, body) => {
    calls.push(`${method} ${path}`);
    if (path === '/v1/store/categories') return {status: 200, body: []};
    if (path === '/v1/admin/categories') return {status: 201, body: {id: `cat-${body.slug}`}};
    if (path === '/v1/admin/products') return firstRun ? {status: 201, body: {id: 'p1', variants: [{id: 'v1', sku: 'S1'}]}} : {status: 409, body: {}};
    if (method === 'POST' && (path === '/v1/admin/attributes' || path === '/v1/admin/product-types') && !firstRun) return {status: 409, body: {}};
    return {status: path.endsWith('/publish') ? 200 : 201, body: {}};
  };
  await seedCatalog(send, store, {...taxonomy, ...products});
  const order = ['POST /v1/admin/attributes', 'POST /v1/admin/product-types', 'POST /v1/admin/categories', 'POST /v1/admin/products', 'POST /v1/admin/offers', 'POST /v1/admin/products/p1/publish'].map(c => calls.indexOf(c));
  assert.deepEqual(order, [...order].sort((a, b) => a - b), calls.join('\n'));
  firstRun = false;
  await seedCatalog(send, store, {...taxonomy, ...products});
});

test('a failure stops the seed and names the step', async () => {
  await assert.rejects(seedCatalog(async () => ({status: 403, body: {code: 'ACCESS_DENIED'}}), store, taxonomy), /attribute size: HTTP 403/);
});

test('new-store generates a repository holding no engine source and refuses to overwrite', () => {
  const out = mkdtempSync(join(tmpdir(), 'store-'));
  try {
    newStore(store, out, {engineVersion: '9.9.9', taxonomy, products});
    for (const file of ['engine.lock.json', 'storefront/package.json', 'storefront/config/store.json', 'storefront/config/taxonomy.json', 'seed/products.json', 'tools/fetch-engine.mjs', 'deploy/compose.yml', 'tests/e2e/acceptance.test.mjs'])
      assert.ok(existsSync(join(out, file)), file);
    assert.equal(JSON.parse(readFileSync(join(out, 'engine.lock.json'), 'utf8')).version, '9.9.9');
    const walk = dir => readdirSync(dir).flatMap(f => statSync(join(dir, f)).isDirectory() ? walk(join(dir, f)) : [join(dir, f)]);
    assert.equal(walk(out).filter(f => f.endsWith('.java')).length, 0, 'no Java source is copied');
    for (const file of walk(out)) assert.ok(!/\{\{(storeKey|engineVersion|displayName|domain)\}\}/.test(readFileSync(file, 'utf8')), `unrendered placeholder in ${file}`);
    assert.throws(() => newStore(store, out, {engineVersion: '9.9.9'}), /never overwrites/);
  } finally { rmSync(out, {recursive: true, force: true}); }
});

test('a theme people cannot read is refused', () => {
  assert.equal(Math.round(contrast('#000000', '#ffffff')), 21);
  assert.ok(validateStore({...store, theme: {...store.theme, text: '#eeeeee'}}).some(p => p.includes('text on surface')));
  assert.ok(validateStore({...store, theme: {...store.theme, accentText: '#223344'}}).some(p => p.includes('accentText on accent')));
});

test('a schema change names what it means for existing products', () => {
  const before = [{key: 'a', required: false}, {key: 'b', required: true}];
  assert.equal(schemaDiff(before, [...before, {key: 'c', required: false}]).needsMigration, false);
  const required = schemaDiff(before, [{key: 'a', required: true}, {key: 'b', required: true}, {key: 'c', required: true}]);
  assert.deepEqual(required.requiresValues, ['c', 'a']);
  assert.equal(required.needsMigration, true);
  assert.deepEqual(schemaDiff(before, [before[1]]).removed, ['a']);
});

test('capabilities and themes are edited through validation; a bad edit leaves the file alone', () => {
  const dir = mkdtempSync(join(tmpdir(), 'set-'));
  const file = join(dir, 'store.json');
  const cli = join(dirname(fileURLToPath(import.meta.url)), '..', 'cli.mjs');
  try {
    writeFileSync(file, JSON.stringify(store));
    execFileSync(process.execPath, [cli, 'set', '--store', file, 'capabilities.reviews=true', 'theme.accent=#000000'], {stdio: 'pipe'});
    assert.equal(JSON.parse(readFileSync(file, 'utf8')).capabilities.reviews, true);
    assert.throws(() => execFileSync(process.execPath, [cli, 'set', '--store', file, 'mode=MULTI_VENDOR'], {stdio: 'pipe'}));
    assert.equal(JSON.parse(readFileSync(file, 'utf8')).mode, 'SINGLE_VENDOR');
    assert.throws(() => execFileSync(process.execPath, [cli, 'set', '--store', file, 'key=other'], {stdio: 'pipe'}));
  } finally { rmSync(dir, {recursive: true, force: true}); }
});

test('a third store comes from configuration alone: the handicraft fixture passes the same review', () => {
  const fixture = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'fixtures/handicraft/catalog.json'), 'utf8'));
  const handicraft = {...store, key: 'handicraft', locales: ['fa', 'en']};
  assert.deepEqual(validateCatalog(handicraft, fixture), []);
  const out = mkdtempSync(join(tmpdir(), 'store-'));
  try {
    newStore(handicraft, out, {engineVersion: '9.9.9', taxonomy: {attributes: fixture.attributes, productTypes: fixture.productTypes, categories: fixture.categories}, products: {products: fixture.products}});
    assert.equal(JSON.parse(readFileSync(join(out, 'storefront/config/store.json'), 'utf8')).key, 'handicraft');
  } finally { rmSync(out, {recursive: true, force: true}); }
});

test('the core stays free of industry vocabulary', () => {
  const root = dirname(fileURLToPath(import.meta.url));
  const walk = dir => readdirSync(dir).flatMap(f => statSync(join(dir, f)).isDirectory() ? (f === 'node_modules' ? [] : walk(join(dir, f))) : [join(dir, f)]);
  for (const file of [join(root, '..', 'index.mjs'), ...walk(TEMPLATE_DIR)]) assert.ok(!/gemstone|carat|prompt|gemolog/i.test(readFileSync(file, 'utf8')), file);
});
