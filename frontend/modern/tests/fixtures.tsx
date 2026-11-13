import { useState, type Dispatch, type ReactNode, type SetStateAction } from 'react';
import { act, cleanup, render, within, type RenderResult } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { FinanceApiError } from '../src/shared/api';

import { FinanceRootProviders } from '../src/app/providers';
import { createShadowSurface, type ShadowSurface } from '../src/shared/shadow';
import type {
  FinanceApi,
  FinanceFilters,
  FinanceRequestParams,
  FinanceHost,
  FinanceHostSnapshot
} from '../src/shared/contracts';

// Vitest globals are disabled, so Testing Library cannot auto-register cleanup.
// Unmount observers/timers and drain queued notifications before jsdom teardown.
afterEach(async () => {
  await act(async () => {
    cleanup();
    await new Promise(resolve => setTimeout(resolve, 0));
  });
});

export const defaultSession: FinanceHostSnapshot = {
  organizationId: 'org-1',
  userId: 'user-1',
  canView: true,
  canManage: true,
  locale: 'zh-CN',
  theme: 'light'
};

export function createFinanceHostFixture(initial: FinanceHostSnapshot = defaultSession) {
  let snapshot = initial;
  const listeners = new Set<(next: FinanceHostSnapshot) => void>();
  const navigate = vi.fn<(route: string) => void>();
  const onSessionExpired = vi.fn<() => void>();
  const host: FinanceHost = {
    getSnapshot: () => snapshot,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    navigate,
    onSessionExpired
  };

  return {
    host,
    navigate,
    onSessionExpired,
    update(next: FinanceHostSnapshot) {
      snapshot = next;
      listeners.forEach((listener) => listener(next));
    }
  };
}

export function createFinanceApiFixture(
  overrides: Partial<FinanceApi> = {},
): FinanceApi {
  const legacyGet = overrides.get ?? vi.fn(async () => undefined);
  return {
    send: vi.fn(async () => undefined),
    upload: vi.fn(async () => undefined),
    download: vi.fn(async () => undefined),
    ...overrides,
    // Existing suites model the local legacy backend explicitly. Current DTO
    // tests supply their own get function after this factory.
    get: vi.fn(
      async <T,>(
        path: string,
        params?: FinanceRequestParams,
        signal?: AbortSignal,
      ) => {
        if (
          [
            "/workspace/query",
            "/workspace/costs/context",
            "/workspace/status",
          ].includes(path)
        )
          throw new FinanceApiError("Optional endpoint unavailable", 404);
        return legacyGet<T>(path, params, signal);
      },
    ),
  } as FinanceApi;
}

export interface RenderFinanceOptions {
  active?: boolean;
  api?: FinanceApi;
  filters?: Partial<FinanceFilters>;
  host?: FinanceHost;
  route?: string;
}

export interface FinanceRenderResult extends RenderResult {
  surface: ShadowSurface;
  setActive(active: boolean): void;
}

export function renderFinance(
  children: ReactNode,
  options: RenderFinanceOptions = {}
): FinanceRenderResult {
  const container = document.createElement('div');
  document.body.append(container);
  const surface = createShadowSurface(container, ':host { color-scheme: light dark; }');
  const fixture = createFinanceHostFixture();
  let updateActive: Dispatch<SetStateAction<boolean>> | undefined;
  const Wrapper = ({ children: wrappedChildren }: { children: ReactNode }) => {
    const [active, setActive] = useState(options.active ?? true);
    updateActive = setActive;
    return (
      <FinanceRootProviders
        active={active}
        api={options.api ?? createFinanceApiFixture()}
        host={options.host ?? fixture.host}
        initialFilters={options.filters}
        route={options.route ?? '/overview'}
        surface={surface}
      >
        {wrappedChildren}
      </FinanceRootProviders>
    );
  };
  const result = render(children, {
    baseElement: container,
    container: surface.mountPoint,
    wrapper: Wrapper
  });

  return Object.assign(result, within(surface.mountPoint), {
    surface,
    setActive(active: boolean) {
      act(() => updateActive?.(active));
    }
  });
}
