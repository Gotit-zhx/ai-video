import { useEffect, useState } from "react";
import { Alert, Button, Checkbox, Space, Typography } from "antd";
import type { Row } from "../../../shared/contracts";
import { rows, text, record } from "../../../shared/rows";
import { FinanceModal } from "../../../shared/dialogs";
import { useFinance, useFinanceQuery } from "../context";
import { Failure, useOwnerFence } from "../presentation";
export function fieldIds(value: unknown): string[] {
  return Array.isArray(value)
    ? value.flatMap((item) => (typeof item === "string" ? [item] : []))
    : [];
}
export interface FieldSettingsProps {
  view: string;
  open: boolean;
  onClose: () => void;
  onSaved?: () => void;
}
export function FieldSettings(props: FieldSettingsProps) {
  const { session, active } = useFinance();
  return props.open && active ? (
    <FieldSession
      key={session.organizationId + ":" + session.userId + ":" + props.view}
      {...props}
    />
  ) : null;
}
function FieldSession({ view, open, onClose, onSaved }: FieldSettingsProps) {
  const { api, t, refresh } = useFinance(),
    owner = useOwnerFence();
  const [visible, setVisible] = useState<string[]>([]),
    [exported, setExported] = useState<string[]>([]),
    [error, setError] = useState<unknown>(),
    [busy, setBusy] = useState(false);
  const query = useFinanceQuery({
    queryKey: ["fields", view],
    enabled: open,
    queryFn: ({ signal }) => api.get<Row>("/fields/" + view, {}, signal),
  });
  useEffect(() => {
    setVisible(fieldIds(query.data?.visible));
    setExported(fieldIds(query.data?.exported));
  }, [query.data]);
  const fields = rows(query.data?.fields);
  const save = async () => {
    const valid = (ids: string[]) =>
      ids.length > 0 &&
      new Set(ids).size === ids.length &&
      ids.every((id) => fields.some((f) => f.id === id)) &&
      ids.some((id) =>
        fields.some((f) => f.id === id && !f.cny && !id.startsWith("fx_")),
      );
    if (!valid(visible) || !valid(exported)) {
      setError(new Error(t("显示和导出均须包含至少一个原始字段")));
      return;
    }
    setBusy(true);
    try {
      await api.send("/fields/" + view, "PUT", { visible, exported });
      if (owner.isCurrent()) {
        await refresh();
        onSaved?.();
        onClose();
      }
    } catch (e) {
      if (owner.isCurrent()) setError(e);
    } finally {
      if (owner.isCurrent()) setBusy(false);
    }
  };
  return (
    <FinanceModal
      open={open}
      title={t("个人字段设置")}
      onCancel={onClose}
      footer={
        <Space>
          <Button
            onClick={() => {
              const defaults = record(query.data?.defaults);
              setVisible(fieldIds(defaults?.visible));
              setExported(fieldIds(defaults?.exported));
            }}
          >
            {t("恢复默认")}
          </Button>
          <Button
            type="primary"
            loading={busy}
            disabled={!query.data}
            onClick={() => void save()}
          >
            {t("保存字段")}
          </Button>
        </Space>
      }
    >
      <Failure error={error || query.error} />
      <Alert
        type="info"
        title={t("仅保存当前公司和当前用户的显示及导出偏好。")}
      />
      {[
        [t("显示字段"), visible, setVisible],
        [t("导出字段"), exported, setExported],
      ].map(([label, selected, update]) => (
        <section key={String(label)}>
          <Typography.Title level={5}>{String(label)}</Typography.Title>
          <Checkbox.Group
            value={selected as string[]}
            onChange={(value) =>
              (update as (value: string[]) => void)(value.map(String))
            }
            options={fields.map((field) => ({
              label: t(text(field.label) || text(field.id) || ""),
              value: text(field.id) || "",
            }))}
          />
        </section>
      ))}
    </FinanceModal>
  );
}
