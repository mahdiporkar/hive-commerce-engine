#!/usr/bin/env node
// Tests and packages this store's engine extensions against the pinned engine's SPI, then places the JARs where the
// engine image picks them up (deploy/extensions). Run after tools/fetch-engine.mjs.
import {execSync} from 'node:child_process';
import {cpSync, existsSync, mkdirSync, readFileSync, readdirSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const version = process.env.ENGINE_VERSION ?? JSON.parse(readFileSync(join(ROOT, 'engine.lock.json'), 'utf8')).version;
const spi = join(ROOT, '.engine', version, 'commerce-spi.jar');
const extensions = join(ROOT, 'extensions');
const mvn = process.platform === 'win32' ? '.\\mvnw.cmd' : 'sh mvnw';
const run = args => execSync(`${mvn} -B -q ${args}`, {cwd: ROOT, stdio: 'inherit'});

const modules = existsSync(extensions) ? readdirSync(extensions).filter(name => existsSync(join(extensions, name, 'pom.xml'))) : [];
if (modules.length) run(`install:install-file "-Dfile=${spi}" -DgroupId=io.hivecommerce -DartifactId=commerce-spi "-Dversion=${version}" -Dpackaging=jar`);
mkdirSync(join(ROOT, 'deploy/extensions'), {recursive: true});
for (const name of modules) {
  run(`-f "extensions/${name}/pom.xml" clean verify "-Dengine.version=${version}"`);
  cpSync(join(extensions, name, 'target', `${name}.jar`), join(ROOT, 'deploy/extensions', `${name}.jar`));
  console.log(`built ${name} against engine ${version}`);
}
