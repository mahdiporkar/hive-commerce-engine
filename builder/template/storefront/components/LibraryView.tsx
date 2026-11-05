'use client';
import {useEffect, useState} from 'react';
import {CommerceError, loginUrl} from '@hive-commerce/storefront-sdk';
import {shop} from '@/lib/shop';
import {messagesFor} from '@/lib/messages';

export function LibraryView({locale}: {locale: string}) {
  const m = messagesFor(locale);
  const [items, setItems] = useState<any[] | null>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'anonymous' | 'error'>('loading');
  const [opened, setOpened] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const load = () => shop.library().then(i => { setItems(i); setState('ready'); }, e => setState(e instanceof CommerceError && e.status === 401 ? 'anonymous' : 'error'));
  useEffect(() => { void load(); }, []);
  const open = async (entitlementId: string) => {
    setFailure(null);
    try {
      const delivery = await shop.download(entitlementId);
      if (delivery.url) window.location.assign(delivery.url);
      else setOpened(o => ({...o, [entitlementId]: delivery.content ?? ''}));
      await load();
    } catch (e) {
      setFailure(e instanceof CommerceError ? e.message : m.tryAgain);
    }
  };
  if (state === 'loading') return <p>{m.loading}</p>;
  if (state === 'anonymous') return <><p>{m.signInRequired}</p><a className="button" href={loginUrl(`/${locale}/library`)}>{m.signIn}</a></>;
  if (state === 'error') return <p className="notice error" role="alert">{m.tryAgain}</p>;
  if (!items?.length) return <p>{m.noLibrary}</p>;
  return (
    <ul className="lines">
      {failure ? <li className="notice error" role="alert">{failure}</li> : null}
      {items.map(item => (
        <li key={item.entitlementId} className="stack panel" data-testid="library-item">
          <strong>{item.title}</strong>
          {item.status === 'ACTIVE' ? <button data-testid="library-open" onClick={() => open(item.entitlementId)}>{m.download}</button> : <span className="notice error">{m.revoked}</span>}
          {item.downloadLimit ? <span className="muted">{item.downloadsUsed} / {item.downloadLimit} {m.downloadsLeft}</span> : null}
          {opened[item.entitlementId] !== undefined ? <><strong>{m.yourContent}</strong><pre className="protected" data-testid="library-content">{opened[item.entitlementId]}</pre></> : null}
        </li>
      ))}
    </ul>
  );
}
