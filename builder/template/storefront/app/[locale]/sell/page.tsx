import type {Metadata} from 'next';
import {notFound} from 'next/navigation';
import {catalog} from '@/lib/catalog';
import {messagesFor} from '@/lib/messages';
import {store} from '@/lib/store';
import {SellerWorkspace} from '@/components/SellerWorkspace';

type Props = {params: Promise<{locale: string}>};
export const metadata: Metadata = {robots: {index: false}};

export default async function SellPage({params}: Props) {
  const {locale} = await params;
  if (!store.capabilities.sellerOnboarding) notFound();
  return <><h1>{messagesFor(locale).sellTitle}</h1><SellerWorkspace locale={locale} categories={await catalog().categories()} /></>;
}
