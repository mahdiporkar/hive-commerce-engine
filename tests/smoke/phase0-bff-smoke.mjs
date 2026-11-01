// Phase 0 smoke: register the draft commerce vocabulary against a throwaway local Hive and exercise the BFF path.
// Usage: node smoke.mjs <resource-manifest.json>   (HIVE_PROVISIONING_PASSWORD in env; never printed)
import {readFileSync} from 'node:fs';

const AZ = 'http://127.0.0.1:18182', BFF = 'http://127.0.0.1:18181';
const auth = 'Basic ' + Buffer.from(`provisioner:${process.env.HIVE_PROVISIONING_PASSWORD}`).toString('base64');
const results = [];
const check = (name, ok, detail = '') => { results.push({name, ok}); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`); };

async function admin(method, path, body, allow = []) {
  const r = await fetch(AZ + path, {method, headers: {Authorization: auth, 'Content-Type': 'application/json'}, ...(body === undefined ? {} : {body: JSON.stringify(body)})});
  const text = await r.text(); const value = text ? JSON.parse(text) : null;
  if (r.status >= 300 && !allow.includes(r.status)) throw new Error(`${method} ${path}: ${r.status} ${value?.code ?? ''} ${value?.message ?? ''}`);
  return {status: r.status, value};
}

const manifest = JSON.parse(readFileSync(process.argv[2], 'utf8'));
await admin('POST', '/admin/applications', {key: 'gemstone', displayName: 'Gemstone (Phase 0 smoke)'}, [409]);
await admin('POST', '/admin/modules', {applicationKey: 'gemstone', moduleKey: 'commerce-api', displayName: 'Commerce API', definitionMode: 'MANIFEST'}, [409]);
await admin('POST', '/admin/modules/commerce-api/resource-manifests', manifest);
const rev = (await admin('GET', `/admin/modules/commerce-api/resource-manifests/${manifest.manifestVersion}`)).value;
if (rev.status === 'DRAFT') await admin('POST', `/admin/modules/commerce-api/resource-manifests/${manifest.manifestVersion}/publish`);
const resources = (await admin('GET', '/admin/applications/gemstone/resources')).value;
const list = Array.isArray(resources) ? resources : resources.resources ?? resources.items ?? [];
check('resource-only module commerce-api published (no micro-frontend manifest)', list.some(r => (r.key ?? r.resourceKey) === 'commerce.api.catalog'), `${list.length} resources in gemstone tree`);

await admin('POST', '/admin/service-targets', {key: 'commerce-engine', displayName: 'Commerce engine (echo stub)', baseUrl: 'http://echo:8099'}, [409]);
await admin('POST', '/admin/proxy-routes', {key: 'commerce', applicationKey: 'gemstone', pathPrefix: '/commerce', targetKey: 'commerce-engine', authentication: 'FORWARD_TOKEN'}, [409]);
for (const op of [
  {key: 'store-product', method: 'GET', pathPattern: '/v1/store/products/{slug}', access: 'PUBLIC'},
  {key: 'shop-cart-create', method: 'POST', pathPattern: '/v1/shop/carts', access: 'HYBRID'},
  {key: 'admin-product-create', method: 'POST', pathPattern: '/v1/admin/products', access: 'AUTHENTICATED', resourceKey: 'commerce.api.catalog', action: 'create'},
]) await admin('POST', '/admin/proxy-routes/commerce/operations', op, [409]);
const bad = await admin('POST', '/admin/proxy-routes/commerce/operations', {key: 'bad-public', method: 'GET', pathPattern: '/v1/store/x', access: 'PUBLIC', resourceKey: 'commerce.api.catalog', action: 'create'}, [400, 409, 422]);
check('PUBLIC operation with a permission is rejected', bad.status >= 400, `HTTP ${bad.status}`);

// 1. PUBLIC GET with forged browser headers
let r = await fetch(`${BFF}/api/routes/commerce/v1/store/products/ruby-1?locale=fa`, {headers: {Authorization: 'Bearer forged', Cookie: 'x=1', 'X-Hive-User-Id': 'forged-user', 'X-Forged': '1'}});
let body = await r.json().catch(() => null);
check('PUBLIC GET reaches upstream with strip-prefix path and query', r.status === 200 && body?.url === '/v1/store/products/ruby-1?locale=fa', `HTTP ${r.status} upstream url ${body?.url}`);
const h = body?.headers ?? {};
check('forged Authorization / Cookie / X-Hive-User-Id / custom header not forwarded', !h.authorization && !h.cookie && !h['x-hive-user-id'] && !h['x-forged'], JSON.stringify(h));
check('X-Correlation-Id, X-Hive-Route, X-Hive-Operation forwarded', !!h['x-correlation-id'] && h['x-hive-route'] === 'commerce' && h['x-hive-operation'] === 'store-product', `${h['x-hive-route']}/${h['x-hive-operation']}`);

// 2. AUTHENTICATED anonymous -> 401 before upstream
r = await fetch(`${BFF}/api/routes/commerce/v1/admin/products`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}'});
const csrfLess = r.status;
// get CSRF + session cookie
const c = await fetch(`${BFF}/auth/csrf`); const csrf = await c.json(); const cookie = (c.headers.getSetCookie?.() ?? []).map(s => s.split(';')[0]).join('; ');
const headerName = csrf.headerName ?? csrf.header ?? 'X-XSRF-TOKEN';
r = await fetch(`${BFF}/api/routes/commerce/v1/admin/products`, {method: 'POST', headers: {'Content-Type': 'application/json', Cookie: cookie, [headerName]: csrf.token}, body: '{}'});
body = await r.json().catch(() => null);
check('AUTHENTICATED operation, anonymous with CSRF -> 401, upstream not reached', r.status === 401 && !body?.url, `HTTP ${r.status} ${body?.code ?? ''}`);
check('unsafe method without CSRF header is refused', csrfLess === 403 || csrfLess === 401, `HTTP ${csrfLess}`);

// 3. HYBRID anonymous POST with CSRF -> upstream, no identity
r = await fetch(`${BFF}/api/routes/commerce/v1/shop/carts`, {method: 'POST', headers: {'Content-Type': 'application/json', Cookie: cookie, [headerName]: csrf.token}, body: '{}'});
body = await r.json().catch(() => null);
check('HYBRID anonymous POST reaches upstream without identity or token', r.status === 200 && body?.method === 'POST' && !body.headers['x-hive-user-id'] && !body.headers.authorization, `HTTP ${r.status}`);
r = await fetch(`${BFF}/api/routes/commerce/v1/shop/carts`, {method: 'POST', headers: {'Content-Type': 'application/json', Cookie: cookie}, body: '{}'});
check('HYBRID anonymous POST without CSRF header is refused', r.status === 403, `HTTP ${r.status}`);

// 4. Unregistered path
r = await fetch(`${BFF}/api/routes/commerce/v1/shop/unknown`);
check('unregistered operation -> 404', r.status === 404, `HTTP ${r.status}`);

const failed = results.filter(x => !x.ok).length;
console.log(`\n${results.length - failed}/${results.length} PASS`);
process.exit(failed ? 1 : 0);
