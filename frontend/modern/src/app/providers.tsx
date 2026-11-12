import { useState, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { FinanceProvider } from '../features/finance/context';
import { useFinance } from '../features/finance/context';
import type { FinanceApi, FinanceFilters, FinanceHost } from '../shared/contracts';
import { FinanceUiProvider, type ShadowSurface } from '../shared/shadow';

export function createFinanceQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        refetchOnWindowFocus: false,
        retry: false,
        staleTime: 15_000
      },
      mutations: {
        retry: false
      }
    }
  });
}

export interface FinanceRootProvidersProps {
  active: boolean;
  api: FinanceApi;
  children: ReactNode;
  host: FinanceHost;
  initialFilters?: Partial<FinanceFilters> | undefined;
  route: string;
  surface: ShadowSurface;
}

function FinanceUiBridge({ children, surface }: { children: ReactNode; surface: ShadowSurface }) {
  const { session } = useFinance();
  return (
    <FinanceUiProvider surface={surface} themeName={session.theme}>
      {children}
    </FinanceUiProvider>
  );
}

export function FinanceRootProviders({
  active,
  api,
  children,
  host,
  initialFilters,
  route,
  surface
}: FinanceRootProvidersProps) {
  const [queryClient] = useState(createFinanceQueryClient);
  return (
    <QueryClientProvider client={queryClient}>
      <FinanceProvider
        active={active}
        api={api}
        host={host}
        initialFilters={initialFilters}
        route={route}
      >
        <FinanceUiBridge surface={surface}>
          {children}
        </FinanceUiBridge>
      </FinanceProvider>
    </QueryClientProvider>
  );
}
