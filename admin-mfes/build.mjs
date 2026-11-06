// Bundles each admin area as one ES module, computes its SRI, and writes its Hive manifests with {{applicationKey}} left
// for the client's registration (master prompt §18.1). The Hive React adapter comes from the pinned Hive source tree.
import {createHash} from 'node:crypto';
import {mkdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const SHORT = readFileSync(join(ROOT, 'hive/HIVE_REVISION'), 'utf8').trim().slice(0, 7);
const HIVE = process.env.HIVE_SOURCE_DIR ?? join(ROOT, '.local/hive-src', SHORT);
const VERSION = JSON.parse(readFileSync(join(HERE, 'package.json'), 'utf8')).version;
const alias = Object.fromEntries(['contracts', 'core', 'authorization', 'workspace', 'react', 'mfe-runtime', 'http-client', 'auth'].map(p => {
  const tsx = join(HIVE, 'packages', p, 'src/index.tsx');
  const ts = join(HIVE, 'packages', p, 'src/index.ts');
  return [`@hive-platform/${p}`, p === 'react' ? tsx : ts];
}));

export const MODULES = [
  {area: 'catalog', label: {en: 'Catalog', fa: 'کاتالوگ'}, order: 10},
  {area: 'orders', label: {en: 'Orders', fa: 'سفارش‌ها'}, order: 20},
  {area: 'sellers', label: {en: 'Sellers', fa: 'فروشندگان'}, order: 30},
  {area: 'finance', label: {en: 'Finance', fa: 'مالی'}, order: 40},
  {area: 'digital', label: {en: 'Digital assets', fa: 'دارایی‌های دیجیتال'}, order: 50},
  {area: 'payments', label: {en: 'Payments', fa: 'پرداخت‌ها'}, order: 25},
  {area: 'customers', label: {en: 'Customers', fa: 'مشتریان'}, order: 28},
  {area: 'settings', label: {en: 'Store settings', fa: 'تنظیمات فروشگاه'}, order: 60},
];

rmSync(join(HERE, 'dist'), {recursive: true, force: true});
for (const {area, label, order} of MODULES) {
  const moduleKey = `commerce-admin-${area}`;
  const out = join(HERE, 'dist/modules', moduleKey);
  mkdirSync(out, {recursive: true});
  await build({
    entryPoints: [join(HERE, 'modules', `${area}.tsx`)], outfile: join(out, 'entry.js'), bundle: true, format: 'esm', target: 'es2022', jsx: 'automatic', minify: true,
    legalComments: 'none', define: {'process.env.NODE_ENV': '"production"'}, alias, nodePaths: [join(HERE, 'node_modules')], logLevel: 'warning',
  });
  const integrity = 'sha384-' + createHash('sha384').update(readFileSync(join(out, 'entry.js'))).digest('base64');
  const page = `commerce.admin.${area}`;
  writeFileSync(join(out, 'resource-manifest.json'), JSON.stringify({
    schemaVersion: '1.0.0', manifestVersion: VERSION, applicationKey: '{{applicationKey}}', moduleKey,
    resources: [
      {key: moduleKey, type: 'MODULE', parentKey: '{{applicationKey}}', name: `Commerce admin: ${label.en}`, actions: ['view']},
      {key: page, type: 'PAGE', parentKey: moduleKey, name: label.en, actions: ['view']},
    ],
  }, null, 2) + '\n');
  writeFileSync(join(out, 'mf-manifest.json'), JSON.stringify({
    schemaVersion: '1.0.0', manifestVersion: VERSION, contractVersion: '1.1.0', runtimeVersion: '1.0.0', applicationKey: '{{applicationKey}}', moduleKey, displayName: label.en,
    resourceManifestVersion: VERSION, artifact: {url: `{{modulesBaseUrl}}/${VERSION}/${moduleKey}/entry.js`, integrity, format: 'ES_MODULE'}, styleIsolation: 'SHADOW_DOM',
    routes: [{key: 'home', path: `/admin/commerce/${area}`, access: 'AUTHENTICATED', resource: page, action: 'view', navigation: {label: label.en, order}}],
  }, null, 2) + '\n');
}
console.log(`built ${MODULES.length} modules (${VERSION}) against Hive ${SHORT}`);
