// Store settings: commerce mode. Upgrading keeps all data; downgrading is refused while external sellers are active.
import {useState} from 'react';
import {createReactMicroApp, type ReactMicroAppProps} from '@hive-platform/react';
import {api, Outcome, Problem, Shell, t, useAction, useLoad} from '../lib/kit';

function SettingsAdmin({context}: ReactMicroAppProps) {
  const store = useLoad(() => api<any>('GET', '/v1/admin/store'));
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
  </Shell>;
}

export default createReactMicroApp(SettingsAdmin);
