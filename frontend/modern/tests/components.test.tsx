import { describe, expect, it } from 'vitest';

import {
  BusinessTable,
  InfoTip,
  MetricCard,
  Panel,
  StatusTag
} from '../src/shared/components';
import { renderFinance } from './fixtures';

describe('shared finance components', () => {
  it('renders real Ant Design table and presentation components', async () => {
    const view = renderFinance(
      <>
        <Panel title="Orders">
          <BusinessTable
            columns={[{ dataIndex: 'number', title: 'Order number' }]}
            dataSource={[{ id: '1', number: 'SO-100' }]}
            pagination={false}
            rowKey="id"
          />
        </Panel>
        <MetricCard label="Revenue" value="USD 0.00" />
        <StatusTag status="warning">Pending</StatusTag>
        <InfoTip title="Backend total" />
      </>
    );

    expect(view.getByRole('table')).toBeInTheDocument();
    expect(view.getByText('SO-100')).toBeInTheDocument();
    expect(view.getByText('USD 0.00')).toBeInTheDocument();
    expect(view.getByText('Pending')).toBeInTheDocument();
    const infoTip = view.getByRole('button', { name: 'Backend total' });
    expect(infoTip).toBeInTheDocument();
    expect(infoTip).toHaveAttribute('type', 'button');
  });
});
