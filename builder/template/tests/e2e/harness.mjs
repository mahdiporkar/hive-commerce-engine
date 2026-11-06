// Starts this store's whole stack from the pinned engine release: its own Hive, Keycloak, database and bucket, the engine
// jar with this store's extensions, and the built storefront. Nothing here is shared with any other store.
import {execFileSync, spawn} from 'node:child_process';
import {createHmac} from 'node:crypto';
import {closeSync, existsSync, mkdirSync, openSync, readFileSync, writeFileSync} from 'node:fs';
import {createServer} from 'node:http';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {assembleAdminBundles} from '../../tools/assemble-admin-bundles.mjs';
import {registerStore} from '../../tools/register.mjs';
import {seedStore} from '../../tools/seed.mjs';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = path => JSON.parse(readFileSync(join(ROOT, path), 'utf8'));
export const store = read('storefront/config/store.json');
export const version = process.env.ENGINE_VERSION ?? read('engine.lock.json').version;
const engineDir = join(ROOT, '.engine', version);
const SHORT = readFileSync(join(engineDir, 'hive/HIVE_REVISION'), 'utf8').trim().slice(0, 7);
const hiveDir = process.env.HIVE_SOURCE_DIR ?? resolve(ROOT, '..', 'hive-commerce-engine/.local/hive-src', SHORT);
const kitModule = await import(pathToFileURL(join(engineDir, 'kit/package/index.mjs')).href);
export const {cookieClient, ready, serveDirectory, startGateway} = kitModule;

export const STOREFRONT_PORT = store.portBase + 25;
export const PSP_PORT = store.portBase + 26;
const NEXT_PORT = store.portBase + 27;
export const ORIGIN = `http://127.0.0.1:${STOREFRONT_PORT}`;
export const USERS = {operator: {username: 'store.operator', password: 'test-only-password-operator'}, buyer: {username: 'first.buyer', password: 'test-only-password-buyer'},
  other: {username: 'other.buyer', password: 'test-only-password-other'}, seller: {username: 'first.seller', password: 'test-only-password-seller'}};

export const ENGINE_JAR = join(engineDir, 'commerce-engine.jar');
const extensions = join(ROOT, 'deploy/extensions');
export const kit = kitModule.createKit({hiveDir, hiveVersion: SHORT, engineJar: join(engineDir, 'commerce-engine.jar'), portBase: store.portBase, workDir: join(ROOT, '.local/e2e'),
  storeKey: store.key, application: store.key, users: Object.values(USERS), origins: [ORIGIN], extensions, storage: store.capabilities.digitalProducts,
  engineEnv: {COMMERCE_STORE_MODE: store.mode, COMMERCE_STORE_CURRENCY: store.currency, COMMERCE_STORE_LOCALES: store.locales.join(','), COMMERCE_CAP_PHYSICAL: String(store.capabilities.physicalProducts),
    COMMERCE_CAP_DIGITAL: String(store.capabilities.digitalProducts), COMMERCE_CAP_SELLER_ONBOARDING: String(store.capabilities.sellerOnboarding), COMMERCE_CAP_COMMISSIONS: String(store.capabilities.commissions), COMMERCE_CAP_REVIEWS: String(store.capabilities.reviews ?? false),
    COMMERCE_PAYMENT_RETURN_URL: `${ORIGIN}/${store.defaultLocale}/checkout/return`, COMMERCE_FAKE_PSP_URL: `http://127.0.0.1:${PSP_PORT}`, COMMERCE_TAX_RATE_PERCENT: '9', COMMERCE_FLAT_SHIPPING: store.capabilities.physicalProducts ? '500000' : '0'}});

/** Stands in for the provider's hosted page: the shopper approves or declines, then returns to the store. Test only. */
function startFakePspPage() {
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://x');
    const ref = /^\/pay\/(.+)$/.exec(url.pathname)?.[1];
    if (!ref) { response.writeHead(404).end(); return; }
    if (request.method === 'GET') {
      response.writeHead(200, {'Content-Type': 'text/html'}).end(`<!doctype html><title>Test payment page</title><form method="post"><button name="decision" value="approve" data-testid="psp-approve">Approve</button><button name="decision" value="decline" data-testid="psp-decline">Decline</button></form>`);
      return;
    }
    let body = '';
    for await (const chunk of request) body += chunk;
    const decision = new URLSearchParams(body).get('decision');
    const answer = await fetch(`${kit.ENGINE}/hooks/fake-psp/sessions/${ref}/${decision}`, {method: 'POST'});
    const {returnUrl} = await answer.json();
    response.writeHead(303, {Location: returnUrl}).end();
  });
  return new Promise(r => server.listen(PSP_PORT, '127.0.0.1', () => r(server)));
}

/** Builds the storefront this test run serves. */
export function buildStorefront() {
  execFileSync(process.execPath, [join(ROOT, 'storefront/node_modules/next/dist/bin/next'), 'build'], {cwd: join(ROOT, 'storefront'), stdio: ['ignore', 'pipe', 'inherit'],
    env: {...process.env, STOREFRONT_STANDALONE: 'false', NEXT_TELEMETRY_DISABLED: '1'}, timeout: 400000});
}

