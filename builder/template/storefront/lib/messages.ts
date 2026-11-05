// Interface wording of this storefront. Arabic and Persian need review by native speakers before launch.
const en = {
  home: 'Home', products: 'Products', cart: 'Cart', orders: 'My orders', library: 'My library', sell: 'Sell', signIn: 'Sign in', signOut: 'Sign out',
  search: 'Search', searchPlaceholder: 'Search products', categories: 'Categories', filters: 'Filters', apply: 'Apply', clear: 'Clear', noResults: 'Nothing matches this search.', results: 'results',
  addToCart: 'Add to cart', added: 'Added to your cart', outOfStock: 'Out of stock', available: 'available', unique: 'One of a kind', sellerOffer: 'Offer', details: 'Details', previous: 'Previous', next: 'Next',
  emptyCart: 'Your cart is empty.', remove: 'Remove', quantity: 'Quantity', subtotal: 'Subtotal', tax: 'Tax', shipping: 'Shipping', total: 'Total', checkout: 'Checkout', signInToCheckout: 'Sign in to check out', unavailable: 'No longer available',
  paying: 'Confirming your payment…', paid: 'Payment confirmed. Thank you!', paymentFailed: 'The payment did not complete. You have not been charged.', viewOrders: 'View my orders',
  noOrders: 'You have no orders yet.', order: 'Order', status: 'Status', signInRequired: 'Please sign in to see this page.',
  noLibrary: 'Your library is empty.', download: 'Get it', downloadsLeft: 'downloads used', revoked: 'Access revoked', yourContent: 'Your content',
  yes: 'Yes', no: 'No', preview: 'Preview', included: 'What you get', loading: 'Loading…', tryAgain: 'Something went wrong. Please try again.', notFound: 'This page does not exist.',
  sellTitle: 'Your seller workspace', applyTitle: 'Become a seller', applyHint: 'Applications are reviewed by the store before you can list products.', sellerName: 'Shop name', applyAction: 'Apply',
  submitProduct: 'Submit a product', productType: 'Product type', productTitle: 'Title', slug: 'Web address', sku: 'SKU', category: 'Category', submit: 'Submit for review',
  yourListings: 'Your listings', createOffer: 'Put on sale', price: 'Price', stock: 'Stock', contentText: 'Protected content', previewText: 'Public preview', saveContent: 'Save content', saved: 'Saved', waitingApproval: 'Waiting for approval', yourFinance: 'Your balance',
};
type Messages = typeof en;

const fa: Messages = {
  home: 'خانه', products: 'محصولات', cart: 'سبد خرید', orders: 'سفارش‌های من', library: 'کتابخانهٔ من', sell: 'فروش', signIn: 'ورود', signOut: 'خروج',
  search: 'جستجو', searchPlaceholder: 'جستجوی محصول', categories: 'دسته‌بندی‌ها', filters: 'فیلترها', apply: 'اعمال', clear: 'پاک کردن', noResults: 'نتیجه‌ای پیدا نشد.', results: 'نتیجه',
  addToCart: 'افزودن به سبد', added: 'به سبد شما اضافه شد', outOfStock: 'ناموجود', available: 'موجود', unique: 'تک‌نمونه', sellerOffer: 'پیشنهاد', details: 'مشخصات', previous: 'قبلی', next: 'بعدی',
  emptyCart: 'سبد خرید شما خالی است.', remove: 'حذف', quantity: 'تعداد', subtotal: 'جمع جزء', tax: 'مالیات', shipping: 'ارسال', total: 'جمع کل', checkout: 'تسویه حساب', signInToCheckout: 'برای پرداخت وارد شوید', unavailable: 'دیگر موجود نیست',
  paying: 'در حال تأیید پرداخت…', paid: 'پرداخت تأیید شد. سپاسگزاریم!', paymentFailed: 'پرداخت کامل نشد. مبلغی از شما کسر نشده است.', viewOrders: 'مشاهدهٔ سفارش‌ها',
  noOrders: 'هنوز سفارشی ندارید.', order: 'سفارش', status: 'وضعیت', signInRequired: 'برای دیدن این صفحه وارد شوید.',
  noLibrary: 'کتابخانهٔ شما خالی است.', download: 'دریافت', downloadsLeft: 'بار دریافت شده', revoked: 'دسترسی لغو شد', yourContent: 'محتوای شما',
  yes: 'بله', no: 'خیر', preview: 'پیش‌نمایش', included: 'آنچه دریافت می‌کنید', loading: 'در حال بارگذاری…', tryAgain: 'مشکلی پیش آمد. دوباره تلاش کنید.', notFound: 'این صفحه وجود ندارد.',
  sellTitle: 'میز کار فروشنده', applyTitle: 'فروشنده شوید', applyHint: 'درخواست‌ها پیش از ثبت محصول توسط فروشگاه بررسی می‌شوند.', sellerName: 'نام فروشگاه', applyAction: 'ارسال درخواست',
  submitProduct: 'ثبت محصول', productType: 'نوع محصول', productTitle: 'عنوان', slug: 'نشانی وب', sku: 'شناسهٔ کالا', category: 'دسته‌بندی', submit: 'ارسال برای بررسی',
  yourListings: 'محصولات شما', createOffer: 'عرضه برای فروش', price: 'قیمت', stock: 'موجودی', contentText: 'محتوای محافظت‌شده', previewText: 'پیش‌نمایش عمومی', saveContent: 'ذخیرهٔ محتوا', saved: 'ذخیره شد', waitingApproval: 'در انتظار تأیید', yourFinance: 'موجودی شما',
};

