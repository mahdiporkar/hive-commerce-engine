'use client';
import {useState} from 'react';
import {CommerceError, type ProductReviews} from '@hive-commerce/storefront-sdk';
import {shop} from '@/lib/shop';
import {messagesFor} from '@/lib/messages';

/** Reviews come only from customers who paid for the product, and the engine decides that. */
export function Reviews({productId, reviews, locale}: {productId: string; reviews: ProductReviews; locale: string}) {
  const m = messagesFor(locale);
  const [rating, setRating] = useState(5);
  const [body, setBody] = useState('');
  const [notice, setNotice] = useState<{ok: boolean; text: string} | null>(null);
  const send = async () => {
    setNotice(null);
    try {
      await shop.writeReview(productId, rating, body);
      setBody('');
      setNotice({ok: true, text: m.reviewThanks});
    } catch (e) {
      const code = e instanceof CommerceError ? e.code : '';
      setNotice({ok: false, text: code === 'REVIEW_NEEDS_PURCHASE' || (e instanceof CommerceError && e.status === 401) ? m.reviewNeedsPurchase : code === 'REVIEW_EXISTS' ? m.reviewExists : m.tryAgain});
    }
  };
  return (
    <section className="panel" data-testid="reviews" data-count={reviews.count}>
      <h2>{m.reviews}{reviews.averageRating ? <> · <span className="stars" aria-label={`${reviews.averageRating} / 5`}>★ {reviews.averageRating}</span> ({reviews.count})</> : null}</h2>
      {reviews.count === 0 ? <p className="muted">{m.noReviews}</p> : null}
      <ul className="lines">{reviews.reviews.map((r, i) => <li key={i} className="stack" data-testid="review"><span className="stars" aria-label={`${r.rating} / 5`}>{'★'.repeat(r.rating)}</span><bdi>{r.body}</bdi></li>)}</ul>
      <details>
        <summary>{m.writeReview}</summary>
        <div className="stack">
          <label>{m.rating}<select name="rating" value={rating} onChange={e => setRating(Number(e.target.value))}>{[5, 4, 3, 2, 1].map(n => <option key={n} value={n}>{n}</option>)}</select></label>
          <label>{m.reviewText}<textarea name="review" rows={3} maxLength={2000} value={body} onChange={e => setBody(e.target.value)} /></label>
          <button data-testid="review-send" disabled={!body.trim()} onClick={send}>{m.sendReview}</button>
          {notice ? <p className={`notice ${notice.ok ? 'ok' : 'error'}`} role="status" data-testid="review-notice">{notice.text}</p> : null}
        </div>
      </details>
    </section>
  );
}
