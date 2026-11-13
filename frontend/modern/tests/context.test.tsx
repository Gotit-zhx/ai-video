import { useRef, useState } from 'react';
import { act, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { useFinance, useFinanceQuery } from '../src/features/finance/context';
import type { FinanceApi, FinanceWriteMethod } from '../src/shared/contracts';
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance
} from './fixtures';

function ContextProbe() {
  const finance = useFinance();
  const runs = useRef(0);
  const refreshResult = useFinanceQuery({
    queryKey: ['context-refresh'],
    queryFn: async () => `run-${++runs.current}`
  });
  return (
    <div>
      <output>{finance.session.organizationId}</output>
      <output>{finance.filters.currency}</output>
      <output>{finance.route}</output>
      <button onClick={() => finance.setFilters((current) => ({ ...current, currency: 'USD' }))}>currency</button>
      <button onClick={() => finance.navigate('/records')}>records</button>
      <button onClick={finance.refresh}>refresh</button>
      <output aria-label="refresh">{refreshResult.data}</output>
    </div>
  );
}

function QueryProbe({ queryFn }: { queryFn: (signal: AbortSignal) => Promise<string> }) {
  const result = useFinanceQuery({
    queryKey: ['probe'],
    queryFn: ({ signal }) => queryFn(signal)
  });
  return <output>{result.data ?? result.fetchStatus}</output>;
}

function InactiveWriteProbe() {
  const { api } = useFinance();
  const [message, setMessage] = useState('');
  return (
    <>
      <button onClick={async () => {
        try {
          await api.send('/groups', 'POST', { name: 'Blocked' });
        } catch (error) {
          setMessage(error instanceof Error ? error.message : 'unknown');
        }
      }}>write</button>
      <output>{message}</output>
    </>
  );
}

function FailedQueryProbe({ queryFn }: { queryFn: () => Promise<string> }) {
  const result = useFinanceQuery({ queryKey: ['failed-query'], queryFn });
  return <output>{result.status}</output>;
}

let capturedApi: FinanceApi | undefined;

function CaptureApiProbe() {
  capturedApi = useFinance().api;
  return null;
}

describe('finance context', () => {
  it('tracks host session, filters, routing and refresh state', async () => {
    const fixture = createFinanceHostFixture();
    const view = renderFinance(<ContextProbe />, {
      filters: { currency: 'CNY' },
      host: fixture.host,
      route: '/overview'
    });

    expect(view.getByText('org-1')).toBeInTheDocument();
    expect(view.getByText('CNY')).toBeInTheDocument();
    expect(view.getByText('/overview')).toBeInTheDocument();
    expect(await view.findByText('run-1')).toBeInTheDocument();

    act(() => fixture.update({ ...defaultSession, organizationId: 'org-2' }));
    expect(await view.findByText('org-2')).toBeInTheDocument();

    view.getByRole('button', { name: 'currency' }).click();
    expect(await view.findByText('USD')).toBeInTheDocument();

    view.getByRole('button', { name: 'records' }).click();
    expect(fixture.navigate).toHaveBeenCalledWith('/records');

    view.getByRole('button', { name: 'refresh' }).click();
    expect(await view.findByText('run-2')).toBeInTheDocument();
  });

  it('disables queries while inactive and cancels an active read when deactivated', async () => {
    let aborted = false;
    const queryFn = vi.fn((signal: AbortSignal) => new Promise<string>((resolve, reject) => {
      const onAbort = () => {
        aborted = true;
        reject(new DOMException('Aborted', 'AbortError'));
      };
      signal.addEventListener('abort', onAbort, { once: true });
      void resolve;
    }));
    const view = renderFinance(<QueryProbe queryFn={queryFn} />, { active: false });

    expect(queryFn).not.toHaveBeenCalled();

    view.rerender(<QueryProbe queryFn={queryFn} />);
    expect(queryFn).not.toHaveBeenCalled();

    view.unmount();

    const active = renderFinance(<QueryProbe queryFn={queryFn} />, { active: true });
    await waitFor(() => expect(queryFn).toHaveBeenCalledOnce());
    active.rerender(
      <QueryProbe queryFn={queryFn} />
    );
    active.unmount();
    await waitFor(() => expect(aborted).toBe(true));
  });

  it('disables direct API work while the finance surface is inactive', async () => {
    const sendSpy = vi.fn();
    const send: FinanceApi['send'] = async <T,>(
      path: string,
      method: FinanceWriteMethod,
      body?: unknown
    ) => {
      sendSpy(path, method, body);
      return { saved: true } as T;
    };
    const view = renderFinance(<InactiveWriteProbe />, {
      active: false,
      api: createFinanceApiFixture({ send })
    });

    view.getByRole('button', { name: 'write' }).click();

    expect(await view.findByText('财务界面当前未激活')).toBeInTheDocument();
    expect(sendSpy).not.toHaveBeenCalled();
  });

  it('does not automatically replay a failed read query', async () => {
    const queryFn = vi.fn(async () => {
      throw new Error('network unavailable');
    });
    const view = renderFinance(<FailedQueryProbe queryFn={queryFn} />);

    expect(await view.findByText('error', {}, { timeout: 3_000 })).toBeInTheDocument();
    expect(queryFn).toHaveBeenCalledOnce();
  });

  it('fences an API object captured before the surface is deactivated', async () => {
    const getSpy = vi.fn();
    const sendSpy = vi.fn();
    const get: FinanceApi['get'] = async <T,>(path: string) => {
      getSpy(path);
      return {} as T;
    };
    const send: FinanceApi['send'] = async <T,>(
      path: string,
      method: FinanceWriteMethod,
      body?: unknown
    ) => {
      sendSpy(path, method, body);
      return {} as T;
    };
    const view = renderFinance(<CaptureApiProbe />, {
      api: createFinanceApiFixture({ get, send })
    });
    const staleApi = capturedApi;
    expect(staleApi).toBeDefined();

    view.setActive(false);

    await expect(staleApi?.get('/overview')).rejects.toThrow('财务界面当前未激活');
    await expect(staleApi?.send('/groups', 'POST', {})).rejects.toThrow('财务界面当前未激活');
    expect(getSpy).not.toHaveBeenCalled();
    expect(sendSpy).not.toHaveBeenCalled();
  });
});
