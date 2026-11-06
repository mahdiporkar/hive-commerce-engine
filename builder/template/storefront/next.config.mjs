import {readFileSync} from 'node:fs';

const store = JSON.parse(readFileSync(new URL('./config/store.json', import.meta.url), 'utf8'));

// The gateway routes /api, /auth, /oauth2 and /login to Hive's BFF on this same origin (deploy/gateway.conf.template).

export default {
  output: process.env.STOREFRONT_STANDALONE === 'false' ? undefined : 'standalone',
  poweredByHeader: false,
  async redirects() {
    return [{source: '/', destination: `/${store.defaultLocale}`, permanent: false}];
  },
};
