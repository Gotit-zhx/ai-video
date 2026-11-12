import type { ReactNode } from "react";
import { FinanceApplication } from "../features/finance/FinanceApplication";

import type {
  FinanceApi,
  FinanceFilters,
  FinanceHost,
} from "../shared/contracts";
import type { ShadowSurface } from "../shared/shadow";
import { FinanceRootProviders } from "./providers";

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
      {children ?? <FinanceApplication />}
    </FinanceRootProviders>
  );
}
