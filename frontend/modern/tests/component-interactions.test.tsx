import { fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BusinessTable, InfoTip } from '../src/shared/components';
import { renderFinance } from './fixtures';

describe('documented Ant Design interactions',()=>{
  it('opens an informational tooltip from keyboard focus inside the shadow root',async()=>{
    const view=renderFinance(<InfoTip title="历史汇率口径" />);
    fireEvent.focus(view.getByRole('button',{name:'历史汇率口径'}));
    await waitFor(()=>expect(view.surface.overlayContainer.querySelector('[role="tooltip"]')).toHaveTextContent('历史汇率口径'));
    expect(document.body.querySelector('[role="tooltip"]')).toBeNull();
  });
  it('opens an informational tooltip on a touch-style click',async()=>{
    const view=renderFinance(<InfoTip title="成本完整性口径" />);
    fireEvent.click(view.getByRole('button',{name:'成本完整性口径'}));
    await waitFor(()=>expect(view.surface.overlayContainer.querySelector('[role="tooltip"]')).toHaveTextContent('成本完整性口径'));
  });
  it('keeps horizontal table scrolling when a page configures only vertical height',()=>{
    const view=renderFinance(<BusinessTable scroll={{y:200}} columns={[{title:'订单号',dataIndex:'id'}]} dataSource={[{id:'record-a',key:'record-a'}]} pagination={false} />);
    const tables=view.getAllByRole('table');
    expect(tables.some(table=>table.style.width==='max-content')).toBe(true);
  });
});
