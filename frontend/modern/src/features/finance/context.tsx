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
  type SetStateAction,
} from "react";
import {
  useQuery,
  useQueryClient,
  type QueryKey,
  type UseQueryOptions,
  type UseQueryResult,
} from "@tanstack/react-query";

import type {
  FinanceApi,
  FinanceFilters,
  FinanceHost,
  FinanceHostSnapshot,
  FinanceRequestParams,
  FinanceRequestOptions,
  FinanceWriteMethod,
} from "../../shared/contracts";
import { defaultFinanceFilters } from "./domain";
import { createFinanceReads, type FinanceReads } from "./reads";
import {
  createFinanceTranslator,
  type FinanceTranslator,
} from "../../shared/i18n";

export interface FinanceContextValue {
  host: FinanceHost;
  session: FinanceHostSnapshot;
  api: FinanceApi;
  reads: FinanceReads;
  filters: FinanceFilters;
  setFilters: Dispatch<SetStateAction<FinanceFilters>>;
  refresh(): Promise<void>;
  suspendForNavigation(): void;
  active: boolean;
  route: string;
  navigate(route: string): void;
  t: FinanceTranslator;
}

const FinanceContext = createContext<FinanceContextValue | null>(null);

export class FinanceInactiveError extends Error {
  constructor() {
    super("财务界面当前未激活");
    this.name = "FinanceInactiveError";
  }
}

function guardedFinanceApi(
  api: FinanceApi,
  isActive: () => boolean,
): FinanceApi {
  const ensureActive = () => {
    if (!isActive()) throw new FinanceInactiveError();
  };
  return {
    async get<T>(
      path: string,
      params?: FinanceRequestParams,
      signal?: AbortSignal,
    ) {
      ensureActive();
      return await api.get<T>(path, params, signal);
    },
    async send<T>(
      path: string,
      method: FinanceWriteMethod,
      body?: unknown,
      options?: FinanceRequestOptions,
    ) {
      ensureActive();
      return await api.send<T>(path, method, body, options);
    },
    async upload<T>(path: string, formData: FormData) {
      ensureActive();
      return await api.upload<T>(path, formData);
    },
    async download(
      path: string,
      params: FinanceRequestParams,
      filename?: string,
      options?: FinanceRequestOptions,
    ) {
      ensureActive();
      await api.download(path, params, filename, options);
    },
  };
}

function identity(snapshot: FinanceHostSnapshot): readonly [string, string] {
  return [snapshot.organizationId, snapshot.userId];
}

function queryScope(
  snapshot: FinanceHostSnapshot,
): readonly ["finance", string, string] {
  return ["finance", snapshot.organizationId, snapshot.userId];
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
  active: hostActive,
  api,
  children,
  host,
  initialFilters,
  route,
}: FinanceProviderProps) {
  const [navigating, setNavigating] = useState(false);
  const active = hostActive && !navigating;
  const queryClient = useQueryClient();
  const session = useSyncExternalStore(
    useCallback((notify) => host.subscribe(() => notify()), [host]),
    host.getSnapshot,
    host.getSnapshot,
  );
  const [filters, setFilters] = useState<FinanceFilters>(() => ({
    ...defaultFinanceFilters(),
    ...initialFilters,
  }));
  const [previousIdentity, setPreviousIdentity] = useState(() =>
    identity(session),
  );
  const activeRef = useRef(active);
  activeRef.current = active;
  const exposedApi = useMemo(
    () => guardedFinanceApi(api, () => activeRef.current),
    [api],
  );

  const suspendForNavigation = useCallback(() => {
    // A lazy page can commit passive effects during document navigation. Close
    // dispatch synchronously, before the browser follows the return link.
    activeRef.current = false;
    setNavigating(true);
    void queryClient.cancelQueries({ queryKey: queryScope(session) });
  }, [queryClient, session.organizationId, session.userId]);
  useEffect(() => {
    if (!hostActive) setNavigating(false);
  }, [hostActive]);
  useEffect(() => {
    const resume = (event: PageTransitionEvent) => {
      if (event.persisted) setNavigating(false);
    };
    window.addEventListener("pagehide", suspendForNavigation);
    window.addEventListener("pageshow", resume);
    return () => {
      window.removeEventListener("pagehide", suspendForNavigation);
      window.removeEventListener("pageshow", resume);
    };
  }, [suspendForNavigation]);

  const ownerKey = JSON.stringify(identity(session));
  const ownerRef = useRef(ownerKey);
  ownerRef.current = ownerKey;
  // Host renders may replace the HTTP facade on every navigation. Capability
  // belongs to this owner/provider, while dispatch always uses its latest API.
  const apiRef = useRef(exposedApi);
  apiRef.current = exposedApi;
  const reads = useMemo(
    () =>
      createFinanceReads(
        {
          get: <T,>(
            path: string,
            params?: FinanceRequestParams,
            signal?: AbortSignal,
          ) => apiRef.current.get<T>(path, params, signal),
        },
        () => activeRef.current && ownerRef.current === ownerKey,
      ),
    [host, ownerKey],
  );

  useEffect(() => {
    const nextIdentity = identity(session);
    if (
      nextIdentity[0] === previousIdentity[0] &&
      nextIdentity[1] === previousIdentity[1]
    )
      return;
    void queryClient.cancelQueries({
      queryKey: ["finance", ...previousIdentity],
    });
    queryClient.removeQueries({ queryKey: ["finance", ...previousIdentity] });
    setFilters({ ...defaultFinanceFilters() });
    setPreviousIdentity(nextIdentity);
  }, [previousIdentity, queryClient, session]);

  useEffect(() => {
    if (!active)
      void queryClient.cancelQueries({ queryKey: queryScope(session) });
  }, [active, queryClient, session]);

  const refresh = useCallback(async () => {
    if (!active) return;
    await queryClient.invalidateQueries({ queryKey: queryScope(session) });
  }, [active, queryClient, session]);
  const navigate = useCallback(
    (nextRoute: string) => host.navigate(nextRoute),
    [host],
  );
  const t = useMemo(
    () => createFinanceTranslator(session.locale),
    [session.locale],
  );
  const value = useMemo<FinanceContextValue>(
    () => ({
      host,
      session,
      api: exposedApi,
      reads,
      filters,
      setFilters,
      refresh,
      suspendForNavigation,
      active,
      route,
      navigate,
      t,
    }),
    [
      active,
      exposedApi,
      reads,
      filters,
      host,
      navigate,
      refresh,
      suspendForNavigation,
      route,
      session,
      t,
    ],
  );

  return (
    <FinanceContext.Provider value={value}>{children}</FinanceContext.Provider>
  );
}

export function useFinance(): FinanceContextValue {
  const value = useContext(FinanceContext);
  if (!value) throw new Error("useFinance must be used inside FinanceProvider");
  return value;
}

export type FinanceQueryOptions<TData, TError = Error> = Omit<
  UseQueryOptions<TData, TError, TData, QueryKey>,
  "queryKey"
> & {
  queryKey: QueryKey;
};

export function useFinanceQuery<TData, TError = Error>(
  options: FinanceQueryOptions<TData, TError>,
): UseQueryResult<TData, TError> {
  const { active, session } = useFinance();
  const enabled = active && session.canView && (options.enabled ?? true);
  return useQuery<TData, TError, TData, QueryKey>({
    ...options,
    enabled,
    queryKey: [...queryScope(session), ...options.queryKey],
  });
}
