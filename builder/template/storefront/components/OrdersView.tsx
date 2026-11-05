'use client';
import {useEffect, useState} from 'react';
import {CommerceError, formatMoney, localized, loginUrl} from '@hive-commerce/storefront-sdk';
import {shop} from '@/lib/shop';
import {messagesFor} from '@/lib/messages';

export function OrdersView({locale}: {locale: string}) {
  const m = messagesFor(locale);
  const [orders, setOrders] = useState<any[] | null>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'anonymous' | 'error'>('loading');
  useEffect(() => {
    shop.orders().then(o => { setOrders(o); setState('ready'); }, e => setState(e instanceof CommerceError && e.status === 401 ? 'anonymous' : 'error'));
  }, []);
  if (state === 'loading') return <p>{m.loading}</p>;
  if (state === 'anonymous') return <><p>{m.signInRequired}</p><a className="button" href={loginUrl(`/${locale}/orders`)}>{m.signIn}</a></>;
  if (state === 'error') return <p className="notice error" role="alert">{m.tryAgain}</p>;
  if (!orders?.length) return <p>{m.noOrders}</p>;
  return (
    <ul className="lines">
      {orders.map(order => (
        <li className="line" key={order.orderId} data-testid="order" data-status={order.status}>
          <strong>{order.number}</strong>
          <span>{order.sellerOrders?.flatMap((s: any) => s.lines).map((l: any) => localized(l.title, locale)).join('، ')}</span>
          <span>{formatMoney(order.total, order.currency, locale)}</span>
          <span className="muted">{m.status}: {order.status}</span>
        </li>
      ))}
    </ul>
  );
}
