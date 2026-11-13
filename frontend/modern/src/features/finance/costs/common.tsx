import { useEffect, useRef, useState, type ReactNode } from "react";
import {
  Alert,
  Button,
  Checkbox,
  Input,
  Pagination,
  Select,
  Space,
} from "antd";
import type { FinanceRequestParams, Row } from "../../../shared/contracts";
import { BusinessTable, MetricCard } from "../../../shared/components";
import { amountText, numeric, record, rows, text } from "../../../shared/rows";
import { useFinance, useFinanceQuery } from "../context";
import { selectionKey } from "../domain";
import {
  Failure,
  useOwnerFence,
  useVisible,
  type ColumnSpec,
} from "../presentation";
import { operationReceipt } from "./domain";
export function Field({
  label,
  value,
  onChange,
  type = "text",
  disabled = false,
  maxLength = 2000,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  disabled?: boolean;
  maxLength?: number;
}) {
  const { t } = useFinance();
  return (
    <label>
      <span>{t(label)}</span>
      <Input
        aria-label={t(label)}
        value={value}
        type={type}
        disabled={disabled}
        maxLength={maxLength}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}
export function Choice({
  label,
  value,
  onChange,
  options,
  disabled = false,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  options: (string | { value: string; label: string })[];
  disabled?: boolean;
}) {
  const { t } = useFinance();
  return (
    <label>
      <span>{t(label)}</span>
      <Select
        aria-label={t(label)}
        style={{ minWidth: 170, maxWidth: "100%" }}
        showSearch={{ optionFilterProp: "label" }}
        value={value}
        disabled={disabled}
        onChange={onChange}
        options={options.map((o) =>
          typeof o === "string"
            ? { value: o, label: t(o || "全部") }
            : { ...o, label: t(o.label) },
        )}
      />
    </label>
  );
}
export function Check({
  label,
  value,
  onChange,
}: {
  label: string;
  value: boolean;
  onChange: (v: boolean) => void;
}) {
  const { t } = useFinance();
  return (
    <Checkbox
      aria-label={t(label)}
      checked={value}
      onChange={(e) => onChange(e.target.checked)}
    >
      {t(label)}
    </Checkbox>
  );
}
export function Evidence({
  value,
  onChange,
  importing = false,
}: {
  value: Row;
  onChange: (v: Row) => void;
  importing?: boolean;
}) {
  const update = (key: string, v: unknown) => onChange({ ...value, [key]: v });
  return (
    <Space orientation="vertical">
      <Field
        label="操作原因"
        value={text(value.reason) || ""}
        onChange={(v) => update("reason", v)}
      />
      <Field
        label="依据编号或说明"
        value={text(value.evidenceRef) || ""}
        onChange={(v) => update("evidenceRef", v)}
      />
      {importing && (
        <>
          <Field
            label="成本计价单位"
            value={text(value.unit) || ""}
            onChange={(v) => update("unit", v)}
          />
          <Choice
            label="编号类型"
            value={text(value.identifierType) || ""}
            onChange={(v) => update("identifierType", v)}
            options={[{ value: "", label: "请选择" }, "SKU", "SPU", "MIXED"]}
          />
          <Field
            label="来源标识"
            value={text(value.sourceKey) || ""}
            onChange={(v) => update("sourceKey", v)}
          />
          <Check
            label="确认 SPU 适用规格使用统一成本"
            value={value.spuUniform === true}
            onChange={(v) => update("spuUniform", v)}
          />
          <Check
            label="明确修订已有价格"
            value={value.revision === true}
            onChange={(v) => update("revision", v)}
          />
        </>
      )}
    </Space>
  );
}
export function requireEvidence(meta: Row) {
  if (!text(meta.reason)?.trim() || !text(meta.evidenceRef)?.trim())
    throw Error("请填写操作原因和核对依据");
}
/** Single execution + live ownership/permission fence; no automatic mutation retries. */
export function useCostAction(management = true) {
  const { session, active, t } = useFinance();
  const fence = useOwnerFence();
  const permission = useRef(false);
  permission.current =
    active && session.canView && (!management || session.canManage);
  const busy = useRef(false);
  const [pending, setPending] = useState(false),
    [error, setError] = useState<unknown>(),
    [notice, setNotice] = useState("");
  const valid = () => fence.isCurrent() && permission.current;
  async function run(
    operation: () => Promise<unknown>,
    after?: (v: unknown) => void | Promise<void>,
  ) {
    if (busy.current || !valid()) return;
    busy.current = true;
    setPending(true);
    setError(undefined);
    setNotice("");
    try {
      const v = await operation();
      if (!valid()) return;
      setNotice(t(operationReceipt(v)));
      await after?.(v);
    } catch (e) {
      if (valid()) setError(e);
    } finally {
      busy.current = false;
      if (valid()) setPending(false);
    }
  }
  return { run, pending, error, notice, setError, setNotice, valid };
}
export function Feedback({
  action,
}: {
  action: ReturnType<typeof useCostAction>;
}) {
  return (
    <>
      <Failure error={action.error} />
      {action.notice && (
        <Alert role="status" type="info" title={action.notice} />
      )}
    </>
  );
}
export function useCostRead(
  path: string,
  params: FinanceRequestParams,
  interval: number | ((data: Row | undefined, error: unknown) => number) = 0,
  enabled = true,
) {
  const { api } = useFinance();
  const visible = useVisible();
  return useFinanceQuery({
    queryKey: [path, selectionKey(params)],
    enabled,
    queryFn: async ({ signal }) => {
      const timeout = new AbortController();
      const abort = () => timeout.abort();
      signal.addEventListener("abort", abort, { once: true });
      const timer = setTimeout(abort, 30000);
      try {
        return await api.get<Row>(path, params, timeout.signal);
      } finally {
        clearTimeout(timer);
        signal.removeEventListener("abort", abort);
      }
    },
    refetchInterval: (query) =>
      visible
        ? (typeof interval === "function"
            ? interval(query.state.data, query.state.error)
            : interval) || false
        : false,
    refetchIntervalInBackground: false,
  });
}
export function Pages({
  data,
  page,
  onChange,
}: {
  data: Row | undefined;
  page: number;
  onChange: (p: number) => void;
}) {
  return (
    <Pagination
      responsive
      current={page}
      pageSize={numeric(data?.pageSize) || 50}
      total={numeric(data?.total ?? data?.recordTotal) || 0}
      showSizeChanger={false}
      onChange={onChange}
    />
  );
}
export function DataTable({
  data,
  columns,
  actions,
}: {
  data: unknown;
  columns: readonly ColumnSpec[];
  actions?: (row: Row) => ReactNode;
}) {
  const { t } = useFinance();
  return (
    <BusinessTable<Row>
      rowKey={(r) =>
        text(r.id) ||
        [
          r.import_id,
          r.source_row,
          r.shop_id,
          r.order_id,
          r.return_number,
          r.sku,
          r.warehouse,
          r.product_id,
          r.sku_id,
        ]
          .map((v) => text(v) || "")
          .filter(Boolean)
          .join(":") ||
        JSON.stringify(r)
      }
      pagination={false}
      dataSource={rows(data)}
      columns={[
        ...columns.map(([key, label, type]) => ({
          key,
          title: t(label),
          dataIndex: key,
          render: (value: unknown) =>
            type === "money" ? amountText(value, "CNY") : (text(value) ?? "—"),
        })),
        ...(actions
          ? [
              {
                key: "actions",
                title: t("操作"),
                render: (_: unknown, r: Row) => actions(r),
              },
            ]
          : []),
      ]}
    />
  );
}
export function CostTotal({
  data,
  camel = false,
  label = "商品成本合计（全部查询页）",
}: {
  data: unknown;
  camel?: boolean;
  label?: string;
}) {
  const { t } = useFinance();
  const r = record(data) || {};
  const value = r[camel ? "amountCny" : "amount_cny"],
    known = r[camel ? "knownAmountCny" : "known_amount_cny"];
  return (
    <MetricCard
      label={t(label)}
      value={amountText(value, "CNY")}
      detail={
        value == null ? (
          <span>
            {t("已算部分")} {amountText(known, "CNY")} · {t("待补齐")}{" "}
            {text(r[camel ? "pendingRecords" : "pending_count"]) ?? "—"}
          </span>
        ) : undefined
      }
    />
  );
}
export function SearchNumbers({
  onSearch,
  label = "精确订单编号",
  button = "查询",
  initial = "",
}: {
  onSearch: (s: string) => void;
  label?: string;
  button?: string;
  initial?: string;
}) {
  const { t } = useFinance();
  const [value, set] = useState(initial);
  return (
    <Space wrap>
      <Field label={label} value={value} onChange={set} />
      <Button onClick={() => onSearch(value.trim())}>{t(button)}</Button>
    </Space>
  );
}
export function useDownload() {
  const { api, t } = useFinance();
  const action = useCostAction(false);
  const controllers = useRef(new Set<AbortController>());
  useEffect(() => () => controllers.current.forEach((c) => c.abort()), []);
  return {
    ...action,
    download: (
      path: string,
      selection: FinanceRequestParams,
      filename: string,
    ) =>
      action.run(async () => {
        const c = new AbortController();
        controllers.current.add(c);
        try {
          await api.download(path, { ...selection }, filename, {
            signal: c.signal,
            timeoutMs: 180000,
          });
          return t("已请求浏览器保存");
        } finally {
          controllers.current.delete(c);
        }
      }),
  };
}
