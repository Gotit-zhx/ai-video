import { Descriptions, Typography } from "antd";
import type { FinanceRequestParams, Row } from "../../../shared/contracts";
import { amountText, record, rows, text } from "../../../shared/rows";
import { FinanceDrawer } from "../../../shared/dialogs";
import { Panel } from "../../../shared/components";
import { timestampText } from "../domain";
import { useFinance, useFinanceQuery } from "../context";
import {
  Failure,
  FactTable,
  ScopeEvidence,
  SkuIdentity,
  valueText,
} from "../presentation";
export function fieldValue(row: Row, field: Row): unknown {
  const id = text(field.id) || "";
  const fxFields: Record<string, string> = {
    fx_time: "time",
    fx_provider: "provider",
    fx_business_date: "businessDate",
    fx_rate_date: "rateDate",
  };
  if (Object.hasOwn(fxFields, id)) return record(row.fx)?.[fxFields[id]!];
  return id === "order_id" && row.kind === "ORDER"
    ? (row.order_id ?? row.external_id)
    : row[id];
}
export function renderField(row: Row, field: Row) {
  const value = fieldValue(row, field),
    id = text(field.id) || "";
  if (id === "product_skus") return <SkuIdentity row={row} />;
  if (field.type === "money") return amountText(value);
  if (field.type === "datetime" && text(value)) {
    const label = text(field.label) || "";
    const utcIds = [
      "create_time",
      "update_time",
      "paid_time",
      "payout_time",
      "statement_time",
      "settlement_completed_time",
      "actual_release_time",
    ];
    const zone =
      label.includes("北京时间") ||
      id.includes("beijing") ||
      id === "updated_at"
        ? "Asia/Shanghai"
        : label.includes("UTC") || utcIds.includes(id)
          ? "UTC"
          : text(row.order_timezone) || "UTC";
    return (
      <time dateTime={text(value)} title={zone}>
        {timestampText(value, zone)}
      </time>
    );
  }
  return valueText(value);
}
export interface RecordDetailProps {
  recordId: string;
  selection: FinanceRequestParams;
  onClose: () => void;
}
export function RecordDetail({
  recordId,
  selection,
  onClose,
}: RecordDetailProps) {
  const { api, t } = useFinance();
  const query = useFinanceQuery({
    queryKey: ["record-detail", recordId, selection],
    queryFn: ({ signal }) =>
      api.get<Row>(
        "/records/" + encodeURIComponent(recordId),
        selection,
        signal,
      ),
  });
  const row = query.data;
  const kind = text(row?.kind) || "ALL";
  const fields = useFinanceQuery({
    queryKey: ["fields", kind],
    enabled: !!row,
    queryFn: ({ signal }) => api.get<Row>("/fields/" + kind, {}, signal),
  });
  const cost = record(row?.productCost);
  return (
    <FinanceDrawer open onClose={onClose} title={t("记录详情")} size="large">
      <Failure error={query.error || fields.error} />
      {row && (
        <>
          <Descriptions
            column={1}
            bordered
            items={rows(fields.data?.fields)
              .filter(
                (field) =>
                  fieldValue(row, field) != null && (!field.cny || !!row.fx),
              )
              .map((field) => ({
                key: text(field.id) || "",
                label: t(text(field.label) || text(field.id) || ""),
                children: renderField(row, field),
              }))}
          />
          <ScopeEvidence data={row} />
          <Panel title={t("商品与 SKU")}>
            <SkuIdentity row={row} />
          </Panel>
          {rows(row.settlement_fee_breakdown).length > 0 && (
            <FactTable
              title="平台结算费用拆解"
              data={row.settlement_fee_breakdown}
              columns={[
                ["label", "项目"],
                ["group", "分组"],
                ["amount", "金额（正扣负返）", "money"],
                ["field", "来源字段"],
              ]}
            />
          )}{" "}
          {cost && (
            <Panel title={t("商品成本证据")}>
              <Typography.Paragraph>{text(cost.note)}</Typography.Paragraph>
              <Typography.Paragraph>
                {text(cost.warehouse)}
              </Typography.Paragraph>
              <FactTable
                data={cost.lines}
                columns={[
                  ["name", "商品"],
                  ["costCode", "成本 SKU"],
                  ["quantity", "数量"],
                  ["factor", "换算系数"],
                  ["priceMonth", "价格月份"],
                  ["unitPriceCny", "人民币单价", "money"],
                  ["amountCny", "人民币金额", "money"],
                  ["issue", "待补原因"],
                  ["shipmentId", "发货编号"],
                  ["sourceRow", "来源行"],
                  ["excluded", "排除"],
                  ["manualCostId", "人工成本记录"],
                  ["previousPrice", "历史价格暂估"],
                ]}
              />
            </Panel>
          )}
        </>
      )}
    </FinanceDrawer>
  );
}
