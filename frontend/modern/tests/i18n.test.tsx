import { describe, expect, it } from 'vitest';

import { createFinanceTranslator } from '../src/shared/i18n';

describe('finance translations', () => {
  it('uses explicit catalog keys for all seven supported locales', () => {
    expect(createFinanceTranslator('zh-CN')('确认')).toBe('确认');
    expect(createFinanceTranslator('en-US')('确认')).toBe('Confirm');
    expect(createFinanceTranslator('id-ID')('确认')).toBe('Konfirmasi');
    expect(createFinanceTranslator('th-TH')('确认')).toBe('ยืนยัน');
    expect(createFinanceTranslator('vi-VN')('确认')).toBe('Xác nhận');
    expect(createFinanceTranslator('ms-MY')('确认')).toBe('Sahkan');
    expect(createFinanceTranslator('fil-PH')('确认')).toBe('Kumpirmahin');
  });

  it('keeps an unknown explicit key unchanged', () => {
    expect(createFinanceTranslator('en')('财务现代界面专用新标签')).toBe('财务现代界面专用新标签');
  });
});
