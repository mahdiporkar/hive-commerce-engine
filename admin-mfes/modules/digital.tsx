// Digital asset administration for the store's own products: create, upload directly to storage, finalize, previews.
import {useEffect, useState} from 'react';
import {createReactMicroApp, type ReactMicroAppProps} from '@hive-platform/react';
import {api, Outcome, Shell, t, useAction} from '../lib/kit';

async function sha256(file: File) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('');
}

function DigitalAdmin({context}: ReactMicroAppProps) {
  const [form, setForm] = useState({productId: '', kind: 'FILE', title: '', downloadLimit: '', updatesIncluded: true});
  const [asset, setAsset] = useState<any>(null);
  const [content, setContent] = useState('');
  const [preview, setPreview] = useState('');
  const [entitlement, setEntitlement] = useState({id: '', reason: ''});
  const action = useAction();
  // Another module's selection fills the product here; the modules know each other only through this event.
  useEffect(() => context.events.subscribe<{productId: string}>('commerce:product-selected', event => setForm(f => ({...f, productId: event.payload.productId}))), [context.events]);
  const upload = (file: File) => action.run(async () => {
    const form = await api<any>('POST', `/v1/admin/assets/${asset.assetId}/uploads`, {contentType: file.type, size: file.size, sha256: await sha256(file)});
    // Directly to storage with the signed form: large files never pass through the BFF (ADR-0008).
    const body = new FormData();
    Object.entries(form.fields).forEach(([k, v]) => body.append(k, v as string));
    body.append('file', file);
    const stored = await fetch(form.url, {method: 'POST', body});
    if (!stored.ok) throw new Error(`Storage refused the upload (HTTP ${stored.status})`);
    setAsset(await api('POST', `/v1/admin/assets/${asset.assetId}/uploads/${form.uploadId}/finalize`));
  }, 'Uploaded and verified');
  return <Shell context={context} title="Digital assets">
    <Outcome action={action} />
    <section><h3>New asset</h3>
      <div className="grid">
        <label>Product id<input name="product-id" value={form.productId} onChange={e => setForm({...form, productId: e.target.value})} /></label>
        <label>Kind<select value={form.kind} onChange={e => setForm({...form, kind: e.target.value})}><option>FILE</option><option>TEXT</option></select></label>
        <label>Title<input value={form.title} onChange={e => setForm({...form, title: e.target.value})} /></label>
        <label>Download limit<input type="number" min={1} value={form.downloadLimit} onChange={e => setForm({...form, downloadLimit: e.target.value})} /></label>
        <label>Updates included<input type="checkbox" checked={form.updatesIncluded} onChange={e => setForm({...form, updatesIncluded: e.target.checked})} /></label>
      </div>
      <div className="row"><button disabled={action.busy} onClick={() => action.run(async () => setAsset(await api('POST', '/v1/admin/assets', {productId: form.productId, kind: form.kind, title: form.title,
        downloadLimit: form.downloadLimit ? Number(form.downloadLimit) : null, updatesIncluded: form.updatesIncluded})), 'Asset created')}>{t(context, 'create')}</button></div>
    </section>
    {asset ? <section><h3>{asset.title} <code>{asset.assetId}</code></h3>
      <p>Versions: {asset.versions.map((v: any) => `v${v.version} (${v.size} bytes, ${v.scanStatus})`).join(', ') || '—'}</p>
      {asset.kind === 'FILE' ? <label>File<input type="file" aria-label="file" onChange={e => e.target.files?.[0] && upload(e.target.files[0])} /></label> : <>
        <label>Protected text<textarea rows={6} value={content} onChange={e => setContent(e.target.value)} /></label>
        <div className="row"><button disabled={action.busy} onClick={() => action.run(async () => setAsset(await api('PUT', `/v1/admin/assets/${asset.assetId}/text`, {content})), 'New version saved')}>{t(context, 'save')}</button></div>
      </>}
      <label>Public preview<textarea rows={3} value={preview} onChange={e => setPreview(e.target.value)} /></label>
      <div className="row"><button className="secondary" disabled={action.busy} onClick={() => action.run(async () => setAsset(await api('PUT', `/v1/admin/assets/${asset.assetId}/preview`, {preview})), 'Preview saved')}>{t(context, 'save')}</button></div>
    </section> : null}
    <section><h3>Revoke an entitlement</h3>
      <div className="grid"><label>Entitlement id<input value={entitlement.id} onChange={e => setEntitlement({...entitlement, id: e.target.value})} /></label>
        <label>Reason<input value={entitlement.reason} onChange={e => setEntitlement({...entitlement, reason: e.target.value})} /></label></div>
      <div className="row"><button disabled={action.busy || !entitlement.id} onClick={() => action.run(() => api('POST', `/v1/admin/entitlements/${entitlement.id}/revoke`, {reason: entitlement.reason}), 'Revoked')}>Revoke</button></div>
    </section>
  </Shell>;
}

export default createReactMicroApp(DigitalAdmin);
