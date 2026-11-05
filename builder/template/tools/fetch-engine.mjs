#!/usr/bin/env node
// Brings the pinned engine release into .engine/<version>/: the published GitHub release, or ENGINE_RELEASE_DIR for a
// locally packaged one. The checksums are verified either way. ENGINE_VERSION overrides the pin for upgrade checks.
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {cpSync, existsSync, mkdirSync, readFileSync, rmSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const lock = JSON.parse(readFileSync(join(ROOT, 'engine.lock.json'), 'utf8'));
const version = process.env.ENGINE_VERSION ?? lock.version;
const out = join(ROOT, '.engine', version);
const source = process.env.ENGINE_RELEASE_DIR;

rmSync(out, {recursive: true, force: true});
mkdirSync(out, {recursive: true});
if (source) cpSync(source, out, {recursive: true});
else execFileSync('gh', ['release', 'download', `v${version}`, '-R', lock.repository, '-D', out], {stdio: 'inherit'});

for (const line of readFileSync(join(out, 'SHA256SUMS'), 'utf8').trim().split('\n')) {
  const [sum, name] = line.split(/\s+\*?/);
  const actual = createHash('sha256').update(readFileSync(join(out, name))).digest('hex');
  if (actual !== sum) throw new Error(`Checksum mismatch for ${name}: the release is damaged or was tampered with`);
}
for (const [archive, into] of [['admin-mfes.tar.gz', 'admin-mfes'], ['hive-templates.tar.gz', '.'], ['test-kit.tgz', 'kit'], ['builder.tgz', 'builder']]) {
  mkdirSync(join(out, into), {recursive: true});
  execFileSync('tar', ['-xzf', archive, '-C', into === '.' ? '.' : into], {cwd: out});
}
// The storefront depends on this fixed path; package-lock.json pins the bytes.
mkdirSync(join(ROOT, '.engine/sdk'), {recursive: true});
cpSync(join(out, 'storefront-sdk.tgz'), join(ROOT, '.engine/sdk/storefront-sdk.tgz'));
if (!existsSync(join(out, 'kit/package/index.mjs'))) throw new Error('The release has no test kit');
console.log(`engine ${version} ready in .engine/${version}`);
