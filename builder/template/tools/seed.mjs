#!/usr/bin/env node
// Loads this store's catalog through its Hive route, signed in as a real operator whose session cookie is passed by the
// caller: every authorization and validation rule applies, and no privileged database path exists.
//   HIVE_SESSION=... node tools/seed.mjs --origin https://{{domain}} [--with-samples true]
import {readFileSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = path => JSON.parse(readFileSync(join(ROOT, path), 'utf8'));

export async function seedStore(send, {samples = true, log} = {}) {
  const {version} = read('engine.lock.json');
  const {seedCatalog} = await import(pathToFileURL(join(ROOT, '.engine', process.env.ENGINE_VERSION ?? version, 'builder/package/index.mjs')).href);
  return seedCatalog(send, read('storefront/config/store.json'), {...read('storefront/config/taxonomy.json'), ...(samples ? read('seed/products.json') : {})}, log);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = {};
  for (let i = 2; i < process.argv.length; i += 2) args[process.argv[i].replace(/^--/, '')] = process.argv[i + 1];
  const cookie = `HIVE_SESSION=${process.env.HIVE_SESSION}`;
  const token = await (await fetch(`${args.origin}/auth/csrf`, {headers: {Cookie: cookie}})).json();
  const send = async (method, path, body) => {
    const response = await fetch(`${args.origin}/api/routes/commerce${path}`, {method, headers: {Cookie: cookie, 'Content-Type': 'application/json', [token.headerName]: token.token}, ...(body === undefined ? {} : {body: JSON.stringify(body)})});
    const text = await response.text();
    return {status: response.status, body: text ? JSON.parse(text) : null};
  };
  await seedStore(send, {samples: args['with-samples'] === 'true', log: console.log});
}
