#!/usr/bin/env node
// commerce-builder new-store --store store.json --out ../my-store --engine-version 0.2.0 [--taxonomy t.json] [--products p.json]
// commerce-builder validate  --store store.json [--taxonomy t.json] [--products p.json]
// commerce-builder env       --store store.json
import {readFileSync} from 'node:fs';
import {engineEnvironment, newStore, validateCatalog, validateStore} from './index.mjs';

const [command, ...rest] = process.argv.slice(2);
const args = {};
for (let i = 0; i < rest.length; i += 2) args[rest[i].replace(/^--/, '')] = rest[i + 1];
const json = path => path ? JSON.parse(readFileSync(path, 'utf8')) : undefined;
const fail = message => { console.error(message); process.exit(1); };
if (!args.store) fail('--store <file> is required');
const store = json(args.store);

switch (command) {
  case 'validate': {
    const problems = [...validateStore(store)];
    if (!problems.length && args.taxonomy) problems.push(...validateCatalog(store, {...json(args.taxonomy), ...json(args.products)}));
    if (problems.length) fail(problems.join('\n'));
    console.log(`${store.key}: valid`);
    break;
  }
  case 'env':
    for (const [key, value] of Object.entries(engineEnvironment(store))) console.log(`${key}=${value}`);
    break;
  case 'new-store':
    if (!args.out || !args['engine-version']) fail('new-store needs --out and --engine-version');
    newStore(store, args.out, {engineVersion: args['engine-version'], taxonomy: json(args.taxonomy), products: json(args.products)});
    console.log(`generated ${store.key} in ${args.out}`);
    break;
  default:
    fail('commands: new-store, validate, env');
}
