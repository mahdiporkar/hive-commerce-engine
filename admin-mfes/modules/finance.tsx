// Finance administration: commission rules, ledger balances, settlements and payouts.
import {useState} from 'react';
import {createReactMicroApp, type ReactMicroAppProps} from '@hive-platform/react';
import {api, money, Outcome, Problem, Shell, t, Table, useAction, useLoad} from '../lib/kit';

function FinanceAdmin({context}: ReactMicroAppProps) {
  const [currency, setCurrency] = useState('IRR');
  const load = useLoad(async () => {
    const [balances, rules, settlements, payouts] = await Promise.all([api<any>('GET', `/v1/admin/finance/balances?currency=${currency}`), api<any[]>('GET', '/v1/admin/finance/commission-rules'),
      api<any[]>('GET', '/v1/admin/finance/settlements'), api<any[]>('GET', '/v1/admin/finance/payouts')]);
    return {balances, rules, settlements, payouts};
  }, [currency]);
  const action = useAction(load.reload);
  const [rule, setRule] = useState({level: 'STORE', targetId: '', ratePercent: ''});
  const [seller, setSeller] = useState('');
  const [note, setNote] = useState('');
  const d = load.data;
  return <Shell context={context} title="Finance">
    <Problem error={load.error} />
    <Outcome action={action} />
    <section><h3>Ledger balances</h3>
      <label>Currency<input value={currency} onChange={e => setCurrency(e.target.value.toUpperCase())} maxLength={3} /></label>
      {d ? <>
        <Table columns={['Account', 'Debit − credit']} empty={t(context, 'empty')} rows={Object.entries(d.balances.accounts).map(([a, b]) => [<code>{a}</code>, money(b, currency)])} />
        <p>Trial balance: <strong>{money(d.balances.sum, currency)}</strong></p>
      </> : null}
    </section>
    <section><h3>Commission rules</h3>
      <div className="grid">
        <label>Level<select value={rule.level} onChange={e => setRule({...rule, level: e.target.value})}>{['STORE', 'CATEGORY', 'SELLER', 'PRODUCT'].map(l => <option key={l}>{l}</option>)}</select></label>
        <label>Target id<input disabled={rule.level === 'STORE'} value={rule.targetId} onChange={e => setRule({...rule, targetId: e.target.value})} /></label>
        <label>Rate %<input type="number" min={0} max={100} step="any" value={rule.ratePercent} onChange={e => setRule({...rule, ratePercent: e.target.value})} /></label>
      </div>
      <div className="row"><button disabled={action.busy} onClick={() => action.run(() => api('POST', '/v1/admin/finance/commission-rules', {level: rule.level, targetId: rule.level === 'STORE' ? null : rule.targetId, ratePercent: rule.ratePercent}), 'Rule created')}>{t(context, 'create')}</button></div>
      {d ? <Table columns={['Level', 'Target', 'Rate %']} empty={t(context, 'empty')} rows={d.rules.map(r => [r.level, <code>{r.targetId ?? '—'}</code>, r.ratePercent])} /> : null}
    </section>
    <section><h3>Settlements</h3>
      <div className="row"><input aria-label="seller id" placeholder="Seller id" value={seller} onChange={e => setSeller(e.target.value)} />
        <button disabled={action.busy || !seller} onClick={() => action.run(() => api('POST', '/v1/admin/finance/settlements', {sellerId: seller}), 'Settlement closed')}>Close settlement</button></div>
      {d ? <Table columns={['Seller', 'Amount', 'Closed']} empty={t(context, 'empty')} rows={d.settlements.map(s => [<code>{s.sellerId}</code>, money(s.amount, s.currency), s.closedAt])} /> : null}
    </section>
    <section><h3>Payouts</h3>
      <label>Bank reference / rejection reason<input value={note} onChange={e => setNote(e.target.value)} /></label>
      {d ? <Table columns={['Seller', 'Amount', t(context, 'status'), t(context, 'actions')]} empty={t(context, 'empty')} rows={d.payouts.map(p => [<code>{p.sellerId}</code>, money(p.amount, p.currency), p.status,
        <span className="row">
          {p.status === 'REQUESTED' ? <><button disabled={action.busy} onClick={() => action.run(() => api('POST', `/v1/admin/finance/payouts/${p.payoutId}/approve`), 'Payout approved')}>Approve</button>
            <button className="secondary" disabled={action.busy} onClick={() => action.run(() => api('POST', `/v1/admin/finance/payouts/${p.payoutId}/reject`, {note}), 'Payout rejected')}>Reject</button></> : null}
          {p.status === 'APPROVED' ? <button disabled={action.busy || !note} onClick={() => action.run(() => api('POST', `/v1/admin/finance/payouts/${p.payoutId}/paid`, {note}), 'Marked paid')}>Mark paid</button> : null}
        </span>])} /> : null}
    </section>
  </Shell>;
}

export default createReactMicroApp(FinanceAdmin);
