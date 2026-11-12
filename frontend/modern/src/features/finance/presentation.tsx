import { useEffect, useRef, useState, type ReactNode } from "react";
import { Alert, Image, Space, Typography } from "antd";
import { BusinessTable, Panel, StatusTag } from "../../shared/components";
import type { FinanceRequestParams, Row } from "../../shared/contracts";
import { amountText, numeric, record, rows, text } from "../../shared/rows";
import { useFinance, useFinanceQuery } from "./context";
import { selectionKey, timestampText } from "./domain";
export type ColumnSpec = readonly [
  key: string,
  label: string,
  type?: "money" | "number" | "status",
];
export function Failure({ error }: { error: unknown }) {
  const { t } = useFinance();
  return error ? (
    <Alert
      type="error"
      showIcon
      title={t(error instanceof Error ? error.message : "请求失败")}
    />
  ) : null;
}
export function valueText(value: unknown): string {
  return text(value) ?? "—";
}
export function FactTable({
  data,
  columns,
  title,
  onRowClick,
}: {
  data: unknown;
  columns: readonly ColumnSpec[];
  title?: string;
  onRowClick?: (row: Row) => void;
}) {
  const { t } = useFinance();
  const table = (
    <BusinessTable<Row>
      rowKey={(row) =>
        text(row.id) || text(row.source_key) || JSON.stringify(row)
      }
      dataSource={rows(data)}
      pagination={false}
      columns={columns.map(([key, label, type]) => ({
        key,
        dataIndex: key,
        title: t(label),
        render: (value: unknown, row: Row) =>
          type === "money" ? (
            amountText(value, text(row.currency))
          ) : type === "status" ? (
            <StatusTag>{t(valueText(value))}</StatusTag>
          ) : (
            valueText(value)
          ),
      }))}
      {...(onRowClick
        ? {
            onRow: (row) => ({
              onClick: () => onRowClick(row),
              tabIndex: 0,
              onKeyDown: (event) => {
                if (event.key === "Enter") onRowClick(row);
              },
            }),
          }
        : {})}
    />
  );
  return title ? <Panel title={t(title)}>{table}</Panel> : table;
}
export function ScopeEvidence({ data }: { data: unknown }) {
  const { t, filters } = useFinance();
  const source = record(data),
    fx = record(source?.fx),
    cache = record(source?.cache),
    calculation = record(source?.settlementCalculation),
    resolved = record(source?.filters);
  return (
    <Space orientation="vertical" size="small">
      {resolved && (
        <Typography.Text>
          {t("当前口径")}:{" "}
          {text(resolved.displayCurrency) ||
            text(resolved.currency) ||
            t(
              filters.displayCurrency === "ORIGINAL"
                ? "全部原币（分币种展示）"
                : "尚无币种数据",
            )}
        </Typography.Text>
      )}
      {cache && (
        <Alert
          type={cache.refreshing ? "warning" : "info"}
          title={t(
            cache.refreshing
              ? "正在后台更新，当前显示已有快照"
              : "快照生成时间",
          )}
          description={
            <time dateTime={text(cache.generatedAt)} title="Asia/Shanghai">
              {timestampText(cache.generatedAt, "Asia/Shanghai")}
            </time>
          }
        />
      )}
      {calculation &&
        ["RUNNING", "FAILED", "INTERRUPTED", "PARTIAL"].includes(
          text(calculation.status) || "",
        ) && (
          <Alert
            type={calculation.status === "RUNNING" ? "info" : "warning"}
            title={t(
              calculation.status === "RUNNING"
                ? "结算金额计算中"
                : calculation.status === "PARTIAL"
                  ? "结算金额部分完成"
                  : "历史结算计算暂时中断，已保留数据与进度",
            )}
            description={
              <Space wrap>
                <span>{text(calculation.scope)}</span>
                <span>
                  {t("已处理")}: {text(calculation.processed) ?? "—"} /{" "}
                  {text(calculation.total) ?? "—"}
                </span>
              </Space>
            }
          />
        )}
      {text(source?.basis) && (
        <Typography.Paragraph>{text(source?.basis)}</Typography.Paragraph>
      )}
      {text(source?.reportingMonthNotice) && (
        <Alert type="info" title={text(source?.reportingMonthNotice)} />
      )}{" "}
      {fx && (
        <Space wrap>
          <StatusTag status={fx.status === "PARTIAL" ? "warning" : "default"}>
            {t(fx.status === "PARTIAL" ? "部分历史汇率缺失" : "历史汇率快照")}
          </StatusTag>
          <details>
            <summary>{t("历史汇率依据")}</summary>
            <dl>
              {(
                [
                  ["id", "快照标识"],
                  ["provider", "汇率来源"],
                  ["mode", "换算方式"],
                ] as const
              ).map(([key, label]) =>
                text(fx[key]) ? (
                  <div key={key}>
                    <dt>{t(label)}</dt>
                    <dd>{text(fx[key])}</dd>
                  </div>
                ) : null,
              )}
            </dl>
          </details>
        </Space>
      )}
    </Space>
  );
}
export function useReportReceipt(data: unknown, params: FinanceRequestParams) {
  const { filters, setFilters } = useFinance();
  const expected = selectionKey({ ...filters });
  const id = text(record(record(data)?.fx)?.id);
  useEffect(() => {
    if (!id || params.start !== filters.start || params.end !== filters.end)
      return;
    setFilters((current) =>
      selectionKey({ ...current }) === expected && current.fxSnapshot !== id
        ? { ...current, fxSnapshot: id }
        : current,
    );
  }, [id, expected, params.start, params.end, setFilters]);
}
export function useReport(
  path: string,
  params: FinanceRequestParams,
  enabled = true,
) {
  const { api } = useFinance();
  const query = useFinanceQuery({
    queryKey: [path, selectionKey(params)],
    enabled,
    queryFn: ({ signal }) => api.get<Row>(path, params, signal),
  });
  useReportReceipt(query.data, params);
  return query;
}

