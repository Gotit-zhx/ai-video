import { lazy, Suspense, useEffect, useRef, useState } from "react";
import type { EChartsCoreOption } from "echarts";
import { Panel } from "../../../shared/components";
import { useFinance } from "../context";
const Chart = lazy(() =>
  import("../../../shared/chart").then((module) => ({
    default: module.FinanceChart,
  })),
);
export function ReportChart({
  title,
  option,
}: {
  title: string;
  option: EChartsCoreOption;
}) {
  const { t } = useFinance();
  const ref = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const observer = new ResizeObserver(() => {
      if (element.clientWidth > 0) setVisible(true);
    });
    observer.observe(element);
    if (element.clientWidth > 0) setVisible(true);
    return () => observer.disconnect();
  }, []);
  return (
    <Panel title={t(title)}>
      <div ref={ref} aria-label={t(title)} style={{ minHeight: 280 }}>
        {visible && (
          <Suspense fallback={<span>{t("图表加载中")}</span>}>
            <Chart option={option} />
          </Suspense>
        )}
      </div>
    </Panel>
  );
}
