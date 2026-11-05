import type {Metadata} from 'next';
import {messagesFor} from '@/lib/messages';
import {OrdersView} from '@/components/OrdersView';

type Props = {params: Promise<{locale: string}>};
export const metadata: Metadata = {robots: {index: false}};

export default async function OrdersPage({params}: Props) {
  const {locale} = await params;
  return <><h1>{messagesFor(locale).orders}</h1><OrdersView locale={locale} /></>;
}
