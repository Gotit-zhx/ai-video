import { Component, type ReactNode } from "react";
import { useFinance } from "../features/finance/context";
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
      <FinancePageBoundary>
        {children ?? <FinanceApplication />}
      </FinancePageBoundary>
    </FinanceRootProviders>
  );
}

/** Keep the legacy escape usable when a later route chunk cannot be loaded. */
class PageErrorBoundary extends Component<
  { children: ReactNode; fallback: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}
function FinancePageBoundary({ children }: { children: ReactNode }) {
  const { active, session, host, filters, t } = useFinance();
  if (!active || !session.canView) return null;
  return (
    <PageErrorBoundary
      key={session.organizationId + ":" + session.userId}
      fallback={
        <section className="finance-workspace" role="alert">
          <h2>{t("新版财务页面加载失败")}</h2>
          <p>{t("请返回旧版继续操作。未自动重复提交任何操作。")}</p>
          <a
            href={
              host.getLegacyUrl?.(filters) ||
              "/?financeUi=legacy#finance-overview"
            }
          >
            {t("返回旧版")}
          </a>
        </section>
      }
    >
      {children}
    </PageErrorBoundary>
  );
}
