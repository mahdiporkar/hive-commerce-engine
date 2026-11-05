import type {ProductDetail} from '@hive-commerce/storefront-sdk';

/** A store shows what its extensions publish here (`detail.extensions`). The default store has none. */
export function ProductExtras(_: {detail: ProductDetail; locale: string}) {
  return null;
}
