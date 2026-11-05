'use client';
import {useCallback, useEffect, useState} from 'react';
import {CommerceError, formatMoney, localized, loginUrl, type Category} from '@hive-commerce/storefront-sdk';
import {shop} from '@/lib/shop';
import {messagesFor} from '@/lib/messages';
import {attribute, attributeLabel, store, taxonomy, type AttributeSpec} from '@/lib/store';

function coerce(type: string | undefined, value: string): unknown {
  if (value === '') return undefined;
  if (type === 'INTEGER' || type === 'DECIMAL') return Number(value);
  if (type === 'BOOLEAN') return value === 'true';
  return value;
}

function Field({spec, locale, value, onChange}: {spec: AttributeSpec; locale: string; value: string; onChange: (v: string) => void}) {
  const a = attribute(spec.key);
  const m = messagesFor(locale);
  const label = `${attributeLabel(spec.key, locale)}${spec.required ? ' *' : ''}`;
  if (a?.options?.length) return <label>{label}<select name={spec.key} value={value} onChange={e => onChange(e.target.value)}><option value="" />{a.options.map(o => <option key={o.code} value={o.code}>{localized(o.labels, locale)}</option>)}</select></label>;
  if (a?.type === 'BOOLEAN') return <label>{label}<select name={spec.key} value={value} onChange={e => onChange(e.target.value)}><option value="" /><option value="true">{m.yes}</option><option value="false">{m.no}</option></select></label>;
  if (a?.type === 'LONG_TEXT') return <label>{label}<textarea name={spec.key} rows={3} value={value} onChange={e => onChange(e.target.value)} /></label>;
  const numeric = a?.type === 'INTEGER' || a?.type === 'DECIMAL';
  return <label>{label}<input name={spec.key} type={numeric ? 'number' : 'text'} step={a?.type === 'INTEGER' ? 1 : 'any'} value={value} onChange={e => onChange(e.target.value)} /></label>;
}

