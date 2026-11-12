import { Select } from 'antd';
import { waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { FinanceDrawer, FinanceModal, useFinanceDialogs } from '../src/shared/dialogs';
import { renderFinance } from './fixtures';

describe('ShadowRoot UI surface', () => {
  it('keeps application styles, popups and dialogs inside the finance shadow root', async () => {
    const view = renderFinance(
      <>
        <Select
          aria-label="Currency"
          open
          options={[{ label: 'USD', value: 'USD' }]}
          value="USD"
        />
        <FinanceModal open title="Record detail" footer={null}>
          Detail body
        </FinanceModal>
      </>
    );

    expect(view.surface.styleContainer.querySelector('style[data-finance-base-styles]')).toBeInTheDocument();
    expect(view.surface.overlayContainer.querySelector('.ant-select-dropdown')).toBeInTheDocument();
    expect(view.surface.overlayContainer.querySelector('.ant-modal-root')).toBeInTheDocument();
    expect(view.surface.shadowRoot.textContent).toContain('Detail body');
    expect(document.body.querySelector('.ant-select-dropdown')).toBeNull();
    expect(document.body.querySelector('.ant-modal-root')).toBeNull();
  });

  it('keeps drawer and App dialog utilities inside the shadow root', async () => {
    function DialogTools() {
      const dialogs = useFinanceDialogs();
      return (
        <>
          <button onClick={() => dialogs.modal.info({ content: 'Context modal' })}>modal</button>
          <button onClick={() => dialogs.message.info('Context message')}>message</button>
          <FinanceDrawer open title="Drawer title">Drawer body</FinanceDrawer>
        </>
      );
    }

    const view = renderFinance(<DialogTools />);
    view.getByRole('button', { name: 'modal' }).click();
    view.getByRole('button', { name: 'message' }).click();

    expect(view.surface.shadowRoot.textContent).toContain('Drawer body');
    await waitFor(() => expect(view.surface.shadowRoot.textContent).toContain('Context modal'));
    await waitFor(() => expect(view.surface.shadowRoot.textContent).toContain('Context message'));
    expect(document.body.textContent).not.toContain('Context modal');
    expect(document.body.textContent).not.toContain('Context message');
  });
});
