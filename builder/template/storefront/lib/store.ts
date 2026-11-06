import storeJson from '@/config/store.json';
import taxonomyJson from '@/config/taxonomy.json';
import {localized, type Localized} from '@hive-commerce/storefront-sdk';

export const store = storeJson as {key: string; displayName: Localized; tagline?: Localized; locales: string[]; defaultLocale: string; currency: string; mode: string;
  capabilities: {physicalProducts: boolean; digitalProducts: boolean; sellerOnboarding: boolean; commissions: boolean; reviews?: boolean}; theme: Record<string, string | number>; domain: string};

export interface AttributeSpec {key: string; required?: boolean; variantDefining?: boolean}
export interface Attribute {key: string; type: string; labels: Localized; options?: {code: string; labels: Localized}[]; flags?: {filterable?: boolean}; constraints?: {units?: string[]; min?: number; max?: number; maxLength?: number}}
export const taxonomy = taxonomyJson as unknown as {attributes: Attribute[]; productTypes: {key: string; name: Localized; kind: string; attributes: AttributeSpec[]}[]; categories: {ref: string; slug: string; name: Localized}[]};

const attributes = new Map(taxonomy.attributes.map(a => [a.key, a]));
export const attribute = (key: string) => attributes.get(key);
export const attributeLabel = (key: string, locale: string) => localized(attributes.get(key)?.labels, locale) || key;

/** Renders a stored attribute value for people: option labels, measurements, ranges and booleans. */
export function attributeValue(key: string, value: unknown, locale: string, yes: string, no: string): string {
  const spec = attributes.get(key);
  const option = (code: unknown) => localized(spec?.options?.find(o => o.code === code)?.labels, locale) || String(code);
  if (Array.isArray(value)) return value.map(option).join('، ');
  if (typeof value === 'boolean') return value ? yes : no;
  if (value && typeof value === 'object') {
    const v = value as {value?: unknown; unit?: unknown; min?: unknown; max?: unknown};
    if (v.value !== undefined) return `${v.value} ${v.unit ?? ''}`.trim();
    if (v.min !== undefined) return `${v.min} – ${v.max}`;
    return JSON.stringify(value);
  }
  return spec?.options ? option(value) : String(value ?? '');
}

/** A product's attributes in the order the schema declares them, not the order storage returns. */
export function orderedAttributes(values: Record<string, unknown>): [string, unknown][] {
  const position = (key: string) => { const i = taxonomy.attributes.findIndex(a => a.key === key); return i < 0 ? Number.MAX_SAFE_INTEGER : i; };
  return Object.entries(values).sort(([a], [b]) => position(a) - position(b));
}

export const isLocale = (locale: string) => store.locales.includes(locale);
export const origin = () => process.env.STORE_ORIGIN ?? `https://${store.domain}`;
