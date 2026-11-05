import type {Metadata} from 'next';
import {messagesFor} from '@/lib/messages';
import {CartView} from '@/components/CartView';

type Props = {params: Promise<{locale: string}>};

export async function generateMetadata({params}: Props): Promise<Metadata> {
  const {locale} = await params;
  return {title: messagesFor(locale).cart, robots: {index: false}};
}

export default async function CartPage({params}: Props) {
  const {locale} = await params;
  return <><h1>{messagesFor(locale).cart}</h1><CartView locale={locale} /></>;
}
