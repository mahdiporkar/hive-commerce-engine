#!/usr/bin/env node
// Registers one store's commerce application in its Hive from the released templates and admin bundles. Client
// repositories call this; they keep no copy of the operation list. Idempotent, like `hive register`: existing objects
// and identical manifests are kept. Uses the deployment's provisioning credential, which never reaches the engine.
//
//   HIVE_PROVISIONING_PASSWORD=... node register.mjs --authorization <url> --application <key> --display-name <name> \
//     --engine-url <url> [--modules-url <url> --modules-dir <dir>] [--extra <resource-manifest.json>]...
import {existsSync, readFileSync, readdirSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

export function render(text, values) {
  for (const [key, value] of Object.entries(values)) text = text.replaceAll(`{{${key}}}`, value);
  const left = /\{\{[a-zA-Z]+\}\}/.exec(text);
  if (left) throw new Error(`Unrendered placeholder ${left[0]}`);
  return JSON.parse(text);
}

export async function register({authorization, password, application, displayName, engineUrl, modulesUrl, modulesDir, extras = [], templates = join(HERE, 'templates'), log = () => {}}) {
  if (!authorization || !password || !application || !engineUrl) throw new Error('register needs --authorization, --application, --engine-url and HIVE_PROVISIONING_PASSWORD');
  const auth = 'Basic ' + Buffer.from(`provisioner:${password}`).toString('base64');
  const call = async (method, path, body, {allow = []} = {}) => {
    const response = await fetch(authorization + path, {method, headers: {Authorization: auth, 'Content-Type': 'application/json'}, ...(body === undefined ? {} : {body: JSON.stringify(body)})});
    const text = await response.text();
    const value = text ? JSON.parse(text) : null;
    if (response.status >= 300 && !allow.includes(response.status)) throw new Error(`${method} ${path}: ${response.status} ${value?.code ?? ''} ${value?.message ?? ''}`.trim());
    return {status: response.status, value};
  };
  const steps = [];
  const step = (name, result) => { steps.push({name, status: result.status}); log(`${result.status < 300 ? 'applied' : 'kept   '}  ${name}`); };

  const applyResources = async (moduleKey, moduleName, manifest) => {
    step(`module ${moduleKey}`, await call('POST', '/admin/modules', {applicationKey: application, moduleKey, displayName: moduleName, definitionMode: 'MANIFEST'}, {allow: [409]}));
    step(`resource manifest ${moduleKey}@${manifest.manifestVersion}`, await call('POST', `/admin/modules/${moduleKey}/resource-manifests`, manifest));
    const revision = (await call('GET', `/admin/modules/${moduleKey}/resource-manifests/${manifest.manifestVersion}`)).value;
    if (revision.status === 'DRAFT') step(`publish ${moduleKey}@${manifest.manifestVersion}`, await call('POST', `/admin/modules/${moduleKey}/resource-manifests/${manifest.manifestVersion}/publish`));
  };

  step(`application ${application}`, await call('POST', '/admin/applications', {key: application, displayName: displayName ?? application}, {allow: [409]}));
  await applyResources('commerce-api', 'Commerce API', render(readFileSync(join(templates, 'commerce-api/resource-manifest.json'), 'utf8'), {applicationKey: application}));

  const service = render(readFileSync(join(templates, 'commerce-service.json'), 'utf8'), {applicationKey: application, engineBaseUrl: engineUrl});
  step(`service target ${service.target.key}`, await call('POST', '/admin/service-targets', service.target, {allow: [409]}));
  step(`route ${service.route.key}`, await call('POST', '/admin/proxy-routes', service.route, {allow: [409]}));
  for (const operation of service.operations) step(`operation ${operation.key}`, await call('POST', `/admin/proxy-routes/${service.route.key}/operations`, operation, {allow: [409]}));

  if (modulesDir) {
    if (!modulesUrl) throw new Error('--modules-dir needs --modules-url');
    for (const moduleKey of readdirSync(modulesDir).sort()) {
      const values = {applicationKey: application, modulesBaseUrl: modulesUrl};
      const resources = render(readFileSync(join(modulesDir, moduleKey, 'resource-manifest.json'), 'utf8'), values);
      const mf = render(readFileSync(join(modulesDir, moduleKey, 'mf-manifest.json'), 'utf8'), values);
      await applyResources(moduleKey, mf.displayName, resources);
      step(`artifact ${moduleKey}@${mf.manifestVersion}`, await call('POST', `/admin/modules/${moduleKey}/artifacts`, mf));
      step(`activate ${moduleKey}@${mf.manifestVersion}`, await call('POST', `/admin/modules/${moduleKey}/artifacts/${mf.manifestVersion}/activate`));
    }
  }

  // Industry resources declared by the client's extensions, outside the shared module list.
  for (const path of extras) {
    const manifest = render(readFileSync(path, 'utf8'), {applicationKey: application});
    await applyResources(manifest.moduleKey, manifest.moduleKey, manifest);
  }

  const {roles} = JSON.parse(readFileSync(join(templates, 'roles.json'), 'utf8'));
  for (const role of roles) {
    step(`role ${role.key}`, await call('POST', '/admin/roles', {key: role.key, displayName: role.displayName, description: role.description}, {allow: [409]}));
    for (const [resourceKey, actions] of Object.entries(role.grants)) {
      if (resourceKey.startsWith('commerce.admin.') && !modulesDir) continue; // the page resource exists only with its module
      for (const action of actions) step(`grant ${role.key} ${resourceKey}#${action}`, await call('POST', '/admin/grants', {subject: `role:${role.key}`, applicationKey: application, resourceKey, action}, {allow: [409]}));
    }
  }
  return steps;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = {extras: []};
  for (let i = 2; i < process.argv.length; i += 2) {
    const key = process.argv[i].replace(/^--/, ''), value = process.argv[i + 1];
    if (key === 'extra') args.extras.push(value);
    else args[key.replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = value;
  }
  if (args.modulesDir && !existsSync(args.modulesDir)) throw new Error(`No admin bundles at ${args.modulesDir}`);
  await register({...args, password: process.env.HIVE_PROVISIONING_PASSWORD, log: console.log});
  console.log(`registered ${args.application}`);
}
