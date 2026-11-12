import { describe, expect, it, vi } from 'vitest';

import { createFinanceApi, FinanceApiError, FinanceScopeChangedError } from '../src/shared/api';
import { createFinanceHostFixture, defaultSession } from './fixtures';

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' }
  });
}

describe('FinanceApi', () => {
  it('sends same-origin scoped reads and unwraps ApiResponse.data', async () => {
    const { host } = createFinanceHostFixture();
    const fetchImpl = vi.fn<typeof fetch>(async () => jsonResponse({ success: true, data: { total: 0 }, message: 'OK' }));
    const api = createFinanceApi(host, { fetch: fetchImpl });
    const signal = new AbortController().signal;

    await expect(api.get('/records', { page: 2, search: '', omitted: undefined }, signal)).resolves.toEqual({ total: 0 });

    const [url, request] = fetchImpl.mock.calls[0] ?? [];
    expect(url).toBe('/api/v1/finance/records?page=2&search=');
    expect(request).toMatchObject({ credentials: 'same-origin', method: 'GET', signal });
  });

  it('rejects a response when the organization changes while it is in flight', async () => {
    const fixture = createFinanceHostFixture();
    let release: ((response: Response) => void) | undefined;
    const fetchImpl = vi.fn(() => new Promise<Response>((resolve) => { release = resolve; }));
    const api = createFinanceApi(fixture.host, { fetch: fetchImpl });
    const request = api.get('/overview');

    fixture.update({ ...defaultSession, organizationId: 'org-2' });
    release?.(jsonResponse({ success: true, data: { total: 10 } }));

    await expect(request).rejects.toBeInstanceOf(FinanceScopeChangedError);
  });

  it('passes cancellation to the read request', async () => {
    const { host } = createFinanceHostFixture();
    const fetchImpl = vi.fn((_url: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    }));
    const api = createFinanceApi(host, { fetch: fetchImpl });
    const controller = new AbortController();
    const request = api.get('/overview', undefined, controller.signal);

    controller.abort();

    await expect(request).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('does not replay failed writes and permits backend-authorized viewer actions', async () => {
    const manager = createFinanceHostFixture();
    const failedFetch = vi.fn(async () => jsonResponse({ success: false, data: null, message: 'busy' }, 503));
    const managerApi = createFinanceApi(manager.host, { fetch: failedFetch });

    await expect(managerApi.send('/groups', 'POST', { name: 'North' })).rejects.toBeInstanceOf(FinanceApiError);
    expect(failedFetch).toHaveBeenCalledTimes(1);

    const reader = createFinanceHostFixture({ ...defaultSession, canManage: false });
    const readerFetch = vi.fn(async () => jsonResponse({ success: true, data: { ready: true }, message: 'OK' }));
    const readerApi = createFinanceApi(reader.host, { fetch: readerFetch });
    await expect(readerApi.send('/screen', 'POST', {})).resolves.toEqual({ ready: true });
    expect(readerFetch).toHaveBeenCalledOnce();
  });

  it('notifies the host when the session expires', async () => {
    const fixture = createFinanceHostFixture();
    const api = createFinanceApi(fixture.host, {
      fetch: vi.fn(async () => jsonResponse({ success: false, data: null, message: 'expired' }, 401))
    });

    await expect(api.get('/overview')).rejects.toBeInstanceOf(FinanceApiError);
    expect(fixture.onSessionExpired).toHaveBeenCalledOnce();
  });
});
