// Commerce Builder library. Holds store-agnostic checks only: no industry vocabulary may ever appear here (ADR-0011).
import {existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync} from 'node:fs';
import {dirname, join, relative} from 'node:path';
import {fileURLToPath} from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
export const ATTRIBUTE_TYPES = ['STRING', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME', 'ENUM', 'MULTI_SELECT', 'COLOR', 'MEASUREMENT', 'RANGE', 'URL', 'REFERENCE', 'JSON'];
const WITH_OPTIONS = ['ENUM', 'MULTI_SELECT'];
const MODES = ['SINGLE_VENDOR', 'MULTI_VENDOR', 'HYBRID'];
const KEY = /^[a-z][a-z0-9-]{1,30}$/;
const ATTRIBUTE_KEY = /^[a-z][a-z0-9_]{0,62}$/;
const COLOR = /^#[0-9a-fA-F]{6}$/;

/** WCAG 2 contrast ratio of two #rrggbb colors. */
export function contrast(a, b) {
  const luminance = hex => {
    const [r, g, b] = [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16) / 255).map(c => c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (light + 0.05) / (dark + 0.05);
}

/**
 * What a change between two schema versions means for existing products: an added required attribute or a removed one
 * calls for explicit migration; anything else is compatible.
 */
export function schemaDiff(previous, next) {
  const index = specs => new Map((specs ?? []).map(s => [s.key, s]));
  const before = index(previous), after = index(next);
  const added = [...after.keys()].filter(k => !before.has(k));
  const removed = [...before.keys()].filter(k => !after.has(k));
  const nowRequired = [...after.keys()].filter(k => before.has(k) && after.get(k).required && !before.get(k).required);
  const variantsChanged = [...after.keys()].filter(k => before.has(k) && Boolean(after.get(k).variantDefining) !== Boolean(before.get(k).variantDefining));
  const requiresValues = [...added.filter(k => after.get(k).required), ...nowRequired];
  return {added, removed, nowRequired, variantsChanged, requiresValues, needsMigration: requiresValues.length > 0 || removed.length > 0 || variantsChanged.length > 0};
}

/** @return list of problems; empty when the store configuration is usable */
export function validateStore(store) {
  const problems = [];
  const need = (ok, message) => { if (!ok) problems.push(message); };
  need(KEY.test(store.key ?? ''), 'key: lowercase letters, digits and dashes, 2 to 31 characters');
  need(Array.isArray(store.locales) && store.locales.length > 0, 'locales: at least one locale');
  need(store.locales?.includes(store.defaultLocale), 'defaultLocale: must be one of locales');
  for (const locale of store.locales ?? []) need(store.displayName?.[locale], `displayName: missing ${locale}`);
  need(/^[A-Z]{3}$/.test(store.currency ?? ''), 'currency: ISO 4217 code');
  need(MODES.includes(store.mode), `mode: one of ${MODES.join(', ')}`);
  const capabilities = store.capabilities ?? {};
  for (const name of ['physicalProducts', 'digitalProducts', 'sellerOnboarding', 'commissions']) need(typeof capabilities[name] === 'boolean', `capabilities.${name}: true or false`);
  if (capabilities.reviews !== undefined) need(typeof capabilities.reviews === 'boolean', 'capabilities.reviews: true or false');
  need(capabilities.physicalProducts || capabilities.digitalProducts, 'capabilities: a store sells physical or digital products');
  if (store.mode !== 'SINGLE_VENDOR') need(capabilities.sellerOnboarding, `capabilities.sellerOnboarding: required by ${store.mode}`);
  if (store.mode === 'SINGLE_VENDOR') need(!capabilities.sellerOnboarding && !capabilities.commissions, 'capabilities: a single-vendor store has no seller onboarding or commissions');
  for (const name of ['accent', 'accentText', 'surface', 'surfaceAlt', 'text', 'muted', 'border']) need(COLOR.test(store.theme?.[name] ?? ''), `theme.${name}: #rrggbb color`);
  if (problems.every(p => !p.startsWith('theme.'))) {
    for (const [foreground, background, minimum] of [['text', 'surface', 4.5], ['accentText', 'accent', 4.5], ['text', 'surfaceAlt', 4.5], ['muted', 'surfaceAlt', 3]]) {
      const ratio = contrast(store.theme[foreground], store.theme[background]);
      need(ratio >= minimum, `theme: ${foreground} on ${background} has contrast ${ratio.toFixed(2)}, needs at least ${minimum}`);
    }
  }
  need(Number.isInteger(store.theme?.radius) && store.theme.radius >= 0 && store.theme.radius <= 32, 'theme.radius: 0 to 32');
  need(typeof store.domain === 'string' && /^[a-z0-9.-]+$/.test(store.domain), 'domain: host name');
  need(Number.isInteger(store.portBase) && store.portBase >= 20000 && store.portBase <= 60000, 'portBase: 20000 to 60000');
  return problems;
}

/** Offline schema review, before anything reaches an engine. The engine stays the authority at publish time. */
export function validateCatalog(store, {attributes = [], productTypes = [], categories = [], products = []}) {
  const problems = [];
  const need = (ok, message) => { if (!ok) problems.push(message); };
  const byKey = new Map();
  for (const a of attributes) {
    need(ATTRIBUTE_KEY.test(a.key ?? ''), `attribute ${a.key}: invalid key`);
    need(!byKey.has(a.key), `attribute ${a.key}: duplicate`);
    byKey.set(a.key, a);
    need(ATTRIBUTE_TYPES.includes(a.type), `attribute ${a.key}: unknown type ${a.type}`);
    if (WITH_OPTIONS.includes(a.type)) need(a.options?.length > 0, `attribute ${a.key}: ${a.type} needs options`);
    if (a.type === 'MEASUREMENT') need(a.constraints?.units?.length > 0, `attribute ${a.key}: MEASUREMENT needs units`);
    for (const locale of store.locales) if (!a.labels?.[locale]) need(a.labels?.en, `attribute ${a.key}: no label for ${locale} and no English fallback`);
  }
  const types = new Map();
  for (const t of productTypes) {
    need(!types.has(t.key), `product type ${t.key}: duplicate`);
    types.set(t.key, t);
    need(['PHYSICAL', 'DIGITAL'].includes(t.kind), `product type ${t.key}: kind must be PHYSICAL or DIGITAL`);
    if (t.kind === 'PHYSICAL') need(store.capabilities.physicalProducts, `product type ${t.key}: the store has no physical products`);
    if (t.kind === 'DIGITAL') need(store.capabilities.digitalProducts, `product type ${t.key}: the store has no digital products`);
    for (const spec of t.attributes ?? []) {
      const attribute = byKey.get(spec.key);
      need(attribute, `product type ${t.key}: unknown attribute ${spec.key}`);
      if (spec.variantDefining) need(attribute && ['ENUM', 'COLOR'].includes(attribute.type), `product type ${t.key}: ${spec.key} cannot define variants`);
    }
  }
  const refs = new Set();
  for (const c of categories) {
    need(c.ref && !refs.has(c.ref), `category ${c.ref}: missing or duplicate ref`);
    if (c.parent) need(refs.has(c.parent), `category ${c.ref}: parent ${c.parent} must come first`);
    refs.add(c.ref);
    for (const key of c.attributes ?? []) need(byKey.has(key), `category ${c.ref}: unknown attribute ${key}`);
  }
  const slugs = new Set();
  for (const p of products) {
    need(p.slug && !slugs.has(p.slug), `product ${p.slug}: missing or duplicate slug`);
    slugs.add(p.slug);
    const type = types.get(p.productType);
    need(type, `product ${p.slug}: unknown product type ${p.productType}`);
    need(!p.category || refs.has(p.category), `product ${p.slug}: unknown category ${p.category}`);
    if (!type) continue;
    for (const spec of (type.attributes ?? []).filter(s => s.required && !s.variantDefining)) need(p.attributes?.[spec.key] !== undefined, `product ${p.slug}: required attribute ${spec.key} missing`);
    need(p.variants?.length > 0, `product ${p.slug}: needs at least one variant`);
    for (const v of p.variants ?? []) for (const spec of (type.attributes ?? []).filter(s => s.required && s.variantDefining)) need(v.attributes?.[spec.key] !== undefined, `product ${p.slug}: variant ${v.sku} needs ${spec.key}`);
    if (type.kind === 'DIGITAL' && p.offer) need(p.asset, `product ${p.slug}: a digital product on sale needs its asset`);
  }
  return problems;
}

/** The engine environment that follows from the store configuration, one place only. */
export function engineEnvironment(store) {
  return {
    COMMERCE_STORE_KEY: store.key, COMMERCE_STORE_MODE: store.mode, COMMERCE_STORE_CURRENCY: store.currency, COMMERCE_STORE_LOCALES: store.locales.join(','),
    COMMERCE_CAP_PHYSICAL: String(store.capabilities.physicalProducts), COMMERCE_CAP_DIGITAL: String(store.capabilities.digitalProducts),
    COMMERCE_CAP_SELLER_ONBOARDING: String(store.capabilities.sellerOnboarding), COMMERCE_CAP_COMMISSIONS: String(store.capabilities.commissions), COMMERCE_CAP_REVIEWS: String(store.capabilities.reviews ?? false),
  };
}

/**
 * Loads a catalog through the public engine API, the same path an operator takes, so every authorization and validation
 * rule applies. Safe to run twice: what already exists is kept.
 * @param send async (method, path, body) => {status, body}, an authenticated call through Hive's route
 */
export async function seedCatalog(send, store, {attributes = [], productTypes = [], categories = [], products = []}, log = () => {}) {
  const expect = async (name, method, path, body, ok = [200, 201]) => {
    const r = await send(method, path, body);
    if (!ok.includes(r.status)) throw new Error(`${name}: HTTP ${r.status} ${JSON.stringify(r.body)}`);
    log(`${r.status === 409 ? 'kept   ' : 'applied'}  ${name}`);
    return r;
  };
  for (const a of attributes) await expect(`attribute ${a.key}`, 'POST', '/v1/admin/attributes', a, [201, 409]);
  for (const t of productTypes) {
    await expect(`product type ${t.key}`, 'POST', '/v1/admin/product-types', {key: t.key, name: t.name, kind: t.kind, attributes: t.attributes}, [201, 409]);
    await expect(`publish ${t.key}`, 'POST', `/v1/admin/product-types/${t.key}/versions/1/publish`, undefined, [200, 409]);
  }
  const existing = (await expect('read categories', 'GET', '/v1/store/categories')).body;
  const ids = new Map(existing.map(c => [c.slug, c.id]));
  const refs = new Map();
  for (const c of categories) {
    if (!ids.has(c.slug)) {
      const created = await expect(`category ${c.slug}`, 'POST', '/v1/admin/categories', {slug: c.slug, name: c.name, attributes: c.attributes, parentId: c.parent ? refs.get(c.parent) : null}, [201]);
      ids.set(c.slug, created.body.id);
    }
    refs.set(c.ref, ids.get(c.slug));
  }
  const created = new Map();
  for (const p of products) {
    const r = await expect(`product ${p.slug}`, 'POST', '/v1/admin/products', {productType: p.productType, categoryId: p.category ? refs.get(p.category) : null, title: p.title, slug: p.slug,
      attributes: p.attributes ?? {}, variants: p.variants}, [201, 409]);
    if (r.status === 409) continue;
    created.set(p.slug, r.body.id);
    if (p.asset) {
      const asset = (await expect(`asset ${p.slug}`, 'POST', '/v1/admin/assets', {productId: r.body.id, kind: p.asset.kind, title: p.asset.title, downloadLimit: p.asset.downloadLimit ?? null,
        updatesIncluded: p.asset.updatesIncluded ?? true}, [201])).body;
      if (p.asset.text) await expect(`asset text ${p.slug}`, 'PUT', `/v1/admin/assets/${asset.assetId}/text`, {content: p.asset.text});
      if (p.asset.preview) await expect(`asset preview ${p.slug}`, 'PUT', `/v1/admin/assets/${asset.assetId}/preview`, {preview: p.asset.preview});
    }
    if (p.offer) {
      for (const variant of r.body.variants) {
        await expect(`offer ${variant.sku}`, 'POST', '/v1/admin/offers', {variantId: variant.id, amount: p.offer.amount, currency: p.offer.currency ?? store.currency, quantity: p.offer.quantity ?? null,
          uniqueItem: p.offer.uniqueItem ?? false}, [201]);
      }
    }
    await expect(`publish ${p.slug}`, 'POST', `/v1/admin/products/${r.body.id}/publish`, undefined, [200]);
  }
  return {categories: refs, products: created};
}

function renderInto(from, to, values) {
  mkdirSync(to, {recursive: true});
  for (const name of readdirSync(from)) {
    const source = join(from, name), target = join(to, name === 'gitignore' ? '.gitignore' : name);
    if (statSync(source).isDirectory()) { renderInto(source, target, values); continue; }
    let text = readFileSync(source, 'utf8');
    for (const [key, value] of Object.entries(values)) text = text.replaceAll(`{{${key}}}`, value);
    writeFileSync(target, text);
  }
}

/** Generates a store repository: thin storefront, deployment, tests. It consumes the engine by version and copies no engine source. */
export function newStore(store, out, {engineVersion, taxonomy, products, engineRepository = 'mahdiporkar/hive-commerce-engine'} = {}) {
  const problems = validateStore(store);
  if (problems.length) throw new Error(`Invalid store configuration:\n  ${problems.join('\n  ')}`);
  const catalog = {...taxonomy, ...products};
  if (taxonomy) {
    const review = validateCatalog(store, catalog);
    if (review.length) throw new Error(`Invalid catalog:\n  ${review.join('\n  ')}`);
  }
  if (existsSync(join(out, 'storefront'))) throw new Error(`${out} already holds a storefront; the builder never overwrites a store`);
  const env = Object.entries(engineEnvironment(store)).map(([k, v]) => `${k}=${v}`).join('\n');
  renderInto(join(HERE, 'template'), out, {storeKey: store.key, displayName: store.displayName[store.defaultLocale], engineVersion, engineRepository, domain: store.domain, defaultLocale: store.defaultLocale, storeEnvironment: env});
  writeFileSync(join(out, 'storefront/config/store.json'), JSON.stringify(store, null, 2) + '\n');
  writeFileSync(join(out, 'storefront/config/taxonomy.json'), JSON.stringify(taxonomy ?? {attributes: [], productTypes: [], categories: []}, null, 2) + '\n');
  writeFileSync(join(out, 'seed/products.json'), JSON.stringify(products ?? {products: []}, null, 2) + '\n');
  return readdirSync(out).map(f => relative(out, join(out, f)));
}

export const TEMPLATE_DIR = join(HERE, 'template');
