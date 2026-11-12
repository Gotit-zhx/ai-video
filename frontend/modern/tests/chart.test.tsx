import { render } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const echartsMock = vi.hoisted(() => ({
  init: vi.fn(),
  instances: [] as Array<{
    dispose: ReturnType<typeof vi.fn>;
    resize: ReturnType<typeof vi.fn>;
    setOption: ReturnType<typeof vi.fn>;
  }>
}));

vi.mock('echarts', () => ({ init: echartsMock.init }));

import { FinanceChart } from '../src/shared/chart';

describe('FinanceChart', () => {
  beforeEach(() => {
    echartsMock.instances.length = 0;
    echartsMock.init.mockReset();
    echartsMock.init.mockImplementation(() => {
      const chart = {
        dispose: vi.fn(),
        resize: vi.fn(),
        setOption: vi.fn()
      };
      echartsMock.instances.push(chart);
      return chart;
    });
  });

  it('keeps the instance for callback changes and reapplies a stable option after theme recreation', () => {
    const option = { series: [{ data: [1, 2], type: 'bar' }] };
    const firstReady = vi.fn();
    const secondReady = vi.fn();
    const view = render(
      <FinanceChart onReady={firstReady} option={option} theme="light" />
    );

    expect(echartsMock.init).toHaveBeenCalledOnce();
    expect(echartsMock.instances[0]?.setOption).toHaveBeenCalledWith(option, undefined);

    view.rerender(<FinanceChart onReady={secondReady} option={option} theme="light" />);
    expect(echartsMock.init).toHaveBeenCalledOnce();

    view.rerender(<FinanceChart onReady={secondReady} option={option} theme="dark" />);
    expect(echartsMock.init).toHaveBeenCalledTimes(2);
    expect(echartsMock.instances[0]?.dispose).toHaveBeenCalledOnce();
    expect(echartsMock.instances[1]?.setOption).toHaveBeenCalledWith(option, undefined);
  });
});
