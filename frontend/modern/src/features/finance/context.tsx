import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type Dispatch,
  type ReactNode,
  type SetStateAction
} from 'react';
import {
  useQuery,
  useQueryClient,
  type QueryKey,
  type UseQueryOptions,
  type UseQueryResult
} from '@tanstack/react-query';

import type {
  FinanceApi,
  FinanceFilters,
  FinanceHost,
  FinanceHostSnapshot,
  FinanceRequestParams,
  FinanceWriteMethod
} from '../../shared/contracts';
import { emptyFinanceFilters } from '../../shared/contracts';
import { createFinanceTranslator, type FinanceTranslator } from '../../shared/i18n';

export interface FinanceContextValue {
  host: FinanceHost;
  session: FinanceHostSnapshot;
  api: FinanceApi;
  filters: FinanceFilters;
  setFilters: Dispatch<SetStateAction<FinanceFilters>>;
  refresh(): Promise<void>;
  active: boolean;
  route: string;
  navigate(route: string): void;
  t: FinanceTranslator;
}

const FinanceContext = createContext<FinanceContextValue | null>(null);

export class FinanceInactiveError extends Error {
  constructor() {
    super('财务界面当前未激活');
    this.name = 'FinanceInactiveError';
  }
}

function guardedFinanceApi(api: FinanceApi, isActive: () => boolean): FinanceApi {
  const ensureActive = () => {
    if (!isActive()) throw new FinanceInactiveError();
  };
  return {
    async get<T>(path: string, params?: FinanceRequestParams, signal?: AbortSignal) {
      ensureActive();
      return await api.get<T>(path, params, signal);
    },
    async send<T>(path: string, method: FinanceWriteMethod, body?: unknown) {
      ensureActive();
      return await api.send<T>(path, method, body);
    },
    async upload<T>(path: string, formData: FormData) {
      ensureActive();
      return await api.upload<T>(path, formData);
    },
    async download(path: string, params: FinanceRequestParams, filename?: string) {
      ensureActive();
      await api.download(path, params, filename);
    }
  };
}

function identity(snapshot: FinanceHostSnapshot): readonly [string, string] {
  return [snapshot.organizationId, snapshot.userId];
}

function queryScope(snapshot: FinanceHostSnapshot): readonly ['finance', string, string] {
  return ['finance', snapshot.organizationId, snapshot.userId];
}

export interface FinanceProviderProps {
  active: boolean;
  api: FinanceApi;
  children: ReactNode;
  host: FinanceHost;
  initialFilters?: Partial<FinanceFilters> | undefined;
  route: string;
}

export function FinanceProvider({
  active,
  api,
  children,
  host,
  initialFilters,
  route
}: FinanceProviderProps) {
  const queryClient = useQueryClient();
  const session = useSyncExternalStore(
    useCallback((notify) => host.subscribe(() => notify()), [host]),
    host.getSnapshot,
    host.getSnapshot
  );
  const [filters, setFilters] = useState<FinanceFilters>(() => ({
    ...emptyFinanceFilters,
    ...initialFilters
  }));
  const [previousIdentity, setPreviousIdentity] = useState(() => identity(session));
  const activeRef = useRef(active);
  activeRef.current = active;
  const exposedApi = useMemo(() => guardedFinanceApi(api, () => activeRef.current), [api]);

  useEffect(() => {
    const nextIdentity = identity(session);
    if (nextIdentity[0] === previousIdentity[0] && nextIdentity[1] === previousIdentity[1]) return;
    void queryClient.cancelQueries({ queryKey: ['finance', ...previousIdentity] });
    queryClient.removeQueries({ queryKey: ['finance', ...previousIdentity] });
    setFilters({ ...emptyFinanceFilters });
    setPreviousIdentity(nextIdentity);
  }, [previousIdentity, queryClient, session]);

  useEffect(() => {
    if (!active) void queryClient.cancelQueries({ queryKey: queryScope(session) });
  }, [active, queryClient, session]);

  const refresh = useCallback(async () => {
    if (!active) return;
    await queryClient.invalidateQueries({ queryKey: queryScope(session) });
  }, [active, queryClient, session]);
  const navigate = useCallback((nextRoute: string) => host.navigate(nextRoute), [host]);
  const t = useMemo(() => createFinanceTranslator(session.locale), [session.locale]);
  const value = useMemo<FinanceContextValue>(() => ({
    host,
    session,
    api: exposedApi,
    filters,
    setFilters,
    refresh,
    active,
    route,
    navigate,
    t
  }), [active, exposedApi, filters, host, navigate, refresh, route, session, t]);

  return <FinanceContext.Provider value={value}>{children}</FinanceContext.Provider>;
}

export function useFinance(): FinanceContextValue {
  const value = useContext(FinanceContext);
  if (!value) throw new Error('useFinance must be used inside FinanceProvider');
  return value;
}

export type FinanceQueryOptions<TData, TError = Error> = Omit<
  UseQueryOptions<TData, TError, TData, QueryKey>,
  'queryKey'
> & {
  queryKey: QueryKey;
};

export function useFinanceQuery<TData, TError = Error>(
  options: FinanceQueryOptions<TData, TError>
): UseQueryResult<TData, TError> {
  const { active, session } = useFinance();
  const enabled = active && session.canView && (options.enabled ?? true);
  return useQuery<TData, TError, TData, QueryKey>({
    ...options,
    enabled,
    queryKey: [...queryScope(session), ...options.queryKey]
  });
}
