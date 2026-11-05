'use client';
import {useState} from 'react';
import {CommerceError, formatMoney, type Offer, type ProductDetail} from '@hive-commerce/storefront-sdk';
import {shop} from '@/lib/shop';
import {messagesFor} from '@/lib/messages';
import {attributeValue} from '@/lib/store';

export function Offers({detail, offers, locale}: {detail: ProductDetail; offers: Offer[]; locale: string}) {
  const m = messagesFor(locale);
  const [outcome, setOutcome] = useState<{ok: boolean; text: string} | null>(null);
  const [busy, setBusy] = useState(false);
  const variantName = (offer: Offer) => {
    const variant = detail.product.variants.find(v => v.id === offer.variantId);
    const attributes = (variant?.attributes ?? {}) as Record<string, unknown>;
    return Object.entries(attributes).map(([k, v]) => attributeValue(k, v, locale, m.yes, m.no)).join(' / ');
  };
  const add = async (offer: Offer) => {
    setBusy(true);
    setOutcome(null);
    try {
      await shop.ensureCart();
      await shop.addItem(offer.offerId);
      setOutcome({ok: true, text: m.added});
    } catch (e) {
      setOutcome({ok: false, text: e instanceof CommerceError && e.status === 409 ? m.outOfStock : m.tryAgain});
    } finally {
      setBusy(false);
    }
  };
  return (
    <aside className="panel" aria-label={m.sellerOffer}>
      {offers.length === 0 ? <p className="muted">{m.outOfStock}</p> : null}
      {offers.map(offer => {
        const soldOut = !offer.digital && offer.available <= 0;
        return (
          <div className="stack" key={offer.offerId} data-testid="offer">
            {variantName(offer) ? <strong>{variantName(offer)}</strong> : null}
            <span className="price">{formatMoney(offer.price, offer.currency, locale)}</span>
            {offer.uniqueItem ? <span className="muted">{m.unique}</span> : null}
            <button disabled={busy || soldOut} onClick={() => add(offer)}>{soldOut ? m.outOfStock : m.addToCart}</button>
          </div>
        );
      })}
      {outcome ? <p className={`notice ${outcome.ok ? 'ok' : 'error'}`} role="status">{outcome.text}{outcome.ok ? <> · <a href={`/${locale}/cart`}>{m.cart}</a></> : null}</p> : null}
    </aside>
  );
}
