// Store settings: commerce mode, what this deployment switched on, and the documents of the store's own extensions.
// The document editor knows no industry: each extension validates what it stores, and refuses what it does not recognize.
import {useState} from 'react';
import {createReactMicroApp, type ReactMicroAppProps} from '@hive-platform/react';
import {api, Outcome, Problem, Shell, t, Table, useAction, useLoad} from '../lib/kit';

function ExtensionDocuments({context}: ReactMicroAppProps) {
  const [form, setForm] = useState({extension: '', subjectType: 'product', subjectId: '', document: '{}'});
  const action = useAction();
  const path = () => `/v1/admin/extensions/${encodeURIComponent(form.extension)}/${encodeURIComponent(form.subjectType)}/${encodeURIComponent(form.subjectId)}`;
  const ready = form.extension && form.subjectType && form.subjectId;
  return <section><h3>Extension documents</h3>
    <div className="grid">
      <label>Extension<input name="extension-key" value={form.extension} onChange={e => setForm({...form, extension: e.target.value})} /></label>
      <label>Subject type<input name="subject-type" value={form.subjectType} onChange={e => setForm({...form, subjectType: e.target.value})} /></label>
      <label>Subject id<input name="subject-id" value={form.subjectId} onChange={e => setForm({...form, subjectId: e.target.value})} /></label>
    </div>
    <label>Document (JSON)<textarea name="document" rows={10} value={form.document} onChange={e => setForm({...form, document: e.target.value})} /></label>
    <div className="row">
      <button className="secondary" disabled={!ready || action.busy} onClick={() => action.run(async () => setForm({...form, document: JSON.stringify(await api('GET', path()), null, 2)}), 'Loaded')}>Load</button>
      <button disabled={!ready || action.busy} onClick={() => action.run(() => api('PUT', path(), JSON.parse(form.document)), 'Saved')}>{t(context, 'save')}</button>
    </div>
    <Outcome action={action} />
  </section>;
}

function SettingsAdmin(props: ReactMicroAppProps) {
  const {context} = props;
  const store = useLoad(() => api<any>('GET', '/v1/admin/store'));
  const capabilities = useLoad(() => api<Record<string, boolean>>('GET', '/v1/admin/store/capabilities'));
  const action = useAction(store.reload);
  const [mode, setMode] = useState('');
  return <Shell context={context} title="Store settings">
    <Problem error={store.error} />
    <Outcome action={action} />
    {store.data ? <section>
      <p>Store <code>{store.data.key}</code> · {store.data.currency} · {store.data.locales.join(', ')}</p>
      <label>Commerce mode<select name="mode" value={mode || store.data.mode} onChange={e => setMode(e.target.value)}>{['SINGLE_VENDOR', 'MULTI_VENDOR', 'HYBRID'].map(m => <option key={m}>{m}</option>)}</select></label>
      <div className="row"><button disabled={action.busy || !mode || mode === store.data.mode} onClick={() => action.run(() => api('POST', '/v1/admin/store/mode', {mode}), 'Mode changed')}>{t(context, 'save')}</button></div>
    </section> : null}
    {capabilities.data ? <section><h3>Capabilities</h3><p>Set in this store's deployment configuration.</p>
      <Table columns={['Capability', t(context, 'status')]} empty="" rows={Object.entries(capabilities.data).map(([name, on]) => [name, on ? 'On' : 'Off'])} />
    </section> : null}
    <ExtensionDocuments {...props} />
  </Shell>;
}

export default createReactMicroApp(SettingsAdmin);