const ar: Messages = {
  home: 'الرئيسية', products: 'المنتجات', cart: 'السلة', orders: 'طلباتي', library: 'مكتبتي', sell: 'البيع', signIn: 'تسجيل الدخول', signOut: 'تسجيل الخروج',
  search: 'بحث', searchPlaceholder: 'ابحث عن منتج', categories: 'الفئات', filters: 'التصفية', apply: 'تطبيق', clear: 'مسح', noResults: 'لا توجد نتائج.', results: 'نتيجة',
  addToCart: 'أضف إلى السلة', added: 'أضيف إلى سلتك', outOfStock: 'غير متوفر', available: 'متوفر', unique: 'قطعة فريدة', sellerOffer: 'عرض', details: 'التفاصيل', previous: 'السابق', next: 'التالي',
  emptyCart: 'سلتك فارغة.', remove: 'إزالة', quantity: 'الكمية', subtotal: 'المجموع الفرعي', tax: 'الضريبة', shipping: 'الشحن', total: 'الإجمالي', checkout: 'إتمام الشراء', signInToCheckout: 'سجل الدخول لإتمام الشراء', unavailable: 'لم يعد متوفرًا',
  paying: 'جارٍ تأكيد الدفع…', paid: 'تم تأكيد الدفع. شكرًا لك!', paymentFailed: 'لم يكتمل الدفع. لم يتم خصم أي مبلغ.', viewOrders: 'عرض طلباتي',
  noOrders: 'لا توجد طلبات بعد.', order: 'طلب', status: 'الحالة', signInRequired: 'يرجى تسجيل الدخول لعرض هذه الصفحة.',
  noLibrary: 'مكتبتك فارغة.', download: 'احصل عليه', downloadsLeft: 'مرات التنزيل', revoked: 'تم إلغاء الوصول', yourContent: 'المحتوى الخاص بك',
  yes: 'نعم', no: 'لا', preview: 'معاينة', included: 'ما ستحصل عليه', loading: 'جارٍ التحميل…', tryAgain: 'حدث خطأ. حاول مرة أخرى.', notFound: 'هذه الصفحة غير موجودة.',
  sellTitle: 'مساحة البائع', applyTitle: 'كن بائعًا', applyHint: 'تتم مراجعة الطلبات قبل إدراج المنتجات.', sellerName: 'اسم المتجر', applyAction: 'تقديم الطلب',
  submitProduct: 'إرسال منتج', productType: 'نوع المنتج', productTitle: 'العنوان', slug: 'عنوان الويب', sku: 'رمز المنتج', category: 'الفئة', submit: 'إرسال للمراجعة',
  yourListings: 'منتجاتك', createOffer: 'عرض للبيع', price: 'السعر', stock: 'المخزون', contentText: 'المحتوى المحمي', previewText: 'معاينة عامة', saveContent: 'حفظ المحتوى', saved: 'تم الحفظ', waitingApproval: 'بانتظار الموافقة', yourFinance: 'رصيدك',
};

const all: Record<string, Messages> = {en, fa, ar};
export const messagesFor = (locale: string): Messages => all[locale.split('-')[0]] ?? en;
export type {Messages};
