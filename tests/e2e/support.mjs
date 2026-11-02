// End-to-end harness: a real Hive core (authorization + BFF jars built from hive/HIVE_REVISION, PostgreSQL, OpenFGA,
// Redis via Hive's own compose file), a real Keycloak, the commerce engine jar and its own PostgreSQL.
// Everything binds to 127.0.0.1, uses throwaway credentials and is removed afterwards. Mirrors Hive's tests/support/stack.mjs.
import {execFileSync, spawn} from 'node:child_process';
import {randomBytes} from 'node:crypto';
import {closeSync, existsSync, mkdirSync, openSync, readFileSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SHA = readFileSync(resolve(ROOT, 'hive/HIVE_REVISION'), 'utf8').trim();
const SHORT = SHA.slice(0, 7);
const HIVE = resolve(ROOT, '.local/hive-src', SHORT);
const LOGS = resolve(ROOT, '.local/e2e');

export const PORTS = {db: 29500, fga: 29501, redis: 29502, authorization: 29503, bff: 29504, keycloak: 29510, engine: 29520, engineDb: 29521};
export const ISSUER = `http://127.0.0.1:${PORTS.keycloak}/realms/commerce-e2e`;
export const BFF = `http://127.0.0.1:${PORTS.bff}`;
export const ENGINE = `http://127.0.0.1:${PORTS.engine}`;
export const APPLICATION = 'commerce-e2e';
export const FAKE_PSP_SECRET = 'test-only-fake-psp-secret-e2e-0001';

const pause = ms => new Promise(r => setTimeout(r, ms));
const secret = () => randomBytes(24).toString('hex');
const docker = (...args) => execFileSync('docker', args, {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 300000});

export async function ready(url, attempts = 300) {
  const keepAlive = setInterval(() => {}, 1000);
  try {
    for (let n = 0; n < attempts; n++) {
      try { if ((await fetch(url, {signal: AbortSignal.timeout(1500)})).ok) return; } catch {}
      await pause(500);
    }
    throw new Error(`Readiness deadline exceeded: ${url}`);
  } finally { clearInterval(keepAlive); }
}

export async function startEnvironment(t) {
  for (const jar of [`services/authorization/target/authorization-0.1.0-${SHORT}.jar`, `services/bff/target/bff-0.1.0-${SHORT}.jar`]) {
    if (!existsSync(resolve(HIVE, jar))) throw new Error(`Missing ${jar}; run tools/build-hive-starter.sh --services`);
  }
  const engineJar = resolve(ROOT, 'commerce-app/target/commerce-engine.jar');
  if (!existsSync(engineJar)) throw new Error('Missing commerce-app/target/commerce-engine.jar; run ./mvnw -B install');
  mkdirSync(LOGS, {recursive: true});

  const run = Date.now();
  const credentials = {db: secret(), graph: secret(), redis: secret(), internal: secret(), provisioning: secret(), vault: randomBytes(32).toString('base64'),
    engineOwner: secret(), engineRuntime: secret()};
  const project = `commerce-e2e-${run}`, keycloak = `commerce-e2e-keycloak-${run}`, engineDb = `commerce-e2e-db-${run}`;
  const children = [], fds = [];
  const composeEnv = {...process.env, HIVE_DB_PASSWORD: credentials.db, HIVE_GRAPH_PASSWORD: credentials.graph, HIVE_REDIS_PASSWORD: credentials.redis,
    HIVE_DB_PORT: String(PORTS.db), HIVE_FGA_PORT: String(PORTS.fga), HIVE_REDIS_PORT: String(PORTS.redis)};
  const compose = (...args) => execFileSync('docker', ['compose', '-p', project, '-f', resolve(HIVE, 'infra/docker-compose/compose.yml'), ...args],
    {env: composeEnv, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 300000});
  const java = (name, jar, env) => {
    const fd = openSync(resolve(LOGS, `${name}.log`), 'w'); fds.push(fd);
    const child = spawn('java', ['-jar', jar], {windowsHide: true, stdio: ['ignore', fd, fd], env: {...process.env, ...env}});
    children.push(child);
    return child;
  };

  t.after(async () => {
    for (const child of children.reverse()) {
      child.kill();
      await new Promise(r => { if (child.exitCode !== null) return r(); child.once('exit', r); setTimeout(r, 5000).unref(); });
    }
    for (const fd of fds) closeSync(fd);
    for (const name of [keycloak, engineDb]) { try { docker('rm', '-f', '-v', name); } catch {} }
    try { compose('down', '--volumes', '--remove-orphans'); } catch (e) { console.error('compose down failed', e.message); }
  });

  // Keycloak (reference IdP) with the e2e realm.
  docker('run', '-d', '--name', keycloak, '-p', `127.0.0.1:${PORTS.keycloak}:8080`,
    '-e', 'KC_BOOTSTRAP_ADMIN_USERNAME=bootstrap', '-e', 'KC_BOOTSTRAP_ADMIN_PASSWORD=test-only-bootstrap',
    '--mount', `type=bind,source=${resolve(ROOT, 'tests/e2e/keycloak')},target=/opt/keycloak/data/import,readonly`,
    'quay.io/keycloak/keycloak:26.3.3', 'start-dev', '--import-realm', '--hostname-strict=false');

  // Engine database: the container superuser owns migrations; commerce_app is a plain runtime role.
  docker('run', '-d', '--name', engineDb, '-p', `127.0.0.1:${PORTS.engineDb}:5432`, '-e', 'POSTGRES_DB=commerce', '-e', 'POSTGRES_USER=commerce_owner',
    '-e', `POSTGRES_PASSWORD=${credentials.engineOwner}`, 'postgres:17.6-alpine');

  // Hive core dependencies.
  compose('up', '-d', '--wait', 'postgres', 'graph-db', 'redis');
  compose('up', '-d', 'openfga');
  await ready(`http://127.0.0.1:${PORTS.fga}/healthz`);

  for (let n = 0; ; n++) {
    try { docker('exec', engineDb, 'pg_isready', '-U', 'commerce_owner', '-d', 'commerce'); break; } catch { if (n > 60) throw new Error('engine db not ready'); await pause(1000); }
  }
  for (let n = 0; ; n++) {
    try {
      docker('exec', engineDb, 'psql', '-U', 'commerce_owner', '-d', 'commerce', '-v', 'ON_ERROR_STOP=1', '-c',
        `create role commerce_app login password '${credentials.engineRuntime}' nosuperuser nocreaterole nobypassrls; grant connect on database commerce to commerce_app;`);
      break;
    } catch (e) { if (n > 20) throw e; await pause(1000); }
  }

  await ready(`${ISSUER}/.well-known/openid-configuration`, 400);

  java('authorization', resolve(HIVE, `services/authorization/target/authorization-0.1.0-${SHORT}.jar`), {
    HIVE_PROFILE: 'development', HIVE_AUTHORIZATION_PORT: String(PORTS.authorization), HIVE_DB_URL: `jdbc:postgresql://127.0.0.1:${PORTS.db}/hive`,
    HIVE_DB_USER: 'hive', HIVE_DB_PASSWORD: credentials.db, HIVE_OPENFGA_URL: `http://127.0.0.1:${PORTS.fga}`, HIVE_INTERNAL_PASSWORD: credentials.internal,
    HIVE_PROVISIONING_PASSWORD: credentials.provisioning, HIVE_REDIS_HOST: '127.0.0.1', HIVE_REDIS_PORT: String(PORTS.redis), HIVE_REDIS_PASSWORD: credentials.redis,
    HIVE_PRIMARY_ISSUER: ISSUER, HIVE_TARGET_ALLOW_HTTP: 'true'});
  await ready(`http://127.0.0.1:${PORTS.authorization}/actuator/health/readiness`);

  java('engine', engineJar, {
    HIVE_PROFILE: 'development', COMMERCE_PORT: String(PORTS.engine), COMMERCE_DB_URL: `jdbc:postgresql://127.0.0.1:${PORTS.engineDb}/commerce`,
    COMMERCE_DB_USER: 'commerce_app', COMMERCE_DB_PASSWORD: credentials.engineRuntime, COMMERCE_DB_OWNER_USER: 'commerce_owner',
    COMMERCE_DB_OWNER_PASSWORD: credentials.engineOwner, COMMERCE_TENANT_ID: 'default', COMMERCE_STORE_KEY: 'e2e-store',
    COMMERCE_JWT_ISSUER: ISSUER, COMMERCE_JWT_JWKS_URI: `${ISSUER}/protocol/openid-connect/certs`, COMMERCE_JWT_AUDIENCE: 'commerce-engine',
    COMMERCE_PAYMENT_PROVIDER: 'fake', COMMERCE_FAKE_PAYMENTS: 'true', COMMERCE_FAKE_PAYMENTS_SECRET: FAKE_PSP_SECRET,
    COMMERCE_PAYMENT_RETURN_URL: `${BFF}/checkout/return`, COMMERCE_TAX_RATE_PERCENT: '9', COMMERCE_FLAT_SHIPPING: '500000'});
  await ready(`${ENGINE}/actuator/health/readiness`);

  java('bff', resolve(HIVE, `services/bff/target/bff-0.1.0-${SHORT}.jar`), {
    HIVE_PROFILE: 'development', HIVE_BFF_PORT: String(PORTS.bff), HIVE_AUTHORIZATION_URL: `http://127.0.0.1:${PORTS.authorization}`,
    HIVE_INTERNAL_PASSWORD: credentials.internal, HIVE_CONTROL_ALLOW_HTTP: 'true', HIVE_REDIS_HOST: '127.0.0.1', HIVE_REDIS_PORT: String(PORTS.redis),
    HIVE_REDIS_PASSWORD: credentials.redis, HIVE_VAULT_KEY: credentials.vault, HIVE_IDENTITY_ENABLED: 'true', HIVE_OIDC_ISSUER: ISSUER,
    HIVE_OIDC_CLIENT_ID: 'hive-bff', HIVE_OIDC_CLIENT_SECRET: 'test-only-keycloak-client-secret-0001', HIVE_OIDC_ALLOW_LOCAL_HTTP: 'true',
    HIVE_PROXY_ALLOWED_ORIGINS: ENGINE, HIVE_PROXY_ALLOW_HTTP: 'true'});
  await ready(`${BFF}/actuator/health/readiness`);

  const provision = async (method, path, body) => {
    const response = await fetch(`http://127.0.0.1:${PORTS.authorization}${path}`, {method,
      headers: {'Content-Type': 'application/json', Authorization: 'Basic ' + Buffer.from(`provisioner:${credentials.provisioning}`).toString('base64')},
      ...(body === undefined ? {} : {body: JSON.stringify(body)})});
    const text = await response.text();
    return {status: response.status, body: text ? JSON.parse(text) : null};
  };
  const engineSql = statement => docker('exec', engineDb, 'psql', '-U', 'commerce_owner', '-d', 'commerce', '-At', '-c', statement).trim();
  const engineLog = () => readFileSync(resolve(LOGS, 'engine.log'), 'utf8');
  return {provision, engineSql, engineLog};
}

/** Renders a template from hive/templates with the given placeholders. */
export function template(path, values) {
  let text = readFileSync(resolve(ROOT, 'hive/templates', path), 'utf8');
  for (const [key, value] of Object.entries(values)) text = text.replaceAll(`{{${key}}}`, value);
  if (/\{\{[a-zA-Z]+\}\}/.test(text)) throw new Error(`Unrendered placeholder in ${path}`);
  return JSON.parse(text);
}

/** Cookie-jar HTTP client for one origin (no browser), as in Hive's tests/support/stack.mjs. */
export function cookieClient(origin) {
  const jar = new Map();
  const client = async (path, options = {}) => {
    const cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
    const response = await fetch(new URL(path, origin), {...options, redirect: 'manual', headers: {...(cookie ? {Cookie: cookie} : {}), ...options.headers}});
    for (const value of response.headers.getSetCookie()) {
      const [pair] = value.split(';'); const i = pair.indexOf('=');
      const name = pair.slice(0, i), v = pair.slice(i + 1);
      if (/Max-Age=0/i.test(value) || v === '') jar.delete(name); else jar.set(name, v);
    }
    return response;
  };
  client.csrf = async () => { const token = await (await client('/auth/csrf')).json(); return {[token.headerName]: token.token}; };
  return client;
}

/** Signs in through the BFF's OIDC login with Keycloak's real login form; returns a BFF client holding HIVE_SESSION. */
export async function signIn(username, password) {
  const bff = cookieClient(BFF), kc = cookieClient(`http://127.0.0.1:${PORTS.keycloak}`);
  let response = await bff('/auth/login?returnUrl=/');
  let location = response.headers.get('location');
  for (let hops = 0; location && new URL(location, BFF).origin === BFF && hops < 5; hops++) {
    response = await bff(location);
    location = response.headers.get('location');
  }
  if (!location) throw new Error(`BFF did not redirect to the identity provider (status ${response.status})`);
  const page = await kc(location);
  const html = await page.text();
  const action = /<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"/.exec(html)?.[1]?.replaceAll('&amp;', '&');
  if (!action) throw new Error('Keycloak login form not found');
  response = await kc(action, {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams({username, password, credentialId: ''}).toString()});
  location = response.headers.get('location');
  if (!location || new URL(location).origin !== BFF) throw new Error(`Keycloak did not return to the BFF (status ${response.status})`);
  response = await bff(location);
  if (response.status !== 302) throw new Error(`BFF callback failed with ${response.status}`);
  const session = await (await bff('/api/me/session')).json();
  return {bff, session};
}
