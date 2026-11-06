import type {Metadata} from 'next';
import type {ReactNode} from 'react';
import {notFound} from 'next/navigation';
import {directionOf, localized} from '@hive-commerce/storefront-sdk';
import {isLocale, store} from '@/lib/store';
import {catalog} from '@/lib/catalog';
import {messagesFor} from '@/lib/messages';
import {AccountLinks} from '@/components/AccountLinks';
import '../globals.css';

export const dynamic = 'force-dynamic';

export async function generateMetadata({params}: {params: Promise<{locale: string}>}): Promise<Metadata> {
  const {locale} = await params;
  const name = localized(store.displayName, locale);
  return {title: {default: name, template: `%s | ${name}`}, description: localized(store.tagline, locale) || undefined};
}

const t = store.theme;
const tokens = `:root{--accent:${t.accent};--accent-text:${t.accentText};--surface:${t.surface};--surface-alt:${t.surfaceAlt};--text:${t.text};--muted:${t.muted};--border:${t.border};--radius:${t.radius}px;${t.font ? `--font:${t.font};` : ''}}`;

export default async function LocaleLayout({children, params}: {children: ReactNode; params: Promise<{locale: string}>}) {
  const {locale} = await params;
  if (!isLocale(locale)) notFound();
  const m = messagesFor(locale);
  // Hive's context decides direction for its own locale. A store's other languages take the direction of their script.
  const hive = await catalog().context().catch(() => null);
  const direction = hive?.locale?.split('-')[0] === locale && hive.direction ? hive.direction.toLowerCase() as 'rtl' | 'ltr' : directionOf(locale);
  return (
    <html lang={locale} dir={direction}>
      <head><style dangerouslySetInnerHTML={{__html: tokens}} /></head>
      <body>
        <header className="site-header">
          <nav aria-label={m.home}>
            <a className="brand" href={`/${locale}`}>{localized(store.displayName, locale)}</a>
            <a href={`/${locale}/products`}>{m.products}</a>
            <a href={`/${locale}/cart`}>{m.cart}</a>
            <AccountLinks locale={locale} />
            <span className="locale-switch">{store.locales.filter(l => l !== locale).map(l => <a key={l} href={`/${l}`} hrefLang={l} lang={l}>{l.toUpperCase()}</a>)}</span>
          </nav>
        </header>
        <main>{children}</main>
        <footer className="site-footer">{localized(store.displayName, locale)}</footer>
      </body>
    </html>
  );
}
