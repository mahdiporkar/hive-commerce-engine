import type {Metadata} from 'next';
import {localized} from '@hive-commerce/storefront-sdk';
import {catalog} from '@/lib/catalog';
import {messagesFor} from '@/lib/messages';
import {origin, store} from '@/lib/store';
import {ProductGrid} from '@/components/ProductGrid';

type Props = {params: Promise<{locale: string}>};

export async function generateMetadata({params}: Props): Promise<Metadata> {
  const {locale} = await params;
  return {alternates: {canonical: `${origin()}/${locale}`, languages: Object.fromEntries(store.locales.map(l => [l, `${origin()}/${l}`]))}};
}

export default async function Home({params}: Props) {
  const {locale} = await params;
  const m = messagesFor(locale);
  const [categories, latest] = await Promise.all([catalog().categories(), catalog().search({size: 8})]);
  return (
    <>
      <section className="hero">
        <h1>{localized(store.displayName, locale)}</h1>
        {store.tagline ? <p>{localized(store.tagline, locale)}</p> : null}
      </section>
      <nav aria-label={m.categories}>
        <ul className="chips">{categories.filter(c => c.depth === 1).map(c => <li key={c.id}><a href={`/${locale}/products?category=${c.id}`}>{localized(c.name, locale)}</a></li>)}</ul>
      </nav>
      <ProductGrid items={latest.items} locale={locale} empty={m.noResults} />
    </>
  );
}
