import catalogJson from '../../../i18n/catalog.json';

export type FinanceLocale = 'zh' | 'en' | 'id' | 'th' | 'vi' | 'ms' | 'fil';
export type FinanceTranslator = (sourceLabel: string) => string;

const localeIndexes: Record<Exclude<FinanceLocale, 'zh'>, number> = {
  en: 0,
  id: 1,
  th: 2,
  vi: 3,
  ms: 4,
  fil: 5
};

const catalog = catalogJson as Record<string, readonly string[]>;

export function normalizeFinanceLocale(locale: string): FinanceLocale {
  const normalized = locale.toLowerCase().replace('_', '-');
  if (normalized === 'tl' || normalized.startsWith('tl-') || normalized.startsWith('fil')) {
    return 'fil';
  }
  const language = normalized.split('-')[0];
  if (language && ['zh', 'en', 'id', 'th', 'vi', 'ms'].includes(language)) {
    return language as FinanceLocale;
  }
  return 'en';
}

export function createFinanceTranslator(locale: string): FinanceTranslator {
  const language = normalizeFinanceLocale(locale);
  if (language === 'zh') return (sourceLabel) => sourceLabel;
  const index = localeIndexes[language];
  return (sourceLabel) => catalog[sourceLabel]?.[index] ?? catalog[sourceLabel]?.[0] ?? sourceLabel;
}
