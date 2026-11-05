'use client';
import {useEffect, useState} from 'react';
import {shop} from '@/lib/shop';
import {messagesFor} from '@/lib/messages';

/** The return page never trusts the redirect: the server confirms the payment with the provider. */
export function PaymentReturn({locale}: {locale: string}) {
  const m = messagesFor(locale);
  const [state, setState] = useState<'checking' | 'paid' | 'failed'>('checking');
  useEffect(() => {
    (async () => {
      const pending = JSON.parse(sessionStorage.getItem('hc.payment') ?? 'null');
      if (!pending) { setState('failed'); return; }
      const payment = await shop.verifyPayment(pending.paymentId);
      setState(payment?.status === 'CAPTURED' || payment?.status === 'PAID' ? 'paid' : 'failed');
      sessionStorage.removeItem('hc.payment');
    })().catch(() => setState('failed'));
  }, []);
  if (state === 'checking') return <p role="status">{m.paying}</p>;
  return (
    <div className="stack">
      <p data-testid="payment-result" data-state={state} className={`notice ${state === 'paid' ? 'ok' : 'error'}`} role="status">{state === 'paid' ? m.paid : m.paymentFailed}</p>
      <a className="button" href={`/${locale}/orders`}>{m.viewOrders}</a>
    </div>
  );
}
