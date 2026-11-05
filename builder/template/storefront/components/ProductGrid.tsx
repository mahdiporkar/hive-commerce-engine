import {localized, type Card} from '@hive-commerce/storefront-sdk';
import {attributeLabel, attributeValue} from '@/lib/store';
import {messagesFor} from '@/lib/messages';

export function ProductGrid({items, locale, empty}: {items: Card[]; locale: string; empty: string}) {
  const m = messagesFor(locale);
  if (!items.length) return <p className="muted">{empty}</p>;
  return (
    <ul className="grid">
      {items.map(item => (
        <li key={item.listingId}>
          <a className="card" href={`/${locale}/products/${item.slug}`}>
            <h3>{localized(item.title, locale)}</h3>
            <dl>{Object.entries(item.attributes).slice(0, 2).map(([key, value]) => <div key={key}>{attributeLabel(key, locale)}: {attributeValue(key, value, locale, m.yes, m.no)}</div>)}</dl>
          </a>
        </li>
      ))}
    </ul>
  );
}
