import { App, Drawer, Modal } from 'antd';
import type { DrawerProps, ModalProps } from 'antd';

import { useFinanceSurface } from './shadow';

export function FinanceModal(props: ModalProps) {
  const surface = useFinanceSurface();
  return <Modal {...props} getContainer={surface.overlayContainer} />;
}

export function FinanceDrawer(props: DrawerProps) {
  const surface = useFinanceSurface();
  return <Drawer {...props} getContainer={surface.overlayContainer} />;
}

export function useFinanceDialogs() {
  return App.useApp();
}
