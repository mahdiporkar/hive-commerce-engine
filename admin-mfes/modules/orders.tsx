// Order administration: store orders, fulfillment and refunds.
import {useState} from 'react';
import {createReactMicroApp, type ReactMicroAppProps} from '@hive-platform/react';
import {api, money, Outcome, Problem, Shell, t, Table, text, useAction, useLoad} from '../lib/kit';

function OrderDetail({context, orderId, onChange}: ReactMicroAppProps & {orderId: string; onChange: () => unknown}) {
  const {data, error, reload} = useLoad(async () => {
    const [order, fulfillments] = await Promise.all([api<any>('GET', `/v1/admin/orders/${orderId}`), api<any[]>('GET', `/v1/admin/orders/${orderId}/fulfillments`)]);
    return {order, fulfillments};
  }, [orderId]);
  const action = useAction(() => { onChange(); return reload(); });
  const [ship, setShip] = useState({carrier: '', trackingCode: ''});
  const [refund, setRefund] = useState({amount: '', lines: {} as Record<string, boolean>});
  if (error) return <Problem error={error} />;
  if (!data) return <p>{t(context, 'loading')}</p>;
  const {order, fulfillments} = data;
  const lines = order.sellerOrders.flatMap((so: any) => so.lines);
  return <section><h3>{order.number} — {order.status}</h3>
    <Table columns={['Item', 'SKU', 'Qty', 'Total', 'Refund']} empty="" rows={lines.map((l: any) => [text(context, l.title), <code>{l.sku}</code>, l.quantity, money(l.lineTotal, order.currency),
      <input type="checkbox" aria-label={`refund ${l.sku}`} checked={!!refund.lines[l.lineId]} onChange={e => setRefund(r => ({...r, lines: {...r.lines, [l.lineId]: e.target.checked}}))} />])} />
    <div className="row">
      <input aria-label="refund amount" placeholder="Refund amount" value={refund.amount} onChange={e => setRefund({...refund, amount: e.target.value})} />
      <button className="secondary" disabled={action.busy || !refund.amount} onClick={() => action.run(() => api('POST', `/v1/admin/orders/${orderId}/refunds`, {idempotencyKey: `refund-${crypto.randomUUID()}`,
        amount: refund.amount, orderLineIds: Object.keys(refund.lines).filter(k => refund.lines[k])}), 'Refund completed')}>Refund</button>
    </div>
    <h3>Fulfillment</h3>
    <Table columns={[t(context, 'status'), 'Carrier', 'Tracking', t(context, 'actions')]} empty={t(context, 'empty')} rows={fulfillments.map((f: any) => [f.status, f.carrier ?? '', f.trackingCode ?? '',
      f.status === 'PENDING' ? <span className="row">
        <input aria-label="carrier" placeholder="Carrier" value={ship.carrier} onChange={e => setShip({...ship, carrier: e.target.value})} />
        <input aria-label="tracking" placeholder="Tracking" value={ship.trackingCode} onChange={e => setShip({...ship, trackingCode: e.target.value})} />
        <button onClick={() => action.run(() => api('POST', `/v1/admin/fulfillments/${f.fulfillmentId}/ship`, ship), 'Shipped')}>Ship</button></span>
        : f.status === 'SHIPPED' ? <button onClick={() => action.run(() => api('POST', `/v1/admin/fulfillments/${f.fulfillmentId}/deliver`), 'Delivered')}>Delivered</button> : null])} />
    <Outcome action={action} />
  </section>;
}

function OrdersAdmin({context}: ReactMicroAppProps) {
  const {data, error, reload} = useLoad(() => api<any[]>('GET', '/v1/admin/orders'));
  const [selected, setSelected] = useState<string | null>(null);
  return <Shell context={context} title="Orders">
    <Problem error={error} />
    <div className="row"><button className="secondary" onClick={() => reload()}>{t(context, 'refresh')}</button></div>
    {data ? <Table columns={['Number', t(context, 'status'), 'Total', t(context, 'actions')]} empty={t(context, 'empty')} rows={data.map(o => [<code>{o.number}</code>, o.status, money(o.total, o.currency),
      <button className="secondary" onClick={() => setSelected(o.orderId)}>Open</button>])} /> : null}
    {selected ? <OrderDetail context={context} orderId={selected} onChange={reload} /> : null}
  </Shell>;
}

export default createReactMicroApp(OrdersAdmin);
