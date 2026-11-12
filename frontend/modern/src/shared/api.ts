import type {
  FinanceApi,
  FinanceHost,
  FinanceHostSnapshot,
  FinanceRequestParams,
  FinanceWriteMethod
} from './contracts';

interface ApiResponse<T> {
  success: boolean;
  data: T;
  message: string;
}

export class FinanceApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'FinanceApiError';
    this.status = status;
  }
}

export class FinanceScopeChangedError extends Error {
  constructor() {
    super('财务会话范围已变化，请重新加载');
    this.name = 'FinanceScopeChangedError';
  }
}

export interface CreateFinanceApiOptions {
  baseUrl?: string;
  fetch?: typeof fetch;
  saveBlob?: (blob: Blob, filename: string) => void;
}

function scope(snapshot: FinanceHostSnapshot): string {
  return JSON.stringify([snapshot.organizationId, snapshot.userId]);
}

function requireScope(host: FinanceHost): string {
  const snapshot = host.getSnapshot();
  if (!snapshot.organizationId || !snapshot.userId || !snapshot.canView) {
    throw new FinanceApiError('没有财务 BI 查看权限', 403);
  }
  return scope(snapshot);
}

function ensureScope(host: FinanceHost, expected: string): void {
  if (scope(host.getSnapshot()) !== expected) throw new FinanceScopeChangedError();
}

async function readScopedBody<T>(
  host: FinanceHost,
  expectedScope: string,
  read: () => Promise<T>
): Promise<T> {
  try {
    return await read();
  } finally {
    ensureScope(host, expectedScope);
  }
}

function requestUrl(baseUrl: string, path: string, params?: FinanceRequestParams): string {
  if (!path.startsWith('/') || path.startsWith('//')) {
    throw new TypeError('Finance API path must start with one slash');
  }
  const separator = path.includes('?') ? '&' : '?';
  const search = new URLSearchParams();
  if (params) {
    for (const [key, value] of Object.entries(params)) {
      if (value !== null && value !== undefined) search.append(key, String(value));
    }
  }
  const suffix = search.toString();
  return `${baseUrl}${path}${suffix ? `${separator}${suffix}` : ''}`;
}

async function readMessage(response: Response): Promise<string> {
  try {
    const value = await response.clone().json() as Partial<ApiResponse<unknown>>;
    if (typeof value.message === 'string' && value.message) return value.message;
  } catch {
    // The status text below is the safe fallback for non-JSON errors.
  }
  return response.statusText || `HTTP ${response.status}`;
}

async function unwrap<T>(response: Response): Promise<T> {
  const value = await response.json() as Partial<ApiResponse<T>>;
  if (value.success === false) {
    throw new FinanceApiError(value.message || '财务请求失败', response.status);
  }
  if (!Object.prototype.hasOwnProperty.call(value, 'data')) {
    throw new FinanceApiError('财务接口响应缺少 data', response.status);
  }
  return value.data as T;
}

function browserSaveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.hidden = true;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function responseFilename(response: Response): string | undefined {
  const disposition = response.headers.get('Content-Disposition');
  if (!disposition) return undefined;
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (encoded) return decodeURIComponent(encoded);
  return disposition.match(/filename="?([^";]+)"?/i)?.[1];
}

export function createFinanceApi(host: FinanceHost, options: CreateFinanceApiOptions = {}): FinanceApi {
  const baseUrl = options.baseUrl ?? '/api/v1/finance';
  const fetchImpl = options.fetch ?? globalThis.fetch.bind(globalThis);
  const saveBlob = options.saveBlob ?? browserSaveBlob;

  async function requestJson<T>(
    path: string,
    method: 'GET' | FinanceWriteMethod,
    init: Omit<RequestInit, 'credentials' | 'method'>,
    params?: FinanceRequestParams
  ): Promise<T> {
    const expectedScope = requireScope(host);
    const response = await fetchImpl(requestUrl(baseUrl, path, params), {
      ...init,
      credentials: 'same-origin',
      method
    });
    ensureScope(host, expectedScope);
    if (response.status === 401) host.onSessionExpired();
    if (!response.ok) {
      const message = await readScopedBody(host, expectedScope, () => readMessage(response));
      throw new FinanceApiError(message, response.status);
    }
    const data = await readScopedBody(host, expectedScope, () => unwrap<T>(response));
    return data;
  }

  return {
    get<T>(path: string, params?: FinanceRequestParams, signal?: AbortSignal) {
      return requestJson<T>(path, 'GET', {
        cache: 'no-store',
        ...(signal ? { signal } : {})
      }, params);
    },
    send<T>(path: string, method: FinanceWriteMethod, body?: unknown) {
      return requestJson<T>(path, method, {
        ...(body === undefined ? {} : {
          body: JSON.stringify(body),
          headers: { 'Content-Type': 'application/json' }
        })
      });
    },
    upload<T>(path: string, formData: FormData) {
      return requestJson<T>(path, 'POST', { body: formData });
    },
    async download(path: string, params: FinanceRequestParams, filename?: string) {
      const expectedScope = requireScope(host);
      const response = await fetchImpl(requestUrl(baseUrl, path, params), {
        cache: 'no-store',
        credentials: 'same-origin',
        method: 'GET'
      });
      ensureScope(host, expectedScope);
      if (response.status === 401) host.onSessionExpired();
      if (!response.ok) {
        const message = await readScopedBody(host, expectedScope, () => readMessage(response));
        throw new FinanceApiError(message, response.status);
      }
      const blob = await readScopedBody(host, expectedScope, () => response.blob());
      saveBlob(blob, filename ?? responseFilename(response) ?? 'finance-download');
    }
  };
}
