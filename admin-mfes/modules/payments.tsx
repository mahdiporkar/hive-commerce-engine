// Payments: the latest payments as the server last verified them. Refunds are made from the order.
import {createReactMicroApp, type ReactMicroAppProps} from '@hive-platform/react';
import {api, money, Problem, Shell, t, Table, useLoad} from '../lib/kit';

function PaymentsAdmin({context}: ReactMicroAppProps) {
  const {data, error, reload} = useLoad(() => api<any[]>('GET', '/v1/admin/payments'));
  return <Shell context={context} title="Payments">
    <Problem error={error} />
    <div className="row"><button className="secondary" onClick={() => reload()}>{t(context, 'refresh')}</button></div>
    {data ? <Table columns={['Payment', 'Order', t(context, 'status'), 'Amount']} empty={t(context, 'empty')} rows={data.map(p => [<code>{p.paymentId}</code>, <code>{p.orderId}</code>, p.status, money(p.amount, p.currency)])} /> : null}
  </Shell>;
}

export default createReactMicroApp(PaymentsAdmin);
