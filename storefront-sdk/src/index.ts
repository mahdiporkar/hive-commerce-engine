// Headless client for storefronts. Every decision stays on the server: this package only reaches Hive's BFF operations
// (master prompt §18.2) and shapes SEO output. Client repositories never reimplement cart, checkout or seller calls.

export const ROUTE = '/api/routes/commerce';

export type Localized = Record<string, string>;
export interface Card {listingId: string; productId: string; slug: string; title: Localized; categoryId: string | null; kind: string; attributes: Record<string, unknown>}
export interface Page {items: Card[]; total: number; page: number; size: number; facets: {key: string; value: string; count: number}[]}
export interface Offer {offerId: string; productId: string; variantId: string; sellerId: string; price: number; currency: string; condition: string; available: number; uniqueItem: boolean; digital: boolean; status: string}
export interface ProductDetail {product: Card & {variants: Record<string, unknown>[]}; offers: Offer[]; extensions: Record<string, Record<string, unknown>>}
export interface Category {id: string; slug: string; name: Localized; parentId: string | null; depth: number; position: number; attributes: string[]}
export interface Preview {kind: string; title: string; preview: string | null}
export interface CartLine {itemId: string; offerId: string; productId: string; productSlug: string; title: Localized; sku: string; unitPrice: number; currency: string; quantity: number; uniqueItem: boolean; purchasable: boolean}
export interface Cart {cartId: string; status: string; lines: CartLine[]; totals: {subtotal: number; tax: number; shipping: number; total: number; currency: string}}
export interface Checkout {checkoutId: string; state: string; orderId: string | null; orderNumber: string | null; total: number | null; currency: string | null; paymentId: string | null; redirectUrl: string | null; failureCode: string | null}
export interface SearchQuery {q?: string; category?: string; sort?: string; page?: number; size?: number; filters?: Record<string, string | string[]>; min?: Record<string, number>; max?: Record<string, number>}

export class CommerceError extends Error {
  constructor(public status: number, public code: string, message: string, public violations: {field: string; message: string}[] = []) { super(message); }
}

type Fetch = typeof fetch;

async function read<T>(response: Response): Promise<T> {
  const text = await response.text();
  let data: any = null;
  if (text) { try { data = JSON.parse(text); } catch { data = {message: text}; } }
  if (!response.ok) throw new CommerceError(response.status, data?.code ?? 'ERROR', data?.message ?? `HTTP ${response.status}`, data?.violations ?? []);
  return data as T;
}

export function searchParams(query: SearchQuery = {}): string {
  const out = new URLSearchParams();
  for (const key of ['q', 'category', 'sort', 'page', 'size'] as const) if (query[key] !== undefined && query[key] !== '') out.set(key, String(query[key]));
  for (const [key, value] of Object.entries(query.filters ?? {})) out.set(`f.${key}`, Array.isArray(value) ? value.join(',') : value);
  for (const [key, value] of Object.entries(query.min ?? {})) out.set(`min.${key}`, String(value));
  for (const [key, value] of Object.entries(query.max ?? {})) out.set(`max.${key}`, String(value));
  const text = out.toString();
  return text ? `?${text}` : '';
}

/**
 * Public catalog for server rendering, PUBLIC operations only. It never sends cookies (§18.2), so a rendered page cannot
 * depend on who asked for it and stays cacheable.
 */
export function publicCatalog({bffOrigin, fetchImpl = (...a) => fetch(...a), init = {}}: {bffOrigin: string; fetchImpl?: Fetch; init?: RequestInit}) {
  const get = <T>(path: string) => fetchImpl(`${bffOrigin}${ROUTE}${path}`, {...init, credentials: 'omit', headers: {Accept: 'application/json'}}).then(r => read<T>(r));
  return {
    categories: () => get<Category[]>('/v1/store/categories'),
    search: (query?: SearchQuery) => get<Page>(`/v1/store/products${searchParams(query)}`),
    product: (slug: string) => get<ProductDetail>(`/v1/store/products/${encodeURIComponent(slug)}`),
    previews: (productId: string) => get<Preview[]>(`/v1/store/products/${encodeURIComponent(productId)}/previews`),
  };
}

