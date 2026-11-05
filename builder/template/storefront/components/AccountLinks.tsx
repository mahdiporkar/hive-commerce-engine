'use client';
import {useEffect, useState} from 'react';
import {loginUrl} from '@hive-commerce/storefront-sdk';
import {shop} from '@/lib/shop';
import {messagesFor} from '@/lib/messages';
import {store} from '@/lib/store';

export function AccountLinks({locale}: {locale: string}) {
  const m = messagesFor(locale);
  const [identity, setIdentity] = useState<{id: string} | null | undefined>(undefined);
  useEffect(() => { shop.session().then(setIdentity, () => setIdentity(null)); }, []);
  return (
    <>
      <a href={`/${locale}/orders`}>{m.orders}</a>
      {store.capabilities.digitalProducts ? <a href={`/${locale}/library`}>{m.library}</a> : null}
      {store.capabilities.sellerOnboarding ? <a href={`/${locale}/sell`}>{m.sell}</a> : null}
      {identity === null ? <a data-testid="sign-in" href={loginUrl(`/${locale}`)} onClick={e => { e.currentTarget.href = loginUrl(window.location.pathname + window.location.search); }}>{m.signIn}</a> : null}
      {identity ? <span data-testid="signed-in" className="muted">✓</span> : null}
    </>
  );
}
