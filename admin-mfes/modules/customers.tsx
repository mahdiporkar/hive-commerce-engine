// Customers: who has ordered from this store. Commerce knows customers by their Hive identity; profiles stay in Hive.
import {createReactMicroApp, type ReactMicroAppProps} from '@hive-platform/react';
import {api, Problem, Shell, t, Table, useLoad} from '../lib/kit';

function CustomersAdmin({context}: ReactMicroAppProps) {
  const {data, error, reload} = useLoad(() => api<any[]>('GET', '/v1/admin/customers'));
  return <Shell context={context} title="Customers">
    <Problem error={error} />
    <div className="row"><button className="secondary" onClick={() => reload()}>{t(context, 'refresh')}</button></div>
    {data ? <Table columns={['Hive identity', 'Orders', 'Last order']} empty={t(context, 'empty')} rows={data.map(c => [<code>{c.customerId}</code>, c.orders, c.lastOrderAt])} /> : null}
  </Shell>;
}

export default createReactMicroApp(CustomersAdmin);