export interface TokenStore {get(): string | null; set(token: string): void; clear(): void}

export function browserTokenStore(key = 'hc.cart'): TokenStore {
  return {
    get: () => { try { return localStorage.getItem(key); } catch { return null; } },
    set: token => { try { localStorage.setItem(key, token); } catch { /* a private window keeps the cart for this page only */ } },
    clear: () => { try { localStorage.removeItem(key); } catch { /* nothing stored */ } },
  };
}

/** Same-origin browser client: the session cookie and CSRF token belong to the BFF, the page never sees a bearer token. */
export function createShop({origin = '', fetchImpl = (...a) => fetch(...a), tokens = browserTokenStore()}: {origin?: string; fetchImpl?: Fetch; tokens?: TokenStore} = {}) {
  let csrf: Record<string, string> | null = null;
  const send = async <T>(method: string, path: string, body?: unknown): Promise<T> => {
    const unsafe = method !== 'GET';
    if (unsafe && !csrf) {
      const token = await read<{headerName: string; token: string}>(await fetchImpl(`${origin}/auth/csrf`, {credentials: 'same-origin'}));
      csrf = {[token.headerName]: token.token};
    }
    const response = await fetchImpl(`${origin}${ROUTE}${path}`, {method, credentials: 'same-origin',
      headers: {Accept: 'application/json', ...(body === undefined ? {} : {'Content-Type': 'application/json'}), ...(unsafe ? csrf! : {})},
      ...(body === undefined ? {} : {body: JSON.stringify(body)})});
    if (response.status === 403) csrf = null; // the token may have rotated
    return read<T>(response);
  };
  const withToken = (path: string) => { const token = tokens.get(); return token ? `${path}?cart=${encodeURIComponent(token)}` : path; };

  const seller = (sellerId: string) => {
    const base = `/v1/seller/sellers/${encodeURIComponent(sellerId)}`;
    return {
      submit: (draft: {productType: string; categoryId?: string | null; title: Localized; slug: string; attributes: Record<string, unknown>; variants: {sku: string; attributes: Record<string, unknown>}[]}) => send<any>('POST', `${base}/submissions`, draft),
      listings: () => send<any[]>('GET', `${base}/listings`),
      createOffer: (offer: {variantId: string; amount: number; currency: string; quantity?: number | null; condition?: string; sellerSku?: string; uniqueItem?: boolean}) => send<Offer>('POST', `${base}/offers`, offer),
      offers: () => send<Offer[]>('GET', `${base}/offers`),
      orders: () => send<any[]>('GET', `${base}/orders`),
      fulfillments: () => send<any[]>('GET', `${base}/fulfillments`),
      finance: () => send<any>('GET', `${base}/finance`),
      createAsset: (asset: {productId: string; kind: 'FILE' | 'TEXT'; title: string; downloadLimit?: number | null; updatesIncluded?: boolean; licenseUsage?: string}) => send<any>('POST', `${base}/assets`, asset),
      setText: (assetId: string, content: string) => send<any>('PUT', `/v1/seller/assets/${assetId}/text`, {content}),
      setPreview: (assetId: string, preview: string) => send<any>('PUT', `/v1/seller/assets/${assetId}/preview`, {preview}),
    };
  };

  return {
    session: async () => {
      const response = await fetchImpl(`${origin}/api/me/session`, {credentials: 'same-origin', headers: {Accept: 'application/json'}});
      if (!response.ok) return null;
      const session = await response.json();
      return session?.identity ?? null;
    },
    cart: async (): Promise<Cart | null> => {
      try { return await send<Cart>('GET', withToken('/v1/shop/carts/current')); } catch (e) {
        if (e instanceof CommerceError && (e.status === 404 || e.status === 401)) return null;
        throw e;
      }
    },
    /** Creates the cart when needed; on sign-in, posting the guest token merges the guest cart into the customer's. */
    ensureCart: async (): Promise<Cart> => {
      const created = await send<{cartToken: string | null; cart: Cart}>('POST', withToken('/v1/shop/carts'));
      if (created.cartToken) tokens.set(created.cartToken); else tokens.clear();
      return created.cart;
    },
    addItem: (offerId: string, quantity = 1) => send<Cart>('POST', withToken('/v1/shop/carts/current/items'), {offerId, quantity}),
    removeItem: (itemId: string) => send<Cart>('DELETE', withToken(`/v1/shop/carts/current/items/${itemId}`)),
    checkout: (idempotencyKey: string) => send<Checkout>('POST', '/v1/shop/checkouts', {idempotencyKey}),
    attempt: (checkoutId: string) => send<Checkout>('GET', `/v1/shop/checkouts/${checkoutId}`),
    verifyPayment: (paymentId: string) => send<any>('POST', `/v1/shop/payments/${paymentId}/verify`),
    orders: () => send<any[]>('GET', '/v1/shop/orders'),
    order: (orderId: string) => send<any>('GET', `/v1/shop/orders/${orderId}`),
    library: () => send<any[]>('GET', '/v1/shop/library'),
    download: (entitlementId: string) => send<{url: string | null; expiresAt: string | null; content: string | null; mimeType: string | null; version: number}>('POST', `/v1/shop/library/${entitlementId}/downloads`),
    sellerMe: () => send<any>('GET', '/v1/seller/me'),
    applyAsSeller: (displayName: Localized) => send<any>('POST', '/v1/seller/applications', {displayName}),
    seller,
  };
}

