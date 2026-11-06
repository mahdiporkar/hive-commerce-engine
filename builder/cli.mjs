#!/usr/bin/env node
// Commerce Builder: tooling for new stores, their schemas, capabilities and themes. Not a visual site builder.
//   commerce-builder new-store --store store.json --out ../my-store --engine-version 0.2.0 [--taxonomy t.json] [--products p.json]
//   commerce-builder validate  --store store.json [--taxonomy t.json] [--products p.json]
//   commerce-builder env       --store store.json
//   commerce-builder set       --store store.json theme.accent=#112233 capabilities.reviews=true   (validated, then saved)
//   commerce-builder schema-diff --from old-type.json --to new-type.json
import {readFileSync, writeFileSync} from 'node:fs';
import {engineEnvironment, newStore, schemaDiff, validateCatalog, validateStore} from './index.mjs';

const [command, ...rest] = process.argv.slice(2);
const args = {};
const assignments = [];
for (let i = 0; i < rest.length; i++) {
  if (rest[i].startsWith('--')) args[rest[i].slice(2)] = rest[++i];
  else assignments.push(rest[i]);
}
const json = path => path ? JSON.parse(readFileSync(path, 'utf8')) : undefined;
const fail = message => { console.error(message); process.exit(1); };
const store = () => args.store ? json(args.store) : fail('--store <file> is required');

switch (command) {
  case 'validate': {
    const config = store();
    const problems = [...validateStore(config)];
    if (!problems.length && args.taxonomy) problems.push(...validateCatalog(config, {...json(args.taxonomy), ...json(args.products)}));
    if (problems.length) fail(problems.join('\n'));
    console.log(`${config.key}: valid`);
    break;
  }
  case 'env':
    for (const [key, value] of Object.entries(engineEnvironment(store()))) console.log(`${key}=${value}`);
    break;
  case 'new-store':
    if (!args.out || !args['engine-version']) fail('new-store needs --out and --engine-version');
    newStore(store(), args.out, {engineVersion: args['engine-version'], taxonomy: json(args.taxonomy), products: json(args.products)});
    console.log(`generated ${store().key} in ${args.out}`);
    break;
  case 'set': {
    const config = store();
    for (const assignment of assignments) {
      const [path, value] = [assignment.slice(0, assignment.indexOf('=')), assignment.slice(assignment.indexOf('=') + 1)];
      const keys = path.split('.');
      if (!['theme', 'capabilities', 'displayName', 'tagline'].includes(keys[0]) && !['mode', 'currency', 'domain'].includes(path)) fail(`${path} cannot be set this way`);
      let target = config;
      for (const key of keys.slice(0, -1)) target = target[key] ??= {};
      target[keys.at(-1)] = value === 'true' ? true : value === 'false' ? false : /^-?\d+$/.test(value) ? Number(value) : value;
    }
    const problems = validateStore(config);
    if (problems.length) fail(problems.join('\n'));
    writeFileSync(args.store, JSON.stringify(config, null, 2) + '\n');
    console.log(`${config.key}: saved`);
    break;
  }
  case 'schema-diff': {
    if (!args.from || !args.to) fail('schema-diff needs --from and --to');
    const specs = value => value.attributes ?? value;
    console.log(JSON.stringify(schemaDiff(specs(json(args.from)), specs(json(args.to))), null, 2));
    break;
  }
  default:
    fail('commands: new-store, validate, env, set, schema-diff');
}
