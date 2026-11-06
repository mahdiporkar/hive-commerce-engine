#!/usr/bin/env node
// Lays out every fetched engine version's admin bundles at the addresses their manifests declare, side by side in
// .engine/admin-host. Keeping the previous version here is what lets a rollback activate it again.
import {cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export function assembleAdminBundles() {
  const engine = join(ROOT, '.engine');
  const host = join(engine, 'admin-host');
  rmSync(host, {recursive: true, force: true});
  for (const version of readdirSync(engine)) {
    const modules = join(engine, version, 'admin-mfes/modules');
    if (!existsSync(modules)) continue;
    for (const moduleKey of readdirSync(modules)) {
      const {artifact} = JSON.parse(readFileSync(join(modules, moduleKey, 'mf-manifest.json'), 'utf8'));
      const target = join(host, artifact.url.replace('{{modulesBaseUrl}}/', ''));
      mkdirSync(dirname(target), {recursive: true});
      cpSync(join(modules, moduleKey, 'entry.js'), target);
    }
  }
  return host;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) console.log(assembleAdminBundles());