export function useOwnerFence() {
  const { session, active } = useFinance();
  const identity = JSON.stringify([session.organizationId, session.userId]);
  const current = useRef({ identity, active }),
    mounted = useRef(true);
  current.current = { identity, active };
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  return {
    identity,
    isCurrent: () =>
      mounted.current &&
      current.current.identity === identity &&
      current.current.active,
  };
}
export function useVisible() {
  const [visible, set] = useState(!document.hidden);
  useEffect(() => {
    const listener = () => set(!document.hidden);
    document.addEventListener("visibilitychange", listener);
    return () => document.removeEventListener("visibilitychange", listener);
  }, []);
  return visible;
}
export function SkuIdentity({ row }: { row: Row }) {
  const { t } = useFinance();
  const details = rows(row.product_sku_details);
  return details.length ? (
    <Space orientation="vertical">
      {details.map((sku, index) => (
        <Space key={index} align="start">
          {text(sku.image) && /^https?:\/\//.test(text(sku.image)!) && (
            <Image
              width={40}
              height={40}
              src={text(sku.image)!}
              alt={text(sku.name) || t("商品图片")}
              preview={false}
            />
          )}
          <div>
            {text(sku.name)} {text(sku.spec)}
            <br />
            {[text(sku.sellerSku), text(sku.skuId), text(sku.productId)]
              .filter(Boolean)
              .join(" · ")}{" "}
            {t("数量")} {numeric(sku.quantity) ?? "—"}
          </div>
        </Space>
      ))}
    </Space>
  ) : (
    <>
      {Array.isArray(row.product_sku_values)
        ? row.product_sku_values.map(valueText).join(" / ")
        : valueText(row.product_skus)}
    </>
  );
}
export function MonetaryFact({
  row,
  field = "amount",
}: {
  row: Row;
  field?: string;
}) {
  const { t } = useFinance();
  const full = numeric(row[field]),
    known = numeric(row["known_" + field]);
  return (
    <>
      {full !== undefined ? (
        amountText(full, text(row.currency))
      ) : known !== undefined && known > 0 ? (
        <>
          {amountText(row["partial_" + field], text(row.currency))}{" "}
          <StatusTag status="warning">{t("已知部分")}</StatusTag>
        </>
      ) : (
        text(row["reason_" + field]) || t("未提供")
      )}
    </>
  );
}

export function AmountWithStatus({
  value,
  currency,
  partial = false,
  estimated = false,
  partialLabel = "已知部分",
}: {
  value: unknown;
  currency?: string | undefined;
  partial?: boolean;
  estimated?: boolean;
  partialLabel?: string;
}) {
  const { t } = useFinance();
  return (
    <Space size="small" wrap>
      <span>{amountText(value, currency)}</span>
      {partial && <StatusTag status="warning">{t(partialLabel)}</StatusTag>}
      {estimated && <StatusTag status="warning">{t("历史价格暂估")}</StatusTag>}
    </Space>
  );
}
