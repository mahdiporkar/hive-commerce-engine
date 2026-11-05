// The engine's own end-to-end stack: the shared test kit with the engine jar from this checkout and the fixture users.
import {readFileSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {cookieClient, createKit, ready, serveDirectory} from '../../test-kit/index.mjs';

export {cookieClient, ready, serveDirectory};
export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const SHORT = readFileSync(resolve(ROOT, 'hive/HIVE_REVISION'), 'utf8').trim().slice(0, 7);

const kit = createKit({
  hiveDir: resolve(ROOT, '.local/hive-src', SHORT), hiveVersion: SHORT, engineJar: resolve(ROOT, 'commerce-app/target/commerce-engine.jar'),
  portBase: 29500, workDir: resolve(ROOT, '.local/e2e'), storeKey: 'e2e-store', application: 'commerce-e2e', heap: '512m',
  users: [{username: 'alice.catalog', password: 'test-only-password-alice', firstName: 'Alice'}, {username: 'bob.shopper', password: 'test-only-password-bob', firstName: 'Bob'},
    {username: 'carol.buyer', password: 'test-only-password-carol', firstName: 'Carol'}, {username: 'dave.buyer', password: 'test-only-password-dave', firstName: 'Dave'}],
});

export const {PORTS, ISSUER, BFF, ENGINE, APPLICATION, FAKE_PSP_SECRET, MODULES_URL, startEnvironment, signIn} = kit;

/** Renders a template from hive/templates with the given placeholders. */
export function template(path, values) {
  let text = readFileSync(resolve(ROOT, 'hive/templates', path), 'utf8');
  for (const [key, value] of Object.entries(values)) text = text.replaceAll(`{{${key}}}`, value);
  if (/\{\{[a-zA-Z]+\}\}/.test(text)) throw new Error(`Unrendered placeholder in ${path}`);
  return JSON.parse(text);
}
