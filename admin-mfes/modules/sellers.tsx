// Seller administration and listing moderation.
import {useState} from 'react';
import {createReactMicroApp, type ReactMicroAppProps} from '@hive-platform/react';
import {api, Outcome, Problem, Shell, t, Table, text, useAction, useLoad} from '../lib/kit';

const STATUSES = ['APPLIED', 'VERIFIED', 'ACTIVE', 'SUSPENDED'];
const NEXT: Record<string, [string, string][]> = {APPLIED: [['verify', 'Verify']], VERIFIED: [['approve', 'Approve']], ACTIVE: [['suspend', 'Suspend']], SUSPENDED: [['reinstate', 'Reinstate']]};

function SellersAdmin({context}: ReactMicroAppProps) {
  const [status, setStatus] = useState('APPLIED');
  const sellers = useLoad(() => api<any[]>('GET', `/v1/admin/sellers?status=${status}`), [status]);
  const listings = useLoad(() => api<any[]>('GET', '/v1/admin/listings'));
  const reviews = useLoad(() => api<any[]>('GET', '/v1/admin/reviews'));
  const action = useAction(() => Promise.all([sellers.reload(), listings.reload(), reviews.reload()]));
  const [reason, setReason] = useState('');
  return <Shell context={context} title="Sellers">
    <Outcome action={action} />
    <section><h3>Sellers</h3>
      <label>{t(context, 'status')}<select value={status} onChange={e => setStatus(e.target.value)}>{STATUSES.map(s => <option key={s}>{s}</option>)}</select></label>
      <Problem error={sellers.error} />
      <Table columns={['Name', 'Members', t(context, 'actions')]} empty={t(context, 'empty')} rows={(sellers.data ?? []).map(s => [text(context, s.displayName), s.members.length,
        <span className="row">{(NEXT[s.status] ?? []).map(([op, label]) => <button key={op} disabled={action.busy} onClick={() => action.run(() => api('POST', `/v1/admin/sellers/${s.sellerId}/${op}`), label)}>{label}</button>)}</span>])} />
    </section>
    <section><h3>Listings waiting for moderation</h3>
      <Problem error={listings.error} />
      <label>Rejection reason<input value={reason} onChange={e => setReason(e.target.value)} /></label>
      <Table columns={['Listing', 'Product', t(context, 'actions')]} empty={t(context, 'empty')} rows={(listings.data ?? []).map(l => [<code>{l.listingId}</code>, <code>{l.productId}</code>,
        <span className="row">
          <button disabled={action.busy} onClick={() => action.run(() => api('POST', `/v1/admin/listings/${l.listingId}/approve`), 'Approved and published')}>Approve</button>
          <button className="secondary" disabled={action.busy || !reason} onClick={() => action.run(() => api('POST', `/v1/admin/listings/${l.listingId}/reject`, {reason}), 'Rejected')}>Reject</button>
        </span>])} />
    </section>
    <section><h3>Reviews</h3>
      <Problem error={reviews.error} />
      <Table columns={['Rating', 'Review', t(context, 'status'), t(context, 'actions')]} empty={t(context, 'empty')} rows={(reviews.data ?? []).map(r => [r.rating, r.body, r.status,
        r.status === 'VISIBLE' ? <button className="secondary" disabled={action.busy} onClick={() => action.run(() => api('POST', `/v1/admin/reviews/${r.reviewId}/hide`), 'Hidden')}>Hide</button> : null])} />
    </section>
  </Shell>;
}

export default createReactMicroApp(SellersAdmin);
