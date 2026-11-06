#!/usr/bin/env node
// Registers this store in its own Hive with the pinned engine's templates and admin bundles. The store adds only its
// application key, addresses and its own extension resources.
//   HIVE_PROVISIONING_PASSWORD=... node tools/register.mjs --authorization <url> --engine-url <url> --modules-url <url>
import {existsSync, readFileSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const store = JSON.parse(readFileSync(join(ROOT, 'storefront/config/store.json'), 'utf8'));
const {version} = JSON.parse(readFileSync(join(ROOT, 'engine.lock.json'), 'utf8'));
const engineDir = requested => join(ROOT, '.engine', requested ?? process.env.ENGINE_VERSION ?? version);

export async function registerStore({authorization, password, engineUrl, modulesUrl, engineVersion, log}) {
  const engine = engineDir(engineVersion);
  const {register} = await import(pathToFileURL(join(engine, 'hive/register.mjs')).href);
  const extra = join(ROOT, 'hive/resource-manifest.json');
  return register({authorization, password, application: store.key, displayName: store.displayName[store.defaultLocale], engineUrl, modulesUrl,
    modulesDir: join(engine, 'admin-mfes/modules'), extras: existsSync(extra) ? [extra] : [], log});
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = {};
  for (let i = 2; i < process.argv.length; i += 2) args[process.argv[i].replace(/^--/, '').replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = process.argv[i + 1];
  await registerStore({...args, password: process.env.HIVE_PROVISIONING_PASSWORD, log: console.log});
}
