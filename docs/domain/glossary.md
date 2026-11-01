# Glossary — ubiquitous language

Code, APIs, tests and UI labels use these terms. Each term belongs to one context. The same English word in two contexts is two different concepts (see "Product").

**Persian terms are proposals and need review by a native-speaking domain expert** (status column). UI copy in fa/ar is owned by client repositories, but must map to these terms.

| Term (code) | فارسی | Context | Meaning | Status |
|---|---|---|---|---|
| Tenant | مستأجر (Tenant) | Store | Hive tenant of the signed-in identity; isolation boundary above stores | proposed |
| Store | فروشگاه | Store | One commercial business with its own catalog, currency and mode | proposed |
| SalesChannel | کانال فروش | Store | Web, mobile, B2B… where listings are published | proposed |
| CommerceMode | حالت تجاری | Store | `SINGLE_VENDOR` (تک‌فروشنده), `MULTI_VENDOR` (چندفروشنده), `HYBRID` (ترکیبی) | proposed |
| Category | دسته‌بندی | Catalog | Hierarchical classification with inherited attributes | proposed |
| AttributeDefinition | تعریف ویژگی | Catalog | Store-level reusable attribute (type, rules, options) | proposed |
| ProductType | نوع محصول | Catalog | Set of attributes and rules a product must satisfy | proposed |
| SchemaVersion | نسخهٔ طرح‌واره | Catalog | Immutable published version of a product type | proposed |
| Product (Catalog) | محصول | Catalog | Reusable description and identity of an item | proposed |
| ProductVariant | تنوع محصول | Catalog | Purchasable configuration of a product | proposed |
| Media | رسانه | Catalog | Images and video describing a product | proposed |
| Listing | عرضه (انتشار در فروشگاه) | Listing | Publication of a product in a store and channel | proposed |
| SellerOffer | پیشنهاد فروشنده | Listing | One seller's price, condition and fulfillment terms for a variant | proposed |
| ApprovalRequest | درخواست تأیید | Listing | Moderation request for a listing or offer | proposed |
| Seller | فروشنده | Seller | Party that sells; the internal seller represents the store owner | proposed |
| SellerMembership | عضویت فروشنده | Seller | A user's role in a seller (owner / staff + capabilities) | proposed |
| Verification | احراز فروشنده | Seller | Evidence that a seller is who it claims to be | proposed |
| PriceList | فهرست قیمت | Pricing | Price overrides by channel or customer group | proposed |
| Discount / Promotion | تخفیف / کمپین تخفیف | Pricing | Rule that reduces a price | proposed |
| Coupon | کد تخفیف | Pricing | Code that activates a promotion | proposed |
| PriceQuote | برآورد قیمت | Pricing | Server-calculated price for given lines at a moment | proposed |
| StockItem | موجودی کالا | Inventory | On-hand and reserved quantity of one offer | proposed |
| Reservation | رزرو موجودی | Inventory | Time-limited hold on stock for a checkout | proposed |
| UniqueItem | کالای تک | Inventory | Stock item that can never exceed quantity 1 (gemstone, handicraft) | proposed |
| Cart | سبد خرید | Checkout | Lines a visitor or customer intends to buy | proposed |
| Checkout | تکمیل خرید | Checkout | Process turning a cart into an order and a payment | proposed |
| CheckoutAttempt | تلاش تکمیل خرید | Checkout | One idempotent attempt; carries the idempotency key | proposed |
| Order | سفارش | Ordering | Customer's placed purchase | proposed |
| OrderLine | ردیف سفارش | Ordering | Immutable snapshot of what was bought, at what price, from whom | proposed |
| Product (Ordering) | — (snapshot inside OrderLine) | Ordering | Frozen copy of title, variant and attributes at order time | proposed |
| SellerOrder | سفارش فروشنده | Ordering | Part of an order fulfilled by one seller | proposed |
| Cancellation | لغو سفارش | Ordering | — | proposed |
| Return | مرجوعی | Ordering | Goods sent back by the customer | proposed |
| Payment | پرداخت | Payment | Money collected for an order through a provider | proposed |
| PaymentTransaction | تراکنش پرداخت | Payment | One provider interaction (initiate, capture, refund) | proposed |
| Refund | استرداد وجه | Payment | Money returned to the customer | proposed |
| WebhookInbox | صندوق اعلان‌های درگاه | Payment | Deduplicated store of provider callbacks | proposed |
| Fulfillment | پردازش و ارسال | Fulfillment | Work to deliver a seller order | proposed |
| Shipment | مرسوله | Fulfillment | A physical package with a carrier and tracking | proposed |
| DigitalAsset | دارایی دیجیتال | Digital Delivery | A protected file or text with versions | proposed |
| ContentVersion | نسخهٔ محتوا | Digital Delivery | Immutable version of an asset's content | proposed |
| License | مجوز استفاده | Digital Delivery | Terms (scope, updates, downloads) attached to a product | proposed |
| Entitlement | حق دسترسی | Digital Delivery | A customer's right to content, from a paid order line | proposed |
| DownloadGrant | مجوز دانلود | Digital Delivery | One short-lived signed URL issued | proposed |
| CommissionRule | قاعدهٔ کارمزد | Finance | Rate by store / category / seller / product with precedence | proposed |
| CommissionRecord | سابقهٔ کارمزد | Finance | Commission calculated for one seller order | proposed |
| LedgerAccount | حساب دفتر کل | Finance | Account in the double-entry ledger | proposed |
| JournalEntry | سند حسابداری | Finance | Balanced set of debits and credits; append-only | proposed |
| Settlement | تسویه | Finance | Closing of a seller's balance for a period | proposed |
| Payout | واریز به فروشنده | Finance | Money paid out to a seller | proposed |
| Money | مبلغ | shared VO | Amount + ISO-4217 currency | proposed |
| Toman / Rial | تومان / ریال | presentation | IRR is stored in rials; Toman = rial / 10, display only | proposed |
| IdempotencyKey | کلید یکتایی درخواست | Checkout, Payment | Key that makes a retried request safe | proposed |
