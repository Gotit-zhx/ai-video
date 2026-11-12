import { useState } from "react";
import { Alert, Button, Space } from "antd";
import { FinanceModal } from "../../../shared/dialogs";
import type { Row } from "../../../shared/contracts";
import { useFinance } from "../context";
import {
  Check,
  Evidence,
  Feedback,
  requireEvidence,
  useCostAction,
} from "./common";
export function ConfirmOperation({
  title,
  description,
  onClose,
  operation,
  onDone,
  safety = false,
  button = "确认删除",
}: {
  title: string;
  description: string;
  onClose: () => void;
  operation: (metadata: Row) => Promise<unknown>;
  onDone: (value: unknown) => void | Promise<void>;
  safety?: boolean;
  button?: string;
}) {
  const { t, session, active } = useFinance();
  const [checked, setChecked] = useState(false),
    [metadata, setMetadata] = useState<Row>({});
  const action = useCostAction();
  if (!session.canManage || !active) return null;
  return (
    <FinanceModal open title={t(title)} onCancel={onClose} footer={null}>
      <Space orientation="vertical">
        <Alert type="warning" title={t(description)} />
        {safety && <Evidence value={metadata} onChange={setMetadata} />}
        <Check label="我已核对删除影响" value={checked} onChange={setChecked} />
        <Feedback action={action} />
        <Button
          danger
          loading={action.pending}
          disabled={!checked}
          onClick={() =>
            void action.run(
              async () => {
                if (safety) requireEvidence(metadata);
                return operation(metadata);
              },
              async (value) => {
                onClose();
                await onDone(value);
              },
            )
          }
        >
          {t(safety ? "暂存移除申请" : button)}
        </Button>
      </Space>
    </FinanceModal>
  );
}
