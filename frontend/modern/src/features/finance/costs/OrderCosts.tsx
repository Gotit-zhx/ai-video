import { useState } from "react";
import { Alert, Button, Space } from "antd";
import type { FinanceRequestParams, Row } from "../../../shared/contracts";
import { rows, record, text } from "../../../shared/rows";
import { useFinance } from "../context";
import { Failure, ScopeEvidence } from "../presentation";
import { ExportJobs } from "../operations/ExportJobs";
import { RecordDetail } from "../records/RecordDetail";
import {
  Choice,
  CostTotal,
  DataTable,
  Field,
  Pages,
  SearchNumbers,
  useCostRead,
} from "./common";
import { costOrderSelection, costPath } from "./domain";
export function OrderCosts({
  initial = {},
  onMapping,
}: {
  initial?: FinanceRequestParams;
  onMapping?: (shopId: string) => void;
}) {
  const { filters, t } = useFinance();
  const [start, setStart] = useState(text(initial.start) || filters.start),
    [end, setEnd] = useState(text(initial.end) || filters.end),
    [numbers, setNumbers] = useState(text(initial.numbers) || ""),
    [pending, setPending] = useState(initial.costStatus === "PENDING"),
    [kind, setKind] = useState(text(initial.kind) || "SETTLED_ORDER"),
    [page, setPage] = useState(1),
    [jobs, setJobs] = useState(false),
    [detail, setDetail] = useState<Row>();
  const selection = costOrderSelection(
    {
      start,
      end,
      shopId: filters.shopId,
      groupId: filters.groupId,
      platform: filters.platform,
      currency: filters.currency,
      kind,
      recordId: initial.recordId || "",
      numbers,
    },
    pending,
  );
  const query = useCostRead(
    costPath + "/orders",
    { ...selection, page },
    (data) => (record(data?.productCosts)?.processing === true ? 15000 : 0),
  );
  const processing = record(query.data?.productCosts)?.processing === true;
  const product = record(query.data?.productCosts) || {};
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title={t(
          pending
            ? "所有店铺待补齐：当前公司全部店铺、分组、平台及原币，保留日期和订单编号。"
            : "按首次平台结算日期核对；商品成本优先实际出库证据，否则使用标准成本。",
        )}
      />
      <Space wrap>
        <Field
          label="成本开始日期"
          type="date"
          value={start}
          onChange={(v) => {
            setStart(v);
            setPage(1);
          }}
          disabled={!!selection.recordId}
        />
        <Field
          label="成本结束日期"
          type="date"
          value={end}
          onChange={(v) => {
            setEnd(v);
            setPage(1);
          }}
          disabled={!!selection.recordId}
        />
        <Choice
          label="成本订单类型"
          value={pending ? "SETTLED_ORDER" : kind}
          disabled={pending}
          onChange={(v) => {
            setKind(v);
            setPage(1);
          }}
          options={[
            "SETTLED_ORDER",
            "SAMPLE_SETTLED",
            "ORDER",
            "REFUND",
            "REFUND_SETTLED",
            "SETTLEMENT",
            "PENDING",
            "BILL",
            "RECONCILIATION",
          ]}
        />
        <SearchNumbers
          button="查询订单成本"
          initial={numbers}
          onSearch={(v) => {
            setNumbers(v);
            setPage(1);
          }}
        />
        <Button
          onClick={() => {
            setPending(true);
            setPage(1);
          }}
        >
          {t("所有店铺待补齐")}
        </Button>
        <Button
          onClick={() => {
            setPending(false);
            setPage(1);
          }}
        >
          {t("全部订单")}
        </Button>
        <Button onClick={() => setJobs(true)}>{t("订单成本导出")}</Button>
        <Button onClick={() => void query.refetch()}>
          {t("刷新订单成本")}
        </Button>
      </Space>
      <Failure error={query.error} />
      <CostTotal data={query.data?.costAmountSummary} />
      <ScopeEvidence data={query.data} />
      {processing && (
        <Alert type="info" title={t("成本后台重算中，当前金额可能尚未补齐")} />
      )}
      <DataTable
        data={[record(product.total) || {}]}
        columns={[
          ["order_count", "范围订单"],
          ["ready_count", "已完成"],
          ["pending_count", "待补齐"],
          ["estimated_count", "历史价暂估"],
          ["actual_count", "实际成本"],
          ["excluded_count", "不计成本"],
          ["label_cost_cny", "贴单成本", "money"],
          ["charges_pending", "费用待处理"],
        ]}
      />
      <DataTable
        data={product.shops}
        columns={[
          ["shop_name", "店铺"],
          ["order_count", "订单"],
          ["pending_count", "待补齐"],
          ["amount_cny", "完整成本", "money"],
          ["known_amount_cny", "已算部分", "money"],
        ]}
      />
      <Space wrap>
        {Array.isArray(query.data?.reportingMonths) &&
          query.data.reportingMonths.map((m, i) => {
            const r = record(m),
              month = text(r?.month ?? r?.reporting_month ?? m) || "";
            if (!/^\d{4}-\d{2}$/.test(month)) return null;
            return (
              <Button
                key={i}
                onClick={() => {
                  const [y, mo] = month.split("-").map(Number);
                  setStart(month + "-01");
                  setEnd(
                    month + "-" + new Date(Date.UTC(y!, mo!, 0)).getUTCDate(),
                  );
                  setPage(1);
                }}
              >
                {month}
              </Button>
            );
          })}
      </Space>
      <DataTable
        data={rows(query.data?.items).map((r) => ({
          ...r,
          product_spus:
            [
              ...new Set(
                (Array.isArray(r.product_spu_values)
                  ? r.product_spu_values
                  : rows(r.product_sku_details).map((d) => d.productId)
                )
                  .filter(
                    (v): v is string => typeof v === "string" && !!v.trim(),
                  )
                  .map((v) => v.trim()),
              ),
            ].join(" / ") ||
            text(r.product_spus) ||
            "—",
        }))}
        columns={[
          ["order_id", "订单编号"],
          ["shop_id", "店铺"],
          ["product_skus", "商品 SKU"],
          ["product_spus", "平台 SPU"],
          ["first_settlement_date", "首次结算日期"],
          ["reporting_month", "归属月份"],
          ["order_type_label", "订单类型"],
          ["settlement_currency", "原币"],
          ["settlement_net_amount", "结算净额原币"],
          ["product_cost_cny", "商品成本", "money"],
          ["product_cost_state", "成本状态"],
          ["product_cost_basis", "成本依据"],
          ["product_cost_issue", "待补原因"],
          ["label_cost_cny", "贴单成本", "money"],
          ["label_cost_note", "贴单说明"],
        ]}
        actions={(r) => (
          <Space wrap>
            {!!r.id && (
              <Button onClick={() => setDetail(r)}>
                {t("查看订单成本明细")}
              </Button>
            )}
            {onMapping && (
              <Button onClick={() => onMapping(text(r.shop_id) || "")}>
                {t("核对该店 SKU")}
              </Button>
            )}
          </Space>
        )}
      />
      <Pages data={query.data} page={page} onChange={setPage} />
      <ExportJobs
        kind="cost"
        open={jobs}
        selection={selection}
        onClose={() => setJobs(false)}
      />
      {detail && (
        <RecordDetail
          recordId={text(detail.id) || ""}
          selection={selection}
          onClose={() => setDetail(undefined)}
        />
      )}
    </Space>
  );
}
