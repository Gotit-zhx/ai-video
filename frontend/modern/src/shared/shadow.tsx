import { createContext, useContext, type ReactNode } from 'react';
import { App as AntdApp, ConfigProvider, theme } from 'antd';
import { StyleProvider } from '@ant-design/cssinjs';
import zhCN from 'antd/es/locale/zh_CN';
import enUS from 'antd/es/locale/en_US';
import idID from 'antd/es/locale/id_ID';
import thTH from 'antd/es/locale/th_TH';
import viVN from 'antd/es/locale/vi_VN';
import msMY from 'antd/es/locale/ms_MY';
import { normalizeFinanceLocale } from './i18n';

const componentLocales = {zh:zhCN,en:enUS,id:idID,th:thTH,vi:viVN,ms:msMY,fil:enUS};

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
  localeName?: string;
}

const ShadowSurfaceContext = createContext<ShadowSurface | null>(null);

export function useFinanceSurface(): ShadowSurface {
  const surface = useContext(ShadowSurfaceContext);
  if (!surface) throw new Error('useFinanceSurface must be used inside FinanceUiProvider');
  return surface;
}

export function FinanceUiProvider({ children, surface, themeName = 'light', localeName = 'zh-CN' }: FinanceUiProviderProps) {
  return (
    <StyleProvider container={surface.styleContainer} hashPriority="high">
      <ConfigProvider
        locale={componentLocales[normalizeFinanceLocale(localeName)]}
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
