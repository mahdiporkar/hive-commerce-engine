// Small shared kit bundled into each admin area. Micro-apps never import each other (they share code at build time only).
import {useCallback, useEffect, useState, type ReactNode} from 'react';
import type {HiveMountContext} from '@hive-platform/contracts';

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public violations: {field: string; message: string}[] = []) {
    super(message);
  }
}

let csrf: Record<string, string> | null = null;

/** Calls a commerce operation through the BFF, same origin. The server decides; the UI only reports the outcome. */
export async function api<T = any>(method: string, path: string, body?: unknown): Promise<T> {
  const unsafe = method !== 'GET';
  if (unsafe && !csrf) {
    const token = await (await fetch('/auth/csrf', {credentials: 'same-origin'})).json();
    csrf = {[token.headerName]: token.token};
  }
  const response = await fetch(`/api/routes/commerce${path}`, {
    method, credentials: 'same-origin',
    headers: {Accept: 'application/json', ...(body === undefined ? {} : {'Content-Type': 'application/json'}), ...(unsafe ? csrf! : {})},
    ...(body === undefined ? {} : {body: JSON.stringify(body)}),
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    if (response.status === 403) csrf = null;
    throw new ApiError(response.status, data?.code ?? 'ERROR', data?.message ?? `HTTP ${response.status}`, data?.violations ?? []);
  }
  return data as T;
}

const STRINGS: Record<string, Record<string, string>> = {
  fa: {refresh: 'تازه‌سازی', save: 'ذخیره', create: 'ایجاد', loading: 'در حال بارگذاری…', empty: 'موردی نیست', error: 'خطا', status: 'وضعیت', actions: 'عملیات', required: 'الزامی'},
  en: {refresh: 'Refresh', save: 'Save', create: 'Create', loading: 'Loading…', empty: 'Nothing here yet', error: 'Error', status: 'Status', actions: 'Actions', required: 'Required'},
};

export const t = (context: HiveMountContext, key: string) => (STRINGS[context.locale?.split('-')[0]] ?? STRINGS.en)[key] ?? key;

/** Picks the best label for the current locale from a localized map. */
export const text = (context: HiveMountContext, values?: Record<string, string> | null) =>
  !values ? '' : values[context.locale?.split('-')[0]] ?? values.en ?? Object.values(values)[0] ?? '';

// Logical properties only, so RTL and LTR share every rule. Shadow DOM keeps these from leaking into the host.
const CSS = `
:host,.hc{font:14px/1.5 system-ui,sans-serif;color:#1f2933;--hc-accent:var(--hive-accent,#1d4ed8);--hc-border:var(--hive-border,#d9dee4);--hc-muted:var(--hive-muted,#f5f7f9)}
.hc{padding:16px;display:grid;gap:16px}
.hc h2{margin:0;font-size:18px}.hc h3{margin:0;font-size:15px}
.hc section{border:1px solid var(--hc-border);border-radius:8px;padding:16px;display:grid;gap:12px;background:#fff}
.hc table{border-collapse:collapse;inline-size:100%}
.hc th,.hc td{text-align:start;padding:6px 8px;border-block-end:1px solid #e6e9ed;vertical-align:top}
.hc th{font-weight:600;background:var(--hc-muted)}
.hc label{display:grid;gap:4px;font-weight:500}
.hc input,.hc select,.hc textarea{font:inherit;padding:6px 8px;border:1px solid #bcc4cc;border-radius:6px;min-inline-size:0;background:#fff}
.hc .grid{display:grid;gap:12px;grid-template-columns:repeat(auto-fit,minmax(200px,1fr))}
.hc .row{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
.hc button{font:inherit;padding:6px 12px;border:1px solid var(--hc-accent);background:var(--hc-accent);color:#fff;border-radius:6px;cursor:pointer}
.hc button.secondary{background:#fff;color:var(--hc-accent)}
.hc button:disabled{opacity:.5;cursor:not-allowed}
.hc .error{color:#991b1b;background:#fef2f2;border:1px solid #fecaca;border-radius:6px;padding:8px 12px}
.hc .ok{color:#065f46;background:#ecfdf5;border:1px solid #a7f3d0;border-radius:6px;padding:8px 12px}
.hc code{font-size:12px;word-break:break-all}
`;

export function Shell({context, title, children}: {context: HiveMountContext; title: string; children: ReactNode}) {
  return <div className="hc" dir={context.direction === 'RTL' || (context.direction as string) === 'rtl' ? 'rtl' : 'ltr'}><style>{CSS}</style><h2>{title}</h2>{children}</div>;
}

export function Problem({error}: {error: unknown}) {
  if (!error) return null;
  const e = error as ApiError;
  return <div className="error" role="alert"><strong>{e.code ?? 'ERROR'}</strong>: {e.message}{e.violations?.length ? <ul>{e.violations.map((v, i) => <li key={i}><code>{v.field}</code> {v.message}</li>)}</ul> : null}</div>;
}

/** Loads data on mount and on demand; failures are shown, never hidden. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const reload = useCallback(() => {
    setError(null);
    return load().then(setData, setError);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => { void reload(); }, [reload]);
  return {data, error, reload};
}

/** Runs a mutation and reports its outcome. */
export function useAction(after?: () => unknown) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState<string | null>(null);
  const run = async (work: () => Promise<unknown>, message = 'OK') => {
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      await work();
      setDone(message);
      await after?.();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return {busy, error, done, run};
}

export function Outcome({action}: {action: {error: unknown; done: string | null}}) {
  return <>{action.error ? <Problem error={action.error} /> : null}{action.done ? <div className="ok" role="status">{action.done}</div> : null}</>;
}

export function Table({columns, rows, empty}: {columns: string[]; rows: ReactNode[][]; empty: string}) {
  if (!rows.length) return <p>{empty}</p>;
  return <table><thead><tr>{columns.map(c => <th key={c}>{c}</th>)}</tr></thead><tbody>{rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j}>{c}</td>)}</tr>)}</tbody></table>;
}

export const money = (amount: unknown, currency?: string) => `${Number(amount ?? 0).toLocaleString()} ${currency ?? ''}`.trim();
