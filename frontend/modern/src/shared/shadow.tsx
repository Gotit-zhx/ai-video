import { createContext, useContext, type ReactNode } from 'react';
import { App as AntdApp, ConfigProvider, theme } from 'antd';
import { StyleProvider } from '@ant-design/cssinjs';

export interface ShadowSurface {
  host: HTMLElement;
  shadowRoot: ShadowRoot;
  styleContainer: HTMLDivElement;
  mountPoint: HTMLDivElement;
  overlayContainer: HTMLDivElement;
}

export function createShadowSurface(host: HTMLElement, cssText: string): ShadowSurface {
  const shadowRoot = host.shadowRoot ?? host.attachShadow({ mode: 'open' });
  shadowRoot.replaceChildren();

  const styleContainer = document.createElement('div');
  styleContainer.dataset.financeStyles = '';
  styleContainer.setAttribute('aria-hidden', 'true');

  const baseStyle = document.createElement('style');
  baseStyle.dataset.financeBaseStyles = '';
  baseStyle.textContent = cssText;
  styleContainer.append(baseStyle);

  const mountPoint = document.createElement('div');
  mountPoint.dataset.financeMount = '';

  const overlayContainer = document.createElement('div');
  overlayContainer.dataset.financeOverlays = '';

  shadowRoot.append(styleContainer, mountPoint, overlayContainer);
  return { host, shadowRoot, styleContainer, mountPoint, overlayContainer };
}

export interface FinanceUiProviderProps {
  children: ReactNode;
  surface: ShadowSurface;
  themeName?: string;
}

const ShadowSurfaceContext = createContext<ShadowSurface | null>(null);

export function useFinanceSurface(): ShadowSurface {
  const surface = useContext(ShadowSurfaceContext);
  if (!surface) throw new Error('useFinanceSurface must be used inside FinanceUiProvider');
  return surface;
}

export function FinanceUiProvider({ children, surface, themeName = 'light' }: FinanceUiProviderProps) {
  return (
    <StyleProvider container={surface.styleContainer} hashPriority="high">
      <ConfigProvider
        getPopupContainer={() => surface.overlayContainer}
        theme={{
          algorithm: themeName === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
          cssVar: { key: 'finance-modern' },
          hashed: false
        }}
      >
        <ShadowSurfaceContext.Provider value={surface}>
          <AntdApp className="finance-modern-app">{children}</AntdApp>
        </ShadowSurfaceContext.Provider>
      </ConfigProvider>
    </StyleProvider>
  );
}
