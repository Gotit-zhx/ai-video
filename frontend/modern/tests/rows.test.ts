import { describe, expect, it } from 'vitest';

import { amountText, numeric, record, rows, text } from '../src/shared/rows';

describe('row field helpers', () => {
  it('preserves real zero values while marking absent values', () => {
    expect(text(undefined)).toBeUndefined();
    expect(text(null)).toBeUndefined();
    expect(text(0)).toBe('0');

    expect(numeric(undefined)).toBeUndefined();
    expect(numeric('')).toBeUndefined();
    expect(numeric('0')).toBe(0);
    expect(numeric(0)).toBe(0);

    expect(amountText(undefined, 'USD')).toBe('—');
    expect(amountText(0, 'USD')).toBe('USD 0.00');
  });

  it('accepts only record-shaped rows', () => {
    expect(record({ id: 'one' })).toEqual({ id: 'one' });
    expect(record(null)).toBeUndefined();
    expect(record([])).toBeUndefined();
    expect(rows([{ id: 1 }, null, 'bad', { id: 2 }])).toEqual([{ id: 1 }, { id: 2 }]);
    expect(rows({ rows: [] })).toEqual([]);
  });
});
