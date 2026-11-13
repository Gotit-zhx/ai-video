export type Row = Record<string, unknown>;

export interface FinanceFilters {
  start: string;
  end: string;
  shopId: string;
  groupId: string;
  platform: string;
  currency: string;
  displayCurrency: string;
  granularity: string;
  fxSnapshot: string;
  dateBasis: string;
}

export const emptyFinanceFilters: FinanceFilters = {
  start: "",
  end: "",
  shopId: "",
  groupId: "",
  platform: "",
  currency: "",
  displayCurrency: "",
  granularity: "",
  fxSnapshot: "",
  dateBasis: "",
};

export interface FinanceHostSnapshot {
  organizationId: string;
  userId: string;
  canView: boolean;
  canManage: boolean;
  locale: string;
  theme: string;
}

export type FinanceHostListener = (snapshot: FinanceHostSnapshot) => void;

export interface FinanceHost {
  getSnapshot(): FinanceHostSnapshot;
  subscribe(listener: FinanceHostListener): () => void;
  navigate(route: string): void;
  getLegacyUrl?(filters: Partial<FinanceFilters>): string;
  consumeAuthorizationNotice?(): {status: "success" | "error"; message: string} | null;
  onSessionExpired(): void;
}

export type FinanceRequestParam = string | number | boolean | null | undefined;
export type FinanceRequestParams = Record<string, FinanceRequestParam>;
export type FinanceWriteMethod = "POST" | "PUT" | "PATCH" | "DELETE";

export interface FinanceRequestOptions {
  headers?: HeadersInit;
  signal?: AbortSignal;
  timeoutMs?: number;
}

export interface FinanceApi {
  get<T>(
    path: string,
    params?: FinanceRequestParams,
    signal?: AbortSignal,
  ): Promise<T>;
  send<T>(
    path: string,
    method: FinanceWriteMethod,
    body?: unknown,
    options?: FinanceRequestOptions,
  ): Promise<T>;
  upload<T>(path: string, formData: FormData): Promise<T>;
  download(
    path: string,
    params: FinanceRequestParams,
    filename?: string,
    options?: FinanceRequestOptions,
  ): Promise<void>;
}