export async function startStore(t) {
  buildStorefront();
  const environment = await kit.startEnvironment(t);
  const modulesHost = await serveDirectory(assembleAdminBundles(), kit.PORTS.modules);
  const psp = await startFakePspPage();
  t.after(() => { modulesHost.close(); psp.close(); });
  await registerStore({authorization: `http://127.0.0.1:${kit.PORTS.authorization}`, password: environment.provisioningPassword, engineUrl: kit.ENGINE, modulesUrl: kit.MODULES_URL});

  mkdirSync(join(ROOT, '.local/e2e/logs'), {recursive: true});
  const fd = openSync(join(ROOT, '.local/e2e/logs/storefront.log'), 'w');
  const next = spawn(process.execPath, [join(ROOT, 'storefront/node_modules/next/dist/bin/next'), 'start', '-p', String(NEXT_PORT), '-H', '127.0.0.1'], {cwd: join(ROOT, 'storefront'), windowsHide: true, stdio: ['ignore', fd, fd],
    env: {...process.env, HIVE_BFF_URL: kit.BFF, STORE_ORIGIN: ORIGIN, STOREFRONT_STANDALONE: 'false', NEXT_TELEMETRY_DISABLED: '1'}});
  t.after(async () => { next.kill(); await new Promise(r => { next.once('exit', r); setTimeout(r, 4000).unref(); }); closeSync(fd); });
  const gateway = await startGateway({port: STOREFRONT_PORT, bff: kit.BFF, storefront: `http://127.0.0.1:${NEXT_PORT}`});
  t.after(() => gateway.close());
  await ready(`${ORIGIN}/robots.txt`);

  const operator = await kit.signIn(USERS.operator.username, USERS.operator.password);
  const grant = async (role, session) => {
    const r = await environment.provision('POST', `/admin/roles/${role}/assignments`, {subject: `user:${session.session.identity.id}`});
    if (![200, 201].includes(r.status)) throw new Error(`assign ${role}: ${r.status} ${JSON.stringify(r.body)}`);
  };
  for (const role of ['commerce-catalog-manager', 'commerce-order-manager', 'commerce-seller-manager', 'commerce-finance-manager', 'commerce-digital-manager', 'commerce-store-admin']) await grant(role, operator);
  const send = api(operator.bff);
  // A grant reaches OpenFGA asynchronously through Hive's outbox.
  for (let n = 0; ; n++) {
    const probes = await Promise.all(['/v1/admin/attributes', '/v1/admin/orders', '/v1/admin/sellers?status=APPLIED', '/v1/admin/finance/settlements', '/v1/admin/store'].map(p => send('GET', p)));
    if (probes.every(p => p.status === 200)) break;
    if (n > 120) throw new Error(`Operator grants never became effective: ${probes.map(p => p.status)}`);
    await new Promise(r => setTimeout(r, 500));
  }
  return {...environment, operator, send, grant};
}

export const api = client => async (method, path, body) => {
  const headers = {'Content-Type': 'application/json', ...(method === 'GET' ? {} : await client.csrf())};
  const response = await client(`/api/routes/commerce${path}`, {method, headers, ...(body === undefined ? {} : {body: JSON.stringify(body)})});
  const text = await response.text();
  return {status: response.status, body: text ? JSON.parse(text) : null};
};

export {seedStore};

/** Real browser: installed Edge on Windows, Chromium elsewhere. */
export async function launchBrowser() {
  const {chromium} = await import('playwright');
  return chromium.launch({headless: true, ...(process.platform === 'win32' ? {channel: 'msedge'} : {})});
}

/** Mounts a shared admin bundle in the signed-in page exactly as Hive's runtime does: fetched through the gateway, then evaluated. */
export async function mountAdminModule(page, area, locale = 'en') {
  await page.evaluate(async ({area, locale, version}) => {
    const response = await fetch(`/api/mfe/commerce-admin-${area}/${version}/entry.js`);
    if (!response.ok) throw new Error(`gateway answered ${response.status}`);
    const url = URL.createObjectURL(new Blob([await response.text()], {type: 'text/javascript'}));
    const app = (await import(url)).default;
    document.body.innerHTML = '<div id="admin-host"></div>';
    await app.create().mount(document.getElementById('admin-host'), {contractVersion: '1.1.0', moduleKey: `commerce-admin-${area}`, applicationKey: 'store', instanceId: 'i', slotId: 's', route: '/', params: {},
      locale, direction: 'LTR', context: {}, permissions: {can: () => true}, events: {publish() {}, subscribe: () => () => {}}, signal: new AbortController().signal, basePath: '/', navigate() {}});
  }, {area, locale, version: adminVersion(area)});
}

const adminVersion = area => read(join('.engine', version, `admin-mfes/modules/commerce-admin-${area}/mf-manifest.json`)).manifestVersion;

export async function signInInBrowser(page, user) {
  await page.waitForSelector('#username', {timeout: 20000}).catch(async () => {
    throw new Error(`No login form at ${page.url()}: ${(await page.textContent('body').catch(() => '')).slice(0, 300)}`);
  });
  await page.fill('#username', user.username);
  await page.fill('#password', user.password);
  await page.click('#kc-login');
}

export const signature = (secret, body) => createHmac('sha256', secret).update(body).digest('hex');
export {existsSync, writeFileSync};
