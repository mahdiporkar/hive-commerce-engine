import {catalog} from '@/lib/catalog';

export const dynamic = 'force-dynamic';

/** Resolves a listed product's first variant from public data, for the seller's offer form. Grants nothing. */
export async function GET(_: Request, {params}: {params: Promise<{slug: string}>}) {
  const {slug} = await params;
  try {
    const {product} = await catalog().product(slug);
    return Response.json({variantId: product.variants[0]?.variantId});
  } catch {
    return Response.json({code: 'NOT_FOUND'}, {status: 404});
  }
}
