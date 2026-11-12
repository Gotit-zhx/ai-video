import type { ReactNode } from 'react';
import { Empty } from 'antd';

import { useFinance } from '../features/finance/context';
import type { FinanceApi, FinanceFilters, FinanceHost } from '../shared/contracts';
import type { ShadowSurface } from '../shared/shadow';
import { FinanceRootProviders } from './providers';

function FinancePlaceholder() {
  const { t } = useFinance();
  return (
    <main className="finance-modern-placeholder">
      <Empty description={t('新版财务 BI 基础已就绪')} />
    </main>
  );
}

export interface FinanceRootProps {
  active: boolean;
  api: FinanceApi;
  children?: ReactNode;
  host: FinanceHost;
  initialFilters?: Partial<FinanceFilters> | undefined;
  route: string;
  surface: ShadowSurface;
}

export function FinanceRoot({ children, ...props }: FinanceRootProps) {
  return (
    <FinanceRootProviders {...props}>
      {children ?? <FinancePlaceholder />}
    </FinanceRootProviders>
  );
}
