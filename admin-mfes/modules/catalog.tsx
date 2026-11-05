// Catalog administration: attributes, product types, categories and products. Product forms come from the schema.
import {useMemo, useState} from 'react';
import {createReactMicroApp, type ReactMicroAppProps} from '@hive-platform/react';
import {api, Outcome, Problem, Shell, t, Table, text, useAction, useLoad} from '../lib/kit';
import {SchemaField, toValues, type AttributeInfo, type Spec, type Values} from '../lib/schema-form';

const ATTRIBUTE_TYPES = ['STRING', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME', 'ENUM', 'MULTI_SELECT', 'COLOR', 'MEASUREMENT', 'RANGE', 'URL', 'REFERENCE', 'JSON'];

function Attributes({context, attributes, reload}: ReactMicroAppProps & {attributes: any[]; reload: () => unknown}) {
  const [form, setForm] = useState({key: '', type: 'STRING', label: '', options: '', units: '', filterable: false});
  const action = useAction(reload);
  const save = () => action.run(() => {
    const options = form.options.split(',').map(s => s.trim()).filter(Boolean).map(code => ({code, labels: {[context.locale?.split('-')[0] || 'en']: code}}));
    const units = form.units.split(',').map(s => s.trim()).filter(Boolean);
    return api('POST', '/v1/admin/attributes', {key: form.key, type: form.type, labels: {[context.locale?.split('-')[0] || 'en']: form.label}, options,
      constraints: units.length ? {units} : undefined, flags: {filterable: form.filterable, searchable: false, sortable: false, comparable: false, visible: true}});
  }, 'Attribute defined');
  return <section><h3>Attributes</h3>
    <div className="grid">
      <label>Key<input name="attribute-key" value={form.key} onChange={e => setForm({...form, key: e.target.value})} /></label>
      <label>Type<select value={form.type} onChange={e => setForm({...form, type: e.target.value})}>{ATTRIBUTE_TYPES.map(a => <option key={a}>{a}</option>)}</select></label>
      <label>Label<input value={form.label} onChange={e => setForm({...form, label: e.target.value})} /></label>
      <label>Options (comma separated)<input value={form.options} onChange={e => setForm({...form, options: e.target.value})} /></label>
      <label>Units (comma separated)<input value={form.units} onChange={e => setForm({...form, units: e.target.value})} /></label>
      <label>Filterable<input type="checkbox" checked={form.filterable} onChange={e => setForm({...form, filterable: e.target.checked})} /></label>
    </div>
    <div className="row"><button disabled={action.busy} onClick={save}>{t(context, 'create')}</button></div><Outcome action={action} />
    <Table columns={['Key', 'Type', 'Label', 'Options']} empty={t(context, 'empty')} rows={attributes.map(a => [<code>{a.key}</code>, a.type, text(context, a.labels), (a.options ?? []).join(', ')])} />
  </section>;
}

function ProductTypes({context, types, attributes, reload}: ReactMicroAppProps & {types: any[]; attributes: any[]; reload: () => unknown}) {
  const [form, setForm] = useState({key: '', name: '', kind: 'PHYSICAL'});
  const [picked, setPicked] = useState<Record<string, {required: boolean; variantDefining: boolean}>>({});
  const action = useAction(reload);
  const toggle = (key: string, on: boolean) => setPicked(p => { const n = {...p}; if (on) n[key] = {required: false, variantDefining: false}; else delete n[key]; return n; });
  const save = () => action.run(() => api('POST', '/v1/admin/product-types', {key: form.key, name: {[context.locale?.split('-')[0] || 'en']: form.name}, kind: form.kind,
    attributes: Object.entries(picked).map(([key, v]) => ({key, ...v}))}), 'Product type created (draft)');
  return <section><h3>Product types</h3>
    <div className="grid">
      <label>Key<input value={form.key} onChange={e => setForm({...form, key: e.target.value})} /></label>
      <label>Name<input value={form.name} onChange={e => setForm({...form, name: e.target.value})} /></label>
      <label>Kind<select value={form.kind} onChange={e => setForm({...form, kind: e.target.value})}><option>PHYSICAL</option><option>DIGITAL</option></select></label>
    </div>
    <Table columns={['Attribute', 'Use', 'Required', 'Defines variants']} empty={t(context, 'empty')} rows={attributes.map(a => [<code>{a.key}</code>,
      <input type="checkbox" aria-label={`use ${a.key}`} checked={!!picked[a.key]} onChange={e => toggle(a.key, e.target.checked)} />,
      <input type="checkbox" disabled={!picked[a.key]} checked={!!picked[a.key]?.required} onChange={e => setPicked(p => ({...p, [a.key]: {...p[a.key], required: e.target.checked}}))} />,
      <input type="checkbox" disabled={!picked[a.key] || !['ENUM', 'COLOR'].includes(a.type)} checked={!!picked[a.key]?.variantDefining} onChange={e => setPicked(p => ({...p, [a.key]: {...p[a.key], variantDefining: e.target.checked}}))} />])} />
    <div className="row"><button disabled={action.busy} onClick={save}>{t(context, 'create')}</button></div><Outcome action={action} />
    <Table columns={['Key', 'Kind', 'Versions', t(context, 'actions')]} empty={t(context, 'empty')} rows={types.map(pt => [<code>{pt.key}</code>, pt.kind,
      pt.versions.map((v: any) => `v${v.version} ${v.status}`).join(', '),
      pt.versions.filter((v: any) => v.status === 'DRAFT').map((v: any) => <button key={v.version} className="secondary" onClick={() => action.run(() => api('POST', `/v1/admin/product-types/${pt.key}/versions/${v.version}/publish`), 'Schema published')}>Publish v{v.version}</button>)])} />
  </section>;
}

function Categories({context, categories, reload}: ReactMicroAppProps & {categories: any[]; reload: () => unknown}) {
  const [form, setForm] = useState({slug: '', name: '', parentId: ''});
  const action = useAction(reload);
  return <section><h3>Categories</h3>
    <div className="grid">
      <label>Slug<input value={form.slug} onChange={e => setForm({...form, slug: e.target.value})} /></label>
      <label>Name<input value={form.name} onChange={e => setForm({...form, name: e.target.value})} /></label>
      <label>Parent<select value={form.parentId} onChange={e => setForm({...form, parentId: e.target.value})}><option value="" />{categories.map(c => <option key={c.id} value={c.id}>{'— '.repeat(c.depth - 1)}{text(context, c.name)}</option>)}</select></label>
    </div>
    <div className="row"><button disabled={action.busy} onClick={() => action.run(() => api('POST', '/v1/admin/categories', {slug: form.slug, name: {[context.locale?.split('-')[0] || 'en']: form.name}, parentId: form.parentId || null}), 'Category created')}>{t(context, 'create')}</button></div>
    <Outcome action={action} />
    <Table columns={['Name', 'Slug', 'Depth']} empty={t(context, 'empty')} rows={categories.map(c => [text(context, c.name), <code>{c.slug}</code>, c.depth])} />
  </section>;
}

function Products({context, types, attributes, categories, products, reload}: ReactMicroAppProps & {types: any[]; attributes: any[]; categories: any[]; products: any[]; reload: () => unknown}) {
  const published = types.filter(pt => pt.versions.some((v: any) => v.status === 'PUBLISHED'));
  const [typeKey, setTypeKey] = useState('');
  const [head, setHead] = useState({title: '', slug: '', categoryId: ''});
  const [values, setValues] = useState<Values>({});
  const [variants, setVariants] = useState<{sku: string; values: Values}[]>([{sku: '', values: {}}]);
  const action = useAction(reload);
  const type = published.find(pt => pt.key === typeKey);
  const schema = useMemo(() => type ? [...type.versions].filter((v: any) => v.status === 'PUBLISHED').pop() : null, [type]);
  const specs: Spec[] = schema?.attributes ?? [];
  const productSpecs = specs.filter(s => !s.variantDefining), variantSpecs = specs.filter(s => s.variantDefining);
  const info = (key: string): AttributeInfo | undefined => attributes.find(a => a.key === key);
  const save = () => action.run(() => api('POST', '/v1/admin/products', {productType: typeKey, categoryId: head.categoryId || null, title: {[context.locale?.split('-')[0] || 'en']: head.title},
    slug: head.slug, attributes: toValues(productSpecs, values), variants: variants.map(v => ({sku: v.sku, attributes: toValues(variantSpecs, v.values)}))}), 'Product created');
  return <section><h3>Products</h3>
    <div className="grid">
      <label>Product type<select name="product-type" value={typeKey} onChange={e => { setTypeKey(e.target.value); setValues({}); setVariants([{sku: '', values: {}}]); }}><option value="" />{published.map(pt => <option key={pt.key} value={pt.key}>{text(context, pt.name)}</option>)}</select></label>
      <label>Title<input name="title" value={head.title} onChange={e => setHead({...head, title: e.target.value})} /></label>
      <label>Slug<input name="slug" value={head.slug} onChange={e => setHead({...head, slug: e.target.value})} /></label>
      <label>Category<select value={head.categoryId} onChange={e => setHead({...head, categoryId: e.target.value})}><option value="" />{categories.map(c => <option key={c.id} value={c.id}>{text(context, c.name)}</option>)}</select></label>
    </div>
    {type ? <>
      <div className="grid" data-testid="generated-fields">{productSpecs.map(s => <SchemaField key={s.key} spec={s} info={info(s.key)} locale={context.locale} value={values[s.key]} onChange={v => setValues(c => ({...c, [s.key]: v}))} />)}</div>
      <h3>Variants</h3>
      {variants.map((variant, i) => <div className="grid" key={i}>
        <label>SKU<input name={`sku-${i}`} value={variant.sku} onChange={e => setVariants(vs => vs.map((x, j) => j === i ? {...x, sku: e.target.value} : x))} /></label>
        {variantSpecs.map(s => <SchemaField key={s.key} spec={s} info={info(s.key)} locale={context.locale} value={variant.values[s.key]} onChange={v => setVariants(vs => vs.map((x, j) => j === i ? {...x, values: {...x.values, [s.key]: v}} : x))} />)}
      </div>)}
      <div className="row">
        {variantSpecs.length ? <button className="secondary" onClick={() => setVariants(vs => [...vs, {sku: '', values: {}}])}>+ Variant</button> : null}
        <button disabled={action.busy} onClick={save}>{t(context, 'create')}</button>
      </div>
    </> : null}
    <Outcome action={action} />
    <Table columns={['Title', 'Slug', 'SKUs', t(context, 'actions')]} empty={t(context, 'empty')} rows={products.map(p => [text(context, p.title), <code>{p.slug}</code>, p.variants.map((v: any) => v.sku).join(', '),
      <button className="secondary" onClick={() => action.run(() => api('POST', `/v1/admin/products/${p.id}/publish`), 'Published')}>Publish</button>])} />
  </section>;
}

function CatalogAdmin({context}: ReactMicroAppProps) {
  const {data, error, reload} = useLoad(async () => {
    const [attributes, types, categories, products] = await Promise.all([api<any[]>('GET', '/v1/admin/attributes'), api<any[]>('GET', '/v1/admin/product-types'),
      api<any[]>('GET', '/v1/store/categories'), api<any[]>('GET', '/v1/admin/products')]);
    return {attributes, types, categories, products};
  });
  return <Shell context={context} title="Catalog">
    <Problem error={error} />
    {!data && !error ? <p>{t(context, 'loading')}</p> : null}
    {data ? <>
      <Products context={context} {...data} reload={reload} />
      <ProductTypes context={context} types={data.types} attributes={data.attributes} reload={reload} />
      <Attributes context={context} attributes={data.attributes} reload={reload} />
      <Categories context={context} categories={data.categories} reload={reload} />
    </> : null}
  </Shell>;
}

export default createReactMicroApp(CatalogAdmin);