/** Seller pages: every rule (membership, capabilities, moderation, ownership) is enforced by the engine. */
export function SellerWorkspace({locale, categories}: {locale: string; categories: Category[]}) {
  const m = messagesFor(locale);
  const [state, setState] = useState<'loading' | 'anonymous' | 'ready' | 'error'>('loading');
  const [memberships, setMemberships] = useState<{sellerId: string; role: string}[]>([]);
  const [listings, setListings] = useState<any[]>([]);
  const [offers, setOffers] = useState<any[]>([]);
  const [name, setName] = useState('');
  const [typeKey, setTypeKey] = useState(taxonomy.productTypes[0]?.key ?? '');
  const [form, setForm] = useState({title: '', slug: '', sku: '', categoryId: ''});
  const [values, setValues] = useState<Record<string, string>>({});
  const [sale, setSale] = useState<Record<string, {slug: string; price: string; stock: string; content: string; preview: string; title: string}>>({});
  const [notice, setNotice] = useState<{ok: boolean; text: string} | null>(null);
  const sellerId = memberships[0]?.sellerId;

  const load = useCallback(async () => {
    const mine = await shop.sellerMe();
    setMemberships(mine);
    if (mine[0]) {
      const seller = shop.seller(mine[0].sellerId);
      const [l, o] = await Promise.all([seller.listings().catch(() => []), seller.offers().catch(() => [])]);
      setListings(l);
      setOffers(o);
    }
    setState('ready');
  }, []);
  useEffect(() => { load().catch(e => setState(e instanceof CommerceError && e.status === 401 ? 'anonymous' : 'error')); }, [load]);

  const run = async (work: () => Promise<unknown>, done: string) => {
    setNotice(null);
    try {
      await work();
      setNotice({ok: true, text: done});
      await load();
    } catch (e) {
      const c = e as CommerceError;
      setNotice({ok: false, text: c.violations?.length ? c.violations.map(v => `${v.field}: ${v.message}`).join(' · ') : c.message ?? m.tryAgain});
    }
  };

  if (state === 'loading') return <p>{m.loading}</p>;
  if (state === 'anonymous') return <><p>{m.signInRequired}</p><a className="button" href={loginUrl(`/${locale}/sell`)}>{m.signIn}</a></>;
  if (state === 'error') return <p className="notice error" role="alert">{m.tryAgain}</p>;
  const banner = notice ? <p className={`notice ${notice.ok ? 'ok' : 'error'}`} role="status" data-testid="seller-notice">{notice.text}</p> : null;

  if (!sellerId) return (
    <section className="panel" style={{maxInlineSize: 520}}>
      <h2>{m.applyTitle}</h2><p className="muted">{m.applyHint}</p>
      <label>{m.sellerName}<input name="seller-name" value={name} onChange={e => setName(e.target.value)} /></label>
      <button disabled={!name} onClick={() => run(() => shop.applyAsSeller({[locale]: name}), m.saved)}>{m.applyAction}</button>
      {banner}
    </section>
  );

  const seller = shop.seller(sellerId);
  const type = taxonomy.productTypes.find(t => t.key === typeKey);
  const submit = () => run(async () => {
    const product: Record<string, unknown> = {}, variant: Record<string, unknown> = {};
    for (const spec of type?.attributes ?? []) {
      const value = coerce(attribute(spec.key)?.type, values[spec.key] ?? '');
      if (value !== undefined) (spec.variantDefining ? variant : product)[spec.key] = value;
    }
    await seller.submit({productType: typeKey, categoryId: form.categoryId || null, title: {[locale]: form.title}, slug: form.slug, attributes: product, variants: [{sku: form.sku, attributes: variant}]});
    setForm({title: '', slug: '', sku: '', categoryId: ''});
    setValues({});
  }, m.waitingApproval);

  const patch = (id: string, change: Partial<(typeof sale)[string]>) => setSale(s => ({...s, [id]: {slug: '', price: '', stock: '', content: '', preview: '', title: '', ...s[id], ...change}}));
  const digitalType = (taxonomy.productTypes.some(t => t.kind === 'DIGITAL'));
  return (
    <div className="stack">
      {banner}
      <section className="panel">
        <h2>{m.yourListings}</h2>
        <ul className="lines">
          {listings.map(l => {
            const s = sale[l.listingId] ?? {slug: '', price: '', stock: '', content: '', preview: '', title: ''};
            return (
              <li key={l.listingId} className="stack" data-testid="seller-listing" data-status={l.status}>
                <span className="muted"><code>{l.productId}</code> · {m.status}: {l.status} {l.rejectionReason ? `(${l.rejectionReason})` : ''}</span>
                {digitalType ? <div className="stack">
                  <label>{m.productTitle}<input name="asset-title" value={s.title} onChange={e => patch(l.listingId, {title: e.target.value})} /></label>
                  <label>{m.contentText}<textarea name="asset-content" rows={4} value={s.content} onChange={e => patch(l.listingId, {content: e.target.value})} /></label>
                  <label>{m.previewText}<textarea name="asset-preview" rows={2} value={s.preview} onChange={e => patch(l.listingId, {preview: e.target.value})} /></label>
                  <button className="secondary" onClick={() => run(async () => {
                    const asset = await seller.createAsset({productId: l.productId, kind: 'TEXT', title: s.title});
                    await seller.setText(asset.assetId, s.content);
                    if (s.preview) await seller.setPreview(asset.assetId, s.preview);
                  }, m.saved)}>{m.saveContent}</button>
                </div> : null}
                {l.status === 'PUBLISHED' ? <div className="stack">
                  <label>{m.slug}<input name="offer-slug" value={s.slug} onChange={e => patch(l.listingId, {slug: e.target.value})} /></label>
                  <label>{m.price} ({store.currency})<input name="offer-price" type="number" min={0} value={s.price} onChange={e => patch(l.listingId, {price: e.target.value})} /></label>
                  {digitalType ? null : <label>{m.stock}<input name="offer-stock" type="number" min={0} value={s.stock} onChange={e => patch(l.listingId, {stock: e.target.value})} /></label>}
                  <button onClick={() => run(async () => {
                    const response = await fetch(`/${locale}/sell/variant/${encodeURIComponent(s.slug)}`);
                    if (!response.ok) throw new CommerceError(response.status, 'NOT_FOUND', m.notFound);
                    const {variantId} = await response.json();
                    await seller.createOffer({variantId, amount: Number(s.price), currency: store.currency, ...(digitalType ? {} : {quantity: Number(s.stock)})});
                  }, m.saved)}>{m.createOffer}</button>
                </div> : null}
              </li>
            );
          })}
        </ul>
        <ul className="lines">{offers.map(o => <li className="line" key={o.offerId} data-testid="seller-offer"><code>{o.offerId}</code><span>{formatMoney(o.price, o.currency, locale)}</span><span>{o.status}</span></li>)}</ul>
      </section>
      <section className="panel">
        <h2>{m.submitProduct}</h2>
        <label>{m.productType}<select name="product-type" value={typeKey} onChange={e => { setTypeKey(e.target.value); setValues({}); }}>{taxonomy.productTypes.map(t => <option key={t.key} value={t.key}>{localized(t.name, locale)}</option>)}</select></label>
        <label>{m.productTitle} *<input name="title" value={form.title} onChange={e => setForm({...form, title: e.target.value})} /></label>
        <label>{m.slug} *<input name="slug" value={form.slug} onChange={e => setForm({...form, slug: e.target.value})} /></label>
        <label>{m.sku} *<input name="sku" value={form.sku} onChange={e => setForm({...form, sku: e.target.value})} /></label>
        <label>{m.category}<select name="category" value={form.categoryId} onChange={e => setForm({...form, categoryId: e.target.value})}><option value="" />{categories.map(c => <option key={c.id} value={c.id}>{localized(c.name, locale)}</option>)}</select></label>
        {(type?.attributes ?? []).map(spec => <Field key={spec.key} spec={spec} locale={locale} value={values[spec.key] ?? ''} onChange={v => setValues(c => ({...c, [spec.key]: v}))} />)}
        <button data-testid="seller-submit" disabled={!form.title || !form.slug || !form.sku} onClick={submit}>{m.submit}</button>
      </section>
    </div>
  );
}
