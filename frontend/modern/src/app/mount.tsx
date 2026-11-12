import { createRoot, type Root } from 'react-dom/client';

import { createFinanceApi } from '../shared/api';
import type { FinanceApi, FinanceFilters, FinanceHost } from '../shared/contracts';
import { createShadowSurface, type ShadowSurface } from '../shared/shadow';
import styles from './styles.css?inline';
import { FinanceRoot } from './FinanceRoot';

export interface MountFinanceRootOptions {
  active?: boolean;
  api?: FinanceApi;
  host: FinanceHost;
  initialFilters?: Partial<FinanceFilters> | undefined;
  route?: string;
}

export interface FinanceRootHandle {
  readonly surface: ShadowSurface;
  render(options: MountFinanceRootOptions): void;
  dispose(): void;
}

export function mountFinanceRoot(
  container: HTMLElement,
  initialOptions: MountFinanceRootOptions
): FinanceRootHandle {
  const surface = createShadowSurface(container, styles);
  const root: Root = createRoot(surface.mountPoint);

  const render = (options: MountFinanceRootOptions) => {
    root.render(
      <FinanceRoot
        active={options.active ?? true}
        api={options.api ?? createFinanceApi(options.host)}
        host={options.host}
        initialFilters={options.initialFilters}
        route={options.route ?? '/overview'}
        surface={surface}
      />
    );
  };

  render(initialOptions);
  return {
    surface,
    render,
    dispose() {
      root.unmount();
      surface.shadowRoot.replaceChildren();
    }
  };
}
