// Product forms generated from the product-type schema (master prompt §18.1): one component for every industry.
import type {ReactNode} from 'react';

export interface Spec {key: string; type: string; required: boolean; variantDefining: boolean; options?: string[]; units?: string[] | null; min?: number | null; max?: number | null; maxLength?: number | null}
export interface AttributeInfo {key: string; labels?: Record<string, string>; optionLabels?: Record<string, Record<string, string>>}
export type Values = Record<string, unknown>;

/** Turns what the form holds into the plain values the engine validates. Empty optional fields are omitted. */
export function toValue(spec: Spec, raw: unknown): unknown {
  if (raw === undefined || raw === null || raw === '') return undefined;
  switch (spec.type) {
    case 'INTEGER': case 'DECIMAL': return Number.isNaN(Number(raw)) ? raw : Number(raw);
    case 'BOOLEAN': return raw === true || raw === 'true';
    case 'MEASUREMENT': {
      const m = raw as {value?: string; unit?: string};
      return m.value === undefined || m.value === '' ? undefined : {value: Number(m.value), unit: m.unit ?? spec.units?.[0]};
    }
    case 'RANGE': {
      const r = raw as {min?: string; max?: string};
      return r.min === undefined || r.min === '' ? undefined : {min: Number(r.min), max: Number(r.max)};
    }
    case 'JSON':
      try { return JSON.parse(String(raw)); } catch { return raw; }
    default: return raw;
  }
}

export function toValues(specs: Spec[], form: Values): Values {
  const out: Values = {};
  for (const spec of specs) {
    const value = toValue(spec, form[spec.key]);
    if (value !== undefined) out[spec.key] = value;
  }
  return out;
}

export function SchemaField({spec, info, locale, value, onChange}: {spec: Spec; info?: AttributeInfo; locale: string; value: unknown; onChange: (v: unknown) => void}) {
  const language = locale?.split('-')[0];
  const label = info?.labels?.[language] ?? info?.labels?.en ?? spec.key;
  const optionLabel = (code: string) => info?.optionLabels?.[code]?.[language] ?? info?.optionLabels?.[code]?.en ?? code;
  const options = spec.options ?? [];
  let control: ReactNode;
  const common = {name: spec.key, required: spec.required, 'aria-label': label};
  switch (spec.type) {
    case 'ENUM': case 'COLOR':
      control = options.length ? (
        <select {...common} value={String(value ?? '')} onChange={e => onChange(e.target.value)}><option value="" />{options.map(o => <option key={o} value={o}>{optionLabel(o)}</option>)}</select>
      ) : <input {...common} type="color" value={String(value ?? '#000000')} onChange={e => onChange(e.target.value)} />;
      break;
    case 'MULTI_SELECT':
      control = <select {...common} multiple value={(value as string[]) ?? []} onChange={e => onChange(Array.from(e.target.selectedOptions).map(o => o.value))}>{options.map(o => <option key={o} value={o}>{optionLabel(o)}</option>)}</select>;
      break;
    case 'BOOLEAN':
      control = <select {...common} value={String(value ?? '')} onChange={e => onChange(e.target.value)}><option value="" /><option value="true">✓</option><option value="false">✗</option></select>;
      break;
    case 'INTEGER': case 'DECIMAL':
      control = <input {...common} type="number" step={spec.type === 'INTEGER' ? 1 : 'any'} min={spec.min ?? undefined} max={spec.max ?? undefined} value={String(value ?? '')} onChange={e => onChange(e.target.value)} />;
      break;
    case 'DATE':
      control = <input {...common} type="date" value={String(value ?? '')} onChange={e => onChange(e.target.value)} />;
      break;
    case 'DATETIME':
      control = <input {...common} placeholder="2026-11-05T10:15:30+03:30" value={String(value ?? '')} onChange={e => onChange(e.target.value)} />;
      break;
    case 'URL':
      control = <input {...common} type="url" value={String(value ?? '')} onChange={e => onChange(e.target.value)} />;
      break;
    case 'LONG_TEXT': case 'JSON':
      control = <textarea {...common} rows={4} value={String(value ?? '')} onChange={e => onChange(e.target.value)} />;
      break;
    case 'MEASUREMENT': {
      const m = (value as {value?: string; unit?: string}) ?? {};
      control = <span className="row"><input {...common} type="number" step="any" value={m.value ?? ''} onChange={e => onChange({...m, value: e.target.value})} />
        <select aria-label={`${label} unit`} value={m.unit ?? spec.units?.[0] ?? ''} onChange={e => onChange({...m, unit: e.target.value})}>{(spec.units ?? []).map(u => <option key={u} value={u}>{u}</option>)}</select></span>;
      break;
    }
    case 'RANGE': {
      const r = (value as {min?: string; max?: string}) ?? {};
      control = <span className="row"><input {...common} type="number" step="any" placeholder="min" value={r.min ?? ''} onChange={e => onChange({...r, min: e.target.value})} />
        <input aria-label={`${label} max`} type="number" step="any" placeholder="max" value={r.max ?? ''} onChange={e => onChange({...r, max: e.target.value})} /></span>;
      break;
    }
    default:
      control = <input {...common} maxLength={spec.maxLength ?? undefined} value={String(value ?? '')} onChange={e => onChange(e.target.value)} />;
  }
  return <label>{label}{spec.required ? ' *' : ''}{control}</label>;
}
