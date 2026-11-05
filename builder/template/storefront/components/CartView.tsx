'use client';
import {useCallback, useEffect, useState} from 'react';
import {formatMoney, localized, loginUrl, type Cart} from '@hive-commerce/storefront-sdk';
import {shop} from '@/lib/shop';
import {messagesFor} from '@/lib/messages';

export function CartView({locale}: {locale: string}) {
  const m = messagesFor(locale);
  const [cart, setCart] = useState<Cart | null | undefined>(undefined);
  const [identity, setIdentity] = useState<unknown>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const who = await shop.session();
    setIdentity(who);
    // After signing in, this merges the guest cart into the customer's cart.
    setCart(who ? await shop.ensureCart() : await shop.cart());
  }, []);
  useEffect(() => { load().catch(() => { setCart(null); setError(m.tryAgain); }); }, [load, m.tryAgain]);

  const remove = async (itemId: string) => {
    setBusy(true);
    try { setCart(await shop.removeItem(itemId)); } catch { setError(m.tryAgain); } finally { setBusy(false); }
  };
  const checkout = async () => {
    setBusy(true);
    setError(null);
    try {
      const result = await shop.checkout(`checkout-${crypto.randomUUID()}`);
      if (result.redirectUrl && result.paymentId) {
        sessionStorage.setItem('hc.payment', JSON.stringify({paymentId: result.paymentId, checkoutId: result.checkoutId, orderId: result.orderId}));
        window.location.assign(result.redirectUrl);
        return;
      }
      setError(result.failureCode ? `${m.paymentFailed} (${result.failureCode})` : m.tryAgain);
    } catch {
      setError(m.tryAgain);
      await load().catch(() => undefined);
    }
    setBusy(false);
  };

  if (cart === undefined) return <p>{m.loading}</p>;
  if (!cart || cart.lines.length === 0) return <>{error ? <p className="notice error" role="alert">{error}</p> : null}<p>{m.emptyCart}</p><a className="button" href={`/${locale}/products`}>{m.products}</a></>;
  const c = cart.totals.currency;
  return (
    <div className="stack">
      <ul className="lines">
        {cart.lines.map(line => (
          <li className="line" key={line.itemId} data-testid="cart-line">
            <a href={`/${locale}/products/${line.productSlug}`}>{localized(line.title, locale)}</a>
            <span>{line.quantity} × {formatMoney(line.unitPrice, line.currency, locale)}</span>
            {line.purchasable ? null : <span className="notice error">{m.unavailable}</span>}
            <button className="secondary" disabled={busy} onClick={() => remove(line.itemId)}>{m.remove}</button>
          </li>
        ))}
      </ul>
      <dl className="totals panel">
        <dt>{m.subtotal}</dt><dd>{formatMoney(cart.totals.subtotal, c, locale)}</dd>
        <dt>{m.tax}</dt><dd>{formatMoney(cart.totals.tax, c, locale)}</dd>
        <dt>{m.shipping}</dt><dd>{formatMoney(cart.totals.shipping, c, locale)}</dd>
        <dt className="grand">{m.total}</dt><dd className="grand" data-testid="cart-total">{formatMoney(cart.totals.total, c, locale)}</dd>
      </dl>
      {error ? <p className="notice error" role="alert">{error}</p> : null}
      {identity ? <button data-testid="checkout" disabled={busy} onClick={checkout}>{m.checkout}</button> : <a className="button" data-testid="sign-in-to-checkout" href={loginUrl(`/${locale}/cart`)}>{m.signInToCheckout}</a>}
    </div>
  );
}
