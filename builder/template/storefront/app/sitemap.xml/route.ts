import {allProducts, sitemapXml} from '@hive-commerce/storefront-sdk';
import {catalog} from '@/lib/catalog';
import {origin, store} from '@/lib/store';

export const dynamic = 'force-dynamic';

export async function GET() {
  const products = await allProducts(catalog());
  const urls = store.locales.flatMap(locale => [`${origin()}/${locale}`, `${origin()}/${locale}/products`, ...products.map(p => `${origin()}/${locale}/products/${p.slug}`)]);
  return new Response(sitemapXml(urls), {headers: {'Content-Type': 'application/xml; charset=utf-8'}});
}
