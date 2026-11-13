import type { Row } from './contracts';

export function record(value: unknown): Row | undefined {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return undefined;
  return value as Row;
}

export function rows(value: unknown): Row[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    const itemRecord = record(item);
    return itemRecord ? [itemRecord] : [];
  });
}

export function text(value: unknown): string | undefined {
  if (value === null || value === undefined) return undefined;
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'bigint' || typeof value === 'boolean') {
    return String(value);
  }
  return undefined;
}

export function numeric(value: unknown): number | undefined {
  if (value === null || value === undefined || value === '') return undefined;
  if (typeof value === 'number') return Number.isFinite(value) ? value : undefined;
  if (typeof value !== 'string') return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

export function amountText(value: unknown, currency?: string): string {
  const amount = numeric(value);
  if (amount === undefined) return '—';
  const formatted = amount.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
  return currency ? `${currency} ${formatted}` : formatted;
}
