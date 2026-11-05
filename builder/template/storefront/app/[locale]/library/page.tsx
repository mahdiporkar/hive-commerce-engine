import type {Metadata} from 'next';
import {notFound} from 'next/navigation';
import {messagesFor} from '@/lib/messages';
import {store} from '@/lib/store';
import {LibraryView} from '@/components/LibraryView';

type Props = {params: Promise<{locale: string}>};
export const metadata: Metadata = {robots: {index: false}};

export default async function LibraryPage({params}: Props) {
  const {locale} = await params;
  if (!store.capabilities.digitalProducts) notFound();
  return <><h1>{messagesFor(locale).library}</h1><LibraryView locale={locale} /></>;
}
