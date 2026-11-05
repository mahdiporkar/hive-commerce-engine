import type {Metadata} from 'next';
import {PaymentReturn} from '@/components/PaymentReturn';

type Props = {params: Promise<{locale: string}>};
export const metadata: Metadata = {robots: {index: false}};

export default async function ReturnPage({params}: Props) {
  const {locale} = await params;
  return <PaymentReturn locale={locale} />;
}
