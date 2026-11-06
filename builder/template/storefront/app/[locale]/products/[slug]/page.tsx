import type {Metadata} from 'next';
import {breadcrumbJsonLd, localized, productJsonLd} from '@hive-commerce/storefront-sdk';
import {catalog, orNotFound} from '@/lib/catalog';
import {messagesFor} from '@/lib/messages';
import {attributeLabel, attributeValue, orderedAttributes, origin, store} from '@/lib/store';
import {Offers} from '@/components/Offers';
import {ProductExtras} from '@/components/ProductExtras';
import {Reviews} from '@/components/Reviews';

type Props = {params: Promise<{locale: string; slug: string}>};
const pageUrl = (locale: string, slug: string) => `${origin()}/${locale}/products/${slug}`;
// JSON-LD sits in a script element: "<" must not close it, whatever a seller typed into a title.
const safeJson = (value: unknown) => JSON.stringify(value).replace(/</g, '\\u003c');

export async function generateMetadata({params}: Props): Promise<Metadata> {
  const {locale, slug} = await params;
  const detail = await orNotFound(catalog().product(slug));
  const title = localized(detail.product.title, locale);
  return {title, alternates: {canonical: pageUrl(locale, slug), languages: Object.fromEntries(store.locales.map(l => [l, pageUrl(l, slug)]))}, openGraph: {title, url: pageUrl(locale, slug), type: 'website'}};
}

export default async function ProductPage({params}: Props) {
  const {locale, slug} = await params;
  const m = messagesFor(locale);
  const detail = await orNotFound(catalog().product(slug));
  const {product, offers} = detail;
  const previews = product.kind === 'DIGITAL' ? await catalog().previews(product.productId) : [];
  const reviews = store.capabilities.reviews ? await catalog().reviews(product.productId) : null;
  const title = localized(product.title, locale);
  const crumbs = [{name: m.home, url: `${origin()}/${locale}`}, {name: m.products, url: `${origin()}/${locale}/products`}, {name: title, url: pageUrl(locale, slug)}];
  return (
    <article>
      <script type="application/ld+json" dangerouslySetInnerHTML={{__html: safeJson(productJsonLd(detail, {url: pageUrl(locale, slug), locale}))}} />
      <script type="application/ld+json" dangerouslySetInnerHTML={{__html: safeJson(breadcrumbJsonLd(crumbs))}} />
      <nav aria-label="breadcrumb" className="muted"><a href={`/${locale}/products`}>{m.products}</a> / {title}</nav>
      <h1>{title}</h1>
      <div className="product">
        <div className="stack">
          {detail.media?.length ? <div className="gallery" data-testid="gallery">{detail.media.filter(i => i.kind === 'IMAGE').map(i => <img key={i.url} src={i.url} alt={localized(i.alt, locale) || title} loading="lazy" />)}</div> : null}
          <section className="panel">
            <h2>{m.details}</h2>
            <dl className="spec">
              {orderedAttributes(product.attributes).map(([key, value]) => (<div key={key} style={{display: 'contents'}}><dt>{attributeLabel(key, locale)}</dt><dd><bdi>{attributeValue(key, value, locale, m.yes, m.no)}</bdi></dd></div>))}
            </dl>
          </section>
          <ProductExtras detail={detail} locale={locale} />
          {previews.map((p, i) => p.preview ? <section className="panel" key={i}><h2>{m.preview}: {p.title}</h2><pre className="protected">{p.preview}</pre></section> : null)}
          {reviews ? <Reviews productId={product.productId} reviews={reviews} locale={locale} /> : null}
        </div>
        <Offers detail={detail} offers={offers} locale={locale} />
      </div>
    </article>
  );
}