export const loginUrl = (returnUrl: string) => `/auth/login?returnUrl=${encodeURIComponent(returnUrl)}`;
export const directionOf = (locale: string): 'rtl' | 'ltr' => ['fa', 'ar', 'he', 'ur'].includes(locale.split('-')[0]) ? 'rtl' : 'ltr';
export const localized = (values: Localized | null | undefined, locale: string, fallback = 'en') =>
  !values ? '' : values[locale] ?? values[locale.split('-')[0]] ?? values[fallback] ?? Object.values(values)[0] ?? '';

export function formatMoney(amount: number | null | undefined, currency: string | null | undefined, locale: string) {
  if (amount === null || amount === undefined) return '';
  try {
    return new Intl.NumberFormat(locale, {style: 'currency', currency: currency ?? 'USD', currencyDisplay: 'name'}).format(amount);
  } catch {
    return `${amount} ${currency ?? ''}`.trim();
  }
}

/** schema.org Product with one Offer per purchasable offer. Only what the catalog states: no ratings, no invented claims. */
export function productJsonLd(detail: ProductDetail, {url, locale, description, image}: {url: string; locale: string; description?: string; image?: string}) {
  const {product, offers} = detail;
  return {
    '@context': 'https://schema.org', '@type': 'Product', name: localized(product.title, locale), url, sku: product.productId,
    ...(description ? {description} : {}), ...(image ? {image} : {}),
    offers: offers.map(o => ({
      '@type': 'Offer', price: String(o.price), priceCurrency: o.currency, url,
      availability: o.digital || o.available > 0 ? 'https://schema.org/InStock' : 'https://schema.org/OutOfStock',
      ...(o.digital ? {} : {itemCondition: o.condition === 'USED' ? 'https://schema.org/UsedCondition' : 'https://schema.org/NewCondition'}),
    })),
  };
}

export function breadcrumbJsonLd(items: {name: string; url: string}[]) {
  return {'@context': 'https://schema.org', '@type': 'BreadcrumbList', itemListElement: items.map((item, i) => ({'@type': 'ListItem', position: i + 1, name: item.name, item: item.url}))};
}

const xml = (value: string) => value.replace(/[<>&'"]/g, c => ({'<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;'}[c]!));
export const sitemapXml = (urls: string[]) => `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.map(u => `  <url><loc>${xml(u)}</loc></url>`).join('\n')}\n</urlset>\n`;

/** Every listed product, page by page, for the sitemap. */
export async function allProducts(catalog: ReturnType<typeof publicCatalog>, size = 100): Promise<Card[]> {
  const out: Card[] = [];
  for (let page = 0; ; page++) {
    const result = await catalog.search({page, size});
    out.push(...result.items);
    if (out.length >= result.total || !result.items.length) return out;
  }
}
