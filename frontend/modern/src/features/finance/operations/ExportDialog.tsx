import { useEffect, useRef, useState } from "react";
import { Alert, Button, Descriptions, Select, Space } from "antd";
import { FinanceModal } from "../../../shared/dialogs";
import type { FinanceRequestParams } from "../../../shared/contracts";
import { useFinance } from "../context";
import { Failure, useOwnerFence } from "../presentation";
import { FieldSettings } from "./FieldSettings";
export interface ExportDialogProps {
  open: boolean;
  selection: FinanceRequestParams;
  onClose: () => void;
  path?: string;
  viewOptions?: readonly { value: string; label: string }[];
}
export function ExportDialog(props: ExportDialogProps) {
  const { session, active } = useFinance();
  return props.open && active ? (
    <ExportSession
      key={session.organizationId + ":" + session.userId}
      {...props}
    />
  ) : null;
}
function ExportSession({
  selection,
  onClose,
  path = "/export",
  viewOptions,
}: ExportDialogProps) {
  const { api, t } = useFinance(),
    owner = useOwnerFence();
  const [snapshot] = useState(() => ({ ...selection })),
    [format, setFormat] = useState("xlsx"),
    [selectedView, setSelectedView] = useState(() =>
      String(selection.view || viewOptions?.[0]?.value || "ALL"),
    ),
    [fieldsOpen, setFieldsOpen] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(),
    [done, setDone] = useState(false);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  const close = () => {
    controller.current?.abort();
    onClose();
  };
  const run = async () => {
    controller.current = new AbortController();
    setBusy(true);
    setError(undefined);
    try {
      await api.download(
        path,
        { ...snapshot, ...(viewOptions ? { view: selectedView } : {}), format },
        undefined,
        {
          signal: controller.current.signal,
          timeoutMs: 180000,
        },
      );
      if (owner.isCurrent()) setDone(true);
    } catch (e) {
      if (owner.isCurrent()) setError(e);
    } finally {
      if (owner.isCurrent()) setBusy(false);
    }
  };
  return (
    <FinanceModal
      open
      title={t("导出全部查询结果")}
      onCancel={close}
      footer={
        <Space>
          <Button onClick={close}>{t(busy ? "取消导出" : "关闭")}</Button>
          <Button type="primary" loading={busy} onClick={() => void run()}>
            {t("开始导出")}
          </Button>
        </Space>
      }
    >
      <Descriptions
        column={1}
        items={[
          "start",
          "end",
          "shopId",
          "groupId",
          "currency",
          "displayCurrency",
          "fxSnapshot",
          "orderScope",
          "numbers",
        ]
          .filter((key) => snapshot[key])
          .map((key) => ({
            key,
            label: t(
              (
                {
                  start: "开始日期",
                  end: "结束日期",
                  shopId: "店铺",
                  groupId: "分组",
                  currency: "原币",
                  displayCurrency: "展示币种",
                  fxSnapshot: "历史汇率快照",
                  orderScope: "订单范围",
                  numbers: "精确编号",
                } as Record<string, string>
              )[key] || key,
            ),
            children: String(snapshot[key]),
          }))}
      />
      {viewOptions && (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Select
            aria-label={t("导出类型")}
            style={{ width: "100%" }}
            value={selectedView}
            disabled={busy}
            onChange={setSelectedView}
            options={viewOptions.map((option) => ({
              value: option.value,
              label: t(option.label),
            }))}
          />
          <Button
            type="link"
            disabled={busy}
            onClick={() => setFieldsOpen(true)}
          >
            {t("配置导出字段")}
          </Button>
        </Space>
      )}
      <Select
        aria-label={t("导出格式")}
        value={format}
        disabled={busy}
        onChange={setFormat}
        options={[
          { value: "xlsx", label: "Excel" },
          { value: "csv", label: "CSV" },
        ]}
      />
      <Alert
        type="info"
        title={t(
          "导出范围已冻结，覆盖全部查询页；最多 100,000 行，超时 180 秒。",
        )}
        description={t(
          "原币与人民币按业务日历史汇率区分。销售收入不同于平台净结算；已结算不代表银行到账。全部订单按店铺下单日，已结算订单与成本按首次结算日；刷单保留原始记录但排除经营汇总，缺失字段不补零。CSV 仅含明细，Excel 包含口径与汇率证据。",
        )}
      />
      <Failure error={error} />
      {done && <Alert type="success" title={t("已请求浏览器保存文件")} />}
      {viewOptions && (
        <FieldSettings
          view={selectedView}
          open={fieldsOpen}
          onClose={() => setFieldsOpen(false)}
          onSaved={() => setFieldsOpen(false)}
        />
      )}
    </FinanceModal>
  );
}
