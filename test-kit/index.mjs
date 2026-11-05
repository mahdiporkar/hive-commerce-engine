// End-to-end harness: a real Hive core (authorization + BFF jars built from the pinned revision, PostgreSQL, OpenFGA,
// Redis via Hive's own compose file), a real Keycloak, the commerce engine jar and its own PostgreSQL and object storage.
// Everything binds to 127.0.0.1, uses throwaway credentials and is removed afterwards. Mirrors Hive's tests/support/stack.mjs.
// The engine and each client repository run their own stack from their own port base, so stacks can coexist.
import {execFileSync, spawn} from 'node:child_process';
import {randomBytes} from 'node:crypto';
import {closeSync, createReadStream, existsSync, mkdirSync, openSync, readFileSync, statSync, writeFileSync} from 'node:fs';
import {createServer} from 'node:http';
import {dirname, extname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const MINIO = 'cgr.dev/chainguard/minio@sha256:0dab2f9fee1c1139ae8753b5e35bb4461af2a276cfe0923a2504c75a6170bc64';
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

/** Serves a directory over HTTP (the registered address of the admin bundles). Closed by the caller. */
export function serveDirectory(directory, port) {
  const types = {'.js': 'text/javascript', '.json': 'application/json', '.css': 'text/css'};
  const server = createServer((request, response) => {
    const path = resolve(directory, '.' + decodeURIComponent(new URL(request.url, 'http://x').pathname));
    if (!path.startsWith(resolve(directory)) || !existsSync(path) || !statSync(path).isFile()) { response.writeHead(404).end(); return; }
    response.writeHead(200, {'Content-Type': types[extname(path)] ?? 'application/octet-stream'});
    createReadStream(path).pipe(response);
  });
  return new Promise(r => server.listen(port, '127.0.0.1', () => r(server)));
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

/**
 * @param options.hiveDir      built Hive source tree (tools/build-hive-starter.sh --services)
 * @param options.hiveVersion  jar version suffix, e.g. a75871a
 * @param options.engineJar    engine jar to run
 * @param options.portBase     first of a block of 30 local ports
 * @param options.workDir      logs and generated realm
 * @param options.storeKey     COMMERCE_STORE_KEY
 * @param options.application  Hive application key
 * @param options.users        test users [{username, password, firstName?}]
 * @param options.origins      extra browser origins allowed by the identity provider (the storefront)
 * @param options.engineEnv    extra engine environment
 * @param options.extensions   directory of extension JARs (LOADER_PATH), as in the client image
 * @param options.storage      true to start object storage for digital delivery
 */
export function createKit({hiveDir, hiveVersion, engineJar, portBase, workDir, storeKey, application, users, origins = [], engineEnv = {}, bffEnv = {}, extensions, storage = false, heap = '384m'}) {
  const at = n => portBase + n;
  const PORTS = {db: at(0), fga: at(1), redis: at(2), authorization: at(3), bff: at(4), keycloak: at(10), engine: at(20), engineDb: at(21), storage: at(22), modules: at(23)};
  const ISSUER = `http://127.0.0.1:${PORTS.keycloak}/realms/commerce-e2e`;
  const BFF = `http://127.0.0.1:${PORTS.bff}`;
  const ENGINE = `http://127.0.0.1:${PORTS.engine}`;
  const FAKE_PSP_SECRET = 'test-only-fake-psp-secret-e2e-0001';
  const MODULES_URL = `http://127.0.0.1:${PORTS.modules}`;
  const STORAGE_URL = `http://127.0.0.1:${PORTS.storage}`;

  function writeRealm() {
    const realm = JSON.parse(readFileSync(join(HERE, 'realm.json'), 'utf8'));
    const client = realm.clients[0];
    const all = [BFF, ...origins];
    client.redirectUris = all.map(o => `${o}/login/oauth2/code/primary`);
    client.webOrigins = all;
    client.attributes['post.logout.redirect.uris'] = all.map(o => `${o}/*`).join('##');
    realm.users = users.map(u => ({username: u.username, enabled: true, email: `${u.username}@example.test`, emailVerified: true, firstName: u.firstName ?? u.username, lastName: 'Test',
      credentials: [{type: 'password', value: u.password, temporary: false}], requiredActions: []}));
    const dir = resolve(workDir, 'realm');
    mkdirSync(dir, {recursive: true});
    writeFileSync(join(dir, 'commerce-e2e-realm.json'), JSON.stringify(realm));
    return dir;
  }

  async function startEnvironment(t) {
    for (const jar of [`services/authorization/target/authorization-0.1.0-${hiveVersion}.jar`, `services/bff/target/bff-0.1.0-${hiveVersion}.jar`]) {
      if (!existsSync(resolve(hiveDir, jar))) throw new Error(`Missing ${jar}; run tools/build-hive-starter.sh --services`);
    }
    if (!existsSync(engineJar)) throw new Error(`Missing engine jar ${engineJar}`);
    const LOGS = resolve(workDir, 'logs');
    mkdirSync(LOGS, {recursive: true});

    const run = Date.now();
    const credentials = {db: secret(), graph: secret(), redis: secret(), internal: secret(), provisioning: secret(), vault: randomBytes(32).toString('base64'),
      engineOwner: secret(), engineRuntime: secret(), storage: secret()};
    const project = `commerce-e2e-${run}`, keycloak = `commerce-e2e-keycloak-${run}`, engineDb = `commerce-e2e-db-${run}`, store = `commerce-e2e-storage-${run}`;
    const children = [], fds = [];
    const composeEnv = {...process.env, HIVE_DB_PASSWORD: credentials.db, HIVE_GRAPH_PASSWORD: credentials.graph, HIVE_REDIS_PASSWORD: credentials.redis,
      HIVE_DB_PORT: String(PORTS.db), HIVE_FGA_PORT: String(PORTS.fga), HIVE_REDIS_PORT: String(PORTS.redis)};
    const compose = (...args) => execFileSync('docker', ['compose', '-p', project, '-f', resolve(hiveDir, 'infra/docker-compose/compose.yml'), ...args],
      {env: composeEnv, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 300000});
    const java = (name, jar, env) => {
      const fd = openSync(resolve(LOGS, `${name}.log`), 'w'); fds.push(fd);
      const child = spawn('java', [`-Xmx${heap}`, '-jar', jar], {windowsHide: true, stdio: ['ignore', fd, fd], env: {...process.env, ...env}});
      children.push(child);
      return child;
    };
    const stop = async child => {
      child.kill();
      await new Promise(r => { if (child.exitCode !== null) return r(); child.once('exit', r); setTimeout(r, 5000).unref(); });
    };

    t.after(async () => {
      for (const child of children.reverse()) await stop(child);
      for (const fd of fds) closeSync(fd);
      for (const name of [keycloak, engineDb, ...(storage ? [store] : [])]) { try { docker('rm', '-f', '-v', name); } catch {} }
      try { compose('down', '--volumes', '--remove-orphans'); } catch (e) { console.error('compose down failed', e.message); }
    });

    // Keycloak (reference IdP) with the e2e realm.
    docker('run', '-d', '--name', keycloak, '-p', `127.0.0.1:${PORTS.keycloak}:8080`,
      '-e', 'KC_BOOTSTRAP_ADMIN_USERNAME=bootstrap', '-e', 'KC_BOOTSTRAP_ADMIN_PASSWORD=test-only-bootstrap', '-e', 'JAVA_OPTS_KC_HEAP=-Xmx512m',
      '--mount', `type=bind,source=${writeRealm()},target=/opt/keycloak/data/import,readonly`,
      'quay.io/keycloak/keycloak:26.3.3', 'start-dev', '--import-realm', '--hostname-strict=false');

    // Engine database: the container superuser owns migrations; commerce_app is a plain runtime role.
    docker('run', '-d', '--name', engineDb, '-p', `127.0.0.1:${PORTS.engineDb}:5432`, '-e', 'POSTGRES_DB=commerce', '-e', 'POSTGRES_USER=commerce_owner',
      '-e', `POSTGRES_PASSWORD=${credentials.engineOwner}`, 'postgres:17.6-alpine');

    if (storage) {
      docker('run', '-d', '--name', store, '-p', `127.0.0.1:${PORTS.storage}:9000`, '-e', 'MINIO_ROOT_USER=test-only-storage-user', '-e', `MINIO_ROOT_PASSWORD=${credentials.storage}`,
        MINIO, 'server', '/tmp/data', '--address', ':9000');
      await ready(`${STORAGE_URL}/minio/health/live`);
    }

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

    java('authorization', resolve(hiveDir, `services/authorization/target/authorization-0.1.0-${hiveVersion}.jar`), {
      HIVE_PROFILE: 'development', HIVE_AUTHORIZATION_PORT: String(PORTS.authorization), HIVE_DB_URL: `jdbc:postgresql://127.0.0.1:${PORTS.db}/hive`,
      HIVE_DB_USER: 'hive', HIVE_DB_PASSWORD: credentials.db, HIVE_OPENFGA_URL: `http://127.0.0.1:${PORTS.fga}`, HIVE_INTERNAL_PASSWORD: credentials.internal,
      HIVE_PROVISIONING_PASSWORD: credentials.provisioning, HIVE_REDIS_HOST: '127.0.0.1', HIVE_REDIS_PORT: String(PORTS.redis), HIVE_REDIS_PASSWORD: credentials.redis,
      HIVE_PRIMARY_ISSUER: ISSUER, HIVE_TARGET_ALLOW_HTTP: 'true', HIVE_ARTIFACT_NETWORK_POLICY: 'UNRESTRICTED', HIVE_ARTIFACT_ALLOW_HTTP: 'true'});
    await ready(`http://127.0.0.1:${PORTS.authorization}/actuator/health/readiness`);

    const engineEnvironment = {
      HIVE_PROFILE: 'development', COMMERCE_PORT: String(PORTS.engine), COMMERCE_DB_URL: `jdbc:postgresql://127.0.0.1:${PORTS.engineDb}/commerce`,
      COMMERCE_DB_USER: 'commerce_app', COMMERCE_DB_PASSWORD: credentials.engineRuntime, COMMERCE_DB_OWNER_USER: 'commerce_owner',
      COMMERCE_DB_OWNER_PASSWORD: credentials.engineOwner, COMMERCE_TENANT_ID: 'default', COMMERCE_STORE_KEY: storeKey,
      COMMERCE_JWT_ISSUER: ISSUER, COMMERCE_JWT_JWKS_URI: `${ISSUER}/protocol/openid-connect/certs`, COMMERCE_JWT_AUDIENCE: 'commerce-engine',
      COMMERCE_PAYMENT_PROVIDER: 'fake', COMMERCE_FAKE_PAYMENTS: 'true', COMMERCE_FAKE_PAYMENTS_SECRET: FAKE_PSP_SECRET,
      COMMERCE_PAYMENT_RETURN_URL: `${BFF}/checkout/return`, COMMERCE_TAX_RATE_PERCENT: '9', COMMERCE_FLAT_SHIPPING: '500000',
      ...(extensions ? {LOADER_PATH: extensions} : {}),
      ...(storage ? {COMMERCE_STORAGE_ENDPOINT: STORAGE_URL, COMMERCE_STORAGE_PUBLIC_ENDPOINT: STORAGE_URL, COMMERCE_STORAGE_ACCESS_KEY: 'test-only-storage-user',
        COMMERCE_STORAGE_SECRET_KEY: credentials.storage, COMMERCE_STORAGE_BUCKET: `${storeKey}-assets`, COMMERCE_STORAGE_CREATE_BUCKET: 'true'} : {}),
      ...engineEnv};
    let engine = java('engine', engineJar, engineEnvironment);
    await ready(`${ENGINE}/actuator/health/readiness`);

    java('bff', resolve(hiveDir, `services/bff/target/bff-0.1.0-${hiveVersion}.jar`), {
      HIVE_PROFILE: 'development', HIVE_BFF_PORT: String(PORTS.bff), HIVE_AUTHORIZATION_URL: `http://127.0.0.1:${PORTS.authorization}`,
      HIVE_INTERNAL_PASSWORD: credentials.internal, HIVE_CONTROL_ALLOW_HTTP: 'true', HIVE_REDIS_HOST: '127.0.0.1', HIVE_REDIS_PORT: String(PORTS.redis),
      HIVE_REDIS_PASSWORD: credentials.redis, HIVE_VAULT_KEY: credentials.vault, HIVE_IDENTITY_ENABLED: 'true', HIVE_OIDC_ISSUER: ISSUER,
      HIVE_OIDC_CLIENT_ID: 'hive-bff', HIVE_OIDC_CLIENT_SECRET: 'test-only-keycloak-client-secret-0001', HIVE_OIDC_ALLOW_LOCAL_HTTP: 'true',
      HIVE_PROXY_ALLOWED_ORIGINS: ENGINE, HIVE_PROXY_ALLOW_HTTP: 'true', HIVE_MFE_NETWORK_POLICY: 'UNRESTRICTED', HIVE_MFE_ALLOW_HTTP: 'true', ...bffEnv});
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
    /** Swaps the running engine for another jar on the same database and storage (Scenario F). */
    const restartEngine = async (jar, env = {}) => {
      await stop(engine);
      engine = java(`engine-${Date.now()}`, jar, {...engineEnvironment, ...env});
      await ready(`${ENGINE}/actuator/health/readiness`);
    };
    return {provision, engineSql, engineLog, restartEngine, provisioningPassword: credentials.provisioning};
  }

  /** Signs in through the BFF's OIDC login with Keycloak's real login form; returns a BFF client holding HIVE_SESSION. */
  async function signIn(username, password) {
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

  return {PORTS, ISSUER, BFF, ENGINE, APPLICATION: application, FAKE_PSP_SECRET, MODULES_URL, STORAGE_URL, startEnvironment, signIn};
}
