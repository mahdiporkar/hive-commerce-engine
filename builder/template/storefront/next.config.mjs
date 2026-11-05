import {readFileSync} from 'node:fs';

const store = JSON.parse(readFileSync(new URL('./config/store.json', import.meta.url), 'utf8'));

// In production the gateway routes these paths to Hive's BFF (deploy/gateway.conf.template). HIVE_BFF_URL at build time
// gives local runs the same single origin without a gateway.
const bff = process.env.STOREFRONT_LOCAL_BFF;

export default {
  output: process.env.STOREFRONT_STANDALONE === 'false' ? undefined : 'standalone',
  poweredByHeader: false,
  async redirects() {
    return [{source: '/', destination: `/${store.defaultLocale}`, permanent: false}];
  },
  async rewrites() {
    if (!bff) return [];
    return ['/api/:path*', '/auth/:path*', '/oauth2/:path*', '/login/:path*'].map(source => ({source, destination: `${bff}${source}`}));
  },
};
