import { useEffect, useRef } from 'react';
import type { CSSProperties } from 'react';
import * as echarts from 'echarts/core';
import { LineChart, BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);
import type { ECharts, EChartsCoreOption, SetOptionOpts } from 'echarts';

export interface FinanceChartProps {
  option: EChartsCoreOption;
  className?: string;
  style?: CSSProperties;
  theme?: string | object;
  setOption?: SetOptionOpts;
  onReady?: (chart: ECharts) => void;
}

export function FinanceChart({
  option,
  className,
  style,
  theme,
  setOption,
  onReady
}: FinanceChartProps) {
  const elementRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<ECharts | null>(null);
  const onReadyRef = useRef(onReady);
  onReadyRef.current = onReady;

  useEffect(() => {
    const element = elementRef.current;
    if (!element) return;
    const chart = echarts.init(element, theme);
    chartRef.current = chart;
    onReadyRef.current?.(chart);
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(element);
    return () => {
      observer.disconnect();
      chart.dispose();
      chartRef.current = null;
    };
  }, [theme]);

  useEffect(() => {
    chartRef.current?.setOption(option, setOption);
  }, [option, setOption, theme]);

  return (
    <div
      aria-label="Chart"
      className={className}
      ref={elementRef}
      role="img"
      style={{ minHeight: 280, width: '100%', ...style }}
    />
  );
}
