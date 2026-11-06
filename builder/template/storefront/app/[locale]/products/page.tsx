import type {Metadata} from 'next';
import {localized, type SearchQuery} from '@hive-commerce/storefront-sdk';
import {catalog} from '@/lib/catalog';
import {messagesFor} from '@/lib/messages';
import {attribute, attributeLabel, attributeValue, origin, store} from '@/lib/store';
import {ProductGrid} from '@/components/ProductGrid';

type Props = {params: Promise<{locale: string}>; searchParams: Promise<Record<string, string | string[] | undefined>>};
const one = (v: string | string[] | undefined) => Array.isArray(v) ? v[0] : v;

export async function generateMetadata({params, searchParams}: Props): Promise<Metadata> {
  const {locale} = await params;
  const selected = one((await searchParams).category);
  const category = selected ? (await catalog().categories()).find(c => c.id === selected) : undefined;
  return {title: localized(category?.seo?.title, locale) || localized(category?.name, locale) || messagesFor(locale).products, description: localized(category?.seo?.description, locale) || undefined, alternates: {canonical: `${origin()}/${locale}/products`, languages: Object.fromEntries(store.locales.map(l => [l, `${origin()}/${l}/products`]))}};
}

export default async function Products({params, searchParams}: Props) {
  const {locale} = await params;
  const query = await searchParams;
  const m = messagesFor(locale);
  const page = Math.max(0, Number(one(query.page) ?? 0) || 0);
  const filters: Record<string, string> = {};
  for (const [key, value] of Object.entries(query)) if (key.startsWith('f.') && one(value)) filters[key.slice(2)] = one(value)!;
  const search: SearchQuery = {q: one(query.q), category: one(query.category), page, size: 24, filters};
  const [categories, result] = await Promise.all([catalog().categories(), catalog().search(search)]);
  const facets = new Map<string, {value: string; count: number}[]>();
  for (const f of result.facets) facets.set(f.key, [...(facets.get(f.key) ?? []), f]);
  const link = (change: Record<string, string | undefined>) => {
    const next = new URLSearchParams();
    for (const [k, v] of Object.entries({...Object.fromEntries(Object.entries(query).map(([a, b]) => [a, one(b)])), page: undefined, ...change})) if (v) next.set(k, v);
    const text = next.toString();
    return `/${locale}/products${text ? `?${text}` : ''}`;
  };
  return (
    <>
      <h1>{m.products}</h1>
      <form action={`/${locale}/products`} role="search" className="stack" style={{marginBlockEnd: 16}}>
        <label>{m.search}<input type="search" name="q" defaultValue={search.q} placeholder={m.searchPlaceholder} /></label>
        {search.category ? <input type="hidden" name="category" value={search.category} /> : null}
      </form>
      <nav aria-label={m.categories}>
        <ul className="chips">{categories.map(c => <li key={c.id}><a aria-current={c.id === search.category ? 'true' : undefined} href={link({category: c.id === search.category ? undefined : c.id})}>{localized(c.name, locale)}</a></li>)}</ul>
      </nav>
      <div className="layout">
        <details className="panel filters" open={Object.keys(filters).length > 0 || undefined}>
          <summary>{m.filters}</summary>
          {[...facets].map(([key, values]) => attribute(key) ? (
            <section key={key}>
              <strong>{attributeLabel(key, locale)}</strong>
              <ul className="lines">{values.map(v => (
                <li key={v.value}><a aria-current={filters[key] === v.value ? 'true' : undefined} href={link({[`f.${key}`]: filters[key] === v.value ? undefined : v.value})}>
                  {filters[key] === v.value ? '✓ ' : ''}{attributeValue(key, v.value, locale, m.yes, m.no)} ({v.count})
                </a></li>))}</ul>
            </section>) : null)}
          {Object.keys(filters).length ? <a className="button secondary" href={`/${locale}/products`}>{m.clear}</a> : null}
        </details>
        <div>
          <p className="muted">{result.total} {m.results}</p>
          <ProductGrid items={result.items} locale={locale} empty={m.noResults} />
          <nav className="pager">
            {page > 0 ? <a className="button secondary" rel="prev" href={link({page: String(page - 1)})}>{m.previous}</a> : null}
            {(page + 1) * result.size < result.total ? <a className="button secondary" rel="next" href={link({page: String(page + 1)})}>{m.next}</a> : null}
          </nav>
        </div>
      </div>
    </>
  );
}
