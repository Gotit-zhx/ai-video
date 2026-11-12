import { useState } from "react";
import { Alert, Button, Space } from "antd";
import type { FinanceRequestParams, Row } from "../../../shared/contracts";
import { Panel, MetricCard } from "../../../shared/components";
import { FinanceModal } from "../../../shared/dialogs";
import { amountText, numeric, record, rows, text } from "../../../shared/rows";
import { useFinance } from "../context";
import { businessToday, selectionKey, timestampText } from "../domain";
import { Failure } from "../presentation";
import { QianyiSettingsModal } from "../shops/QianyiSettingsModal";
import {
  Choice,
  CostTotal,
  DataTable,
  Feedback,
  Field,
  Pages,
  SearchNumbers,
  useCostRead,
  useDownload,
} from "../costs/common";
const path = "/qianyi/returns";
const types = [
  { value: "SALES_RETURN", label: "退货单" },
  { value: "RETURN", label: "退款单" },
  { value: "RESHIP", label: "补发货单" },
];
const returned = [
  "NEW",
  "WAIT_AUDIT",
  "WAIT_BUYER_RETURN",
  "WAIT_STORAGE",
  "RECEIVING",
  "FINISH",
  "CLOSE",
  "NO_NEED_RETURN",
];
const refunded = [
  "WAIT_AUDIT",
  "WAIT_PAYMENT",
  "REFUSE_REFUND",
  "FINISH",
  "PAY_FAILED",
  "CLOSE",
];
const all = [
  { value: "", label: "全部" },
  { value: "NOT_RETURNED", label: "千易未返回" },
];
export function QianyiPage(_props: { route?: string }) {
  const { session, active, filters } = useFinance();
  return active && session.canView ? (
    <Returns
      key={
        session.organizationId +
        ":" +
        session.userId +
        ":" +
        selectionKey({
          shopId: filters.shopId,
          groupId: filters.groupId,
          platform: filters.platform,
        })
      }
    />
  ) : null;
}
function Returns() {
  const { filters, session, t, refresh } = useFinance();
  const now = businessToday(),
    month = now.slice(0, 7),
    [y, m] = month.split("-").map(Number);
  const [draft, setDraft] = useState<FinanceRequestParams>({
    groupId: filters.groupId,
    shopId: filters.shopId,
    platform: filters.platform,
    type: "SALES_RETURN",
    returnStatus: "FINISH",
    refundStatus: "",
    receiptScope: "RECEIVED",
    receiptStatus: "SIGNED",
    dateField: "RECEIPT",
    start: month + "-01",
    end: month + "-" + new Date(Date.UTC(y!, m!, 0)).getUTCDate(),
    numbers: "",
    costStatus: "",
    currency: "",
  });
  const [selection, setSelection] = useState(draft),
    [page, setPage] = useState(1),
    [settings, setSettings] = useState(false),
    [detail, setDetail] = useState<Row>();
  const query = useCostRead(path, { ...selection, page });
  const download = useDownload();
  const summary = record(query.data?.summary) || {};
  const patch = (key: string, v: string) =>
    setDraft((d) => ({ ...d, [key]: v }));
  const search = (numbers?: string) => {
    setSelection({
      ...draft,
      ...(numbers === undefined ? {} : { numbers }),
      dateField: "RECEIPT",
      currency: "",
    });
    setPage(1);
  };
  return (
    <Panel title={t("千易售后与收货核对")}>
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Alert
          type="info"
          title={t(
            "收货统计须同时满足收货时间、退货完成、退货类型、返程已签收且未删除。普通发货签收和返程运输中不计入。",
          )}
          description={t(
            "退货成本是人民币只读参考，不含贴单费，不冲回营业成本，不改变利润。",
          )}
        />
        <Space wrap>
          <Choice
            label="售后查询范围"
            value={text(draft.receiptScope) || ""}
            onChange={(v) =>
              setDraft((d) => ({
                ...d,
                receiptScope: v,
                returnStatus: v === "RECEIVED" ? "FINISH" : "",
                receiptStatus: v === "RECEIVED" ? "SIGNED" : "",
              }))
            }
            options={[
              { value: "RECEIVED", label: "符合收货统计条件" },
              { value: "ALL", label: "全部单据（核对）" },
            ]}
          />
          <Choice
            label="售后类型"
            value={text(draft.type) || ""}
            onChange={(v) => patch("type", v)}
            options={[...all, ...types]}
          />
          <Choice
            label="退货状态"
            value={text(draft.returnStatus) || ""}
            onChange={(v) => patch("returnStatus", v)}
            options={[...all, ...returned]}
          />
          <Choice
            label="退款状态"
            value={text(draft.refundStatus) || ""}
            onChange={(v) => patch("refundStatus", v)}
            options={[...all, ...refunded]}
          />
          <Choice
            label="退回物流状态"
            value={text(draft.receiptStatus) || ""}
            onChange={(v) => patch("receiptStatus", v)}
            options={[
              { value: "", label: "全部" },
              "SIGNED",
              "IN_TRANSIT",
              "UNKNOWN",
            ]}
          />
          <Choice
            label="退货成本状态"
            value={text(draft.costStatus) || ""}
            onChange={(v) => patch("costStatus", v)}
            options={[{ value: "", label: "全部" }, "PENDING", "READY"]}
          />
          <Field
            label="收货开始日期（北京时间）"
            type="date"
            value={text(draft.start) || ""}
            onChange={(v) => patch("start", v)}
          />
          <Field
            label="收货结束日期（北京时间）"
            type="date"
            value={text(draft.end) || ""}
            onChange={(v) => patch("end", v)}
          />
          <SearchNumbers
            label="平台订单号 / 千易售后单号"
            button="查询售后"
            onSearch={(v) => {
              patch("numbers", v);
              search(v);
            }}
          />
          <Button
            onClick={() => {
              setDraft((d) => ({ ...d, start: "", end: "" }));
              setSelection({
                ...draft,
                start: "",
                end: "",
                dateField: "RECEIPT",
                currency: "",
              });
              setPage(1);
            }}
          >
            {t("查询全部收货期间")}
          </Button>
          <Button
            disabled={query.isFetching || !query.data}
            loading={download.pending}
            onClick={() =>
              void download.download(
                path + "/export",
                selection,
                "qianyi-returns.xlsx",
              )
            }
          >
            {t("导出千易售后")}
          </Button>
          {session.canManage && (
            <Button onClick={() => setSettings(true)}>
              {t("千易全局设置")}
            </Button>
          )}
        </Space>
        <Failure error={query.error} />
        <Feedback action={download} />
        <Space wrap>
          <MetricCard
            label={t("当前筛选单据")}
            value={text(summary.matchedRecords ?? query.data?.total) ?? "—"}
          />
          <MetricCard
            label={t("符合收货统计条件")}
            value={text(summary.records) ?? "—"}
          />
          <MetricCard
            label={t("缺少返程物流依据（未计入）")}
            value={text(summary.awaitingLogistics) ?? "—"}
          />
          {rows(summary.refundTotals).map((r) => (
            <MetricCard
              key={text(r.currency)}
              label={`${t("退款原币")} ${text(r.currency)}`}
              value={
                r.currency === "UNKNOWN"
                  ? t("币种待确认，暂不合计")
                  : amountText(r.amount, text(r.currency))
              }
              detail={
                (numeric(r.known_count) || 0) < (numeric(r.records) || 0)
                  ? t("部分金额未返回")
                  : undefined
              }
            />
          ))}
          <CostTotal data={summary.returnCost} camel label="退回商品参考成本" />
        </Space>
        <DataTable
          data={rows(query.data?.items).map((r) => ({
            ...r,
            receivedAt: timestampText(r.source_received_at, "Asia/Shanghai"),
            refund:
              text(r.refund_currency) === "UNKNOWN"
                ? t("币种待确认")
                : amountText(r.refund_amount, text(r.refund_currency)),
            returnAmount: amountText(record(r.returnCost)?.amountCny, "CNY"),
          }))}
          columns={[
            ["return_number", "千易售后单"],
            ["order_id", "平台订单编号"],
            ["shop_name", "店铺"],
            ["aftersale_type", "售后类型"],
            ["return_state", "退货状态"],
            ["refund_state", "退款状态"],
            ["return_receipt_label", "返程收货状态"],
            ["receipt_event", "返程物流依据"],
            ["receivedAt", "收货时间（北京时间）"],
            ["refund", "退款原币"],
            ["cost_status", "退货成本状态"],
            ["returnAmount", "参考成本 CNY"],
          ]}
          actions={(r) => (
            <Button onClick={() => setDetail(r)}>{t("查看售后证据")}</Button>
          )}
        />
        <Pages data={query.data} page={page} onChange={setPage} />
        {text(query.data?.basis) && <p>{text(query.data?.basis)}</p>}
        <Alert
          type="info"
          title={t(
            "导出最多 50,000 条，单并发同步生成；失败后请根据错误调整范围。",
          )}
        />
      </Space>
      {detail && (
        <ReturnDetail row={detail} onClose={() => setDetail(undefined)} />
      )}
      <QianyiSettingsModal
        open={settings}
        onClose={() => setSettings(false)}
        onSaved={() => void refresh()}
      />
    </Panel>
  );
}
function ReturnDetail({ row, onClose }: { row: Row; onClose: () => void }) {
  const { t } = useFinance();
  const query = useCostRead(path + "/detail", {
    shopId: text(row.shop_id) || "",
    returnNumber: text(row.return_number) || "",
  });
  const value = query.data || {},
    source = record(value.sourceFields) || {},
    proof = record(source._returnReceipt) || {},
    cost = record(value.returnCost) || {};
  return (
    <FinanceModal
      open
      title={t("千易售后单详情")}
      width={1000}
      onCancel={onClose}
      footer={null}
    >
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Failure error={query.error} />
        <DataTable
          data={[
            {
              ...source,
              return_number: value.return_number,
              order_id: value.order_id,
              shop_name: value.shop_name,
              receipt_event: proof.event,
              receipt_event_at: timestampText(proof.eventDate, "Asia/Shanghai"),
              receivedAt: timestampText(
                value.source_received_at,
                "Asia/Shanghai",
              ),
              receiveTime: timestampText(source.receiveTime, "Asia/Shanghai"),
              createdAt: timestampText(source.createTime, "Asia/Shanghai"),
              updatedAt: timestampText(source.updateTime, "Asia/Shanghai"),
              finishedAt: timestampText(source.finishTime, "Asia/Shanghai"),
            },
          ]}
          columns={[
            ["return_number", "千易售后单"],
            ["order_id", "平台订单号"],
            ["orderNumber", "千易销售单"],
            ["shop_name", "财务店铺"],
            ["shop", "千易店铺"],
            ["type", "类型"],
            ["returnStatus", "退货状态"],
            ["refundStatus", "退款状态"],
            ["customNumber", "退货运单"],
            ["receipt_event", "返程物流依据"],
            ["receipt_event_at", "物流事件时间"],
            ["receivedAt", "有效收货时间"],
            ["receiveTime", "千易 receiveTime"],
            ["warehouse", "千易仓库"],
            ["asnNumber", "入库单"],
            ["warehouseEntryNumber", "仓库入库单"],
            ["createdAt", "创建时间"],
            ["updatedAt", "更新时间"],
            ["finishedAt", "完成时间"],
            ["orderRefundAmount", "千易退款金额"],
            ["currency", "退款币种"],
          ]}
        />
        <CostTotal data={cost} camel label="本单退回参考成本" />
        <p>{text(cost.note)}</p>
        <DataTable
          data={cost.lines}
          columns={[
            ["costCode", "成本型号"],
            ["sellerSku", "商家 SKU"],
            ["quantity", "退货数量"],
            ["factor", "换算数量"],
            ["priceMonth", "价格月份"],
            ["unitPriceCny", "人民币单价"],
            ["amountCny", "人民币成本", "money"],
            ["issue", "核对说明"],
            ["previousPrice", "历史价格暂估"],
          ]}
        />
        <Alert
          type="info"
          title={t(
            "千易订单商品 ID 不是平台 SKU ID，不用于推测平台关联；删除或未选中行不计入。",
          )}
        />
        <DataTable
          data={source.returnSkuList}
          columns={[
            ["sku", "千易商品 SKU"],
            ["orderSkuId", "千易订单商品 ID"],
            ["quantity", "退货数量"],
            ["goodQuantity", "良品数量"],
            ["badQuantity", "不良品数量"],
            ["isDeleted", "已删除"],
            ["selected", "已选中"],
          ]}
        />
      </Space>
    </FinanceModal>
  );
}
