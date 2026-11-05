import {origin, store} from '@/lib/store';

export const dynamic = 'force-dynamic';

export function GET() {
  const hidden = store.locales.flatMap(l => ['cart', 'checkout', 'orders', 'library', 'sell'].map(p => `Disallow: /${l}/${p}`));
  return new Response(['User-agent: *', ...hidden, 'Disallow: /api/', 'Disallow: /auth/', `Sitemap: ${origin()}/sitemap.xml`, ''].join('\n'), {headers: {'Content-Type': 'text/plain'}});
}
