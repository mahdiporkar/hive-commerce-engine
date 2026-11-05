import {CommerceError, publicCatalog} from '@hive-commerce/storefront-sdk';
import {notFound} from 'next/navigation';

// Server rendering talks to Hive's BFF directly and sends no cookies: public pages never depend on who asks.
export const catalog = () => publicCatalog({bffOrigin: process.env.HIVE_BFF_URL ?? 'http://bff:8080', init: {cache: 'no-store'}});

export async function orNotFound<T>(load: Promise<T>): Promise<T> {
  try {
    return await load;
  } catch (e) {
    if (e instanceof CommerceError && e.status === 404) notFound();
    throw e;
  }
}
