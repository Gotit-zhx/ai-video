import { useState } from "react";
import { Alert, Button, Space } from "antd";
import { Panel, MetricCard } from "../../../shared/components";
import { FinanceModal } from "../../../shared/dialogs";
import type { Row } from "../../../shared/contracts";
import { amountText, record, rows, text } from "../../../shared/rows";
import { useFinance } from "../context";
import { Failure } from "../presentation";
import {
  DataTable,
  Feedback,
  Pages,
  SearchNumbers,
  useCostAction,
  useCostRead,
} from "../costs/common";
import { ConfirmOperation } from "../costs/ConfirmOperation";
import { id, operationReceipt } from "../costs/domain";
const path = "/workspace/charges";
export function BrushPage() {
  const { api, filters, session, t } = useFinance();
  const [page, setPage] = useState(1),
    [numbers, setNumbers] = useState(""),
    [upload, setUpload] = useState(false),
    [removing, setRemoving] = useState<Row>(),
    [source, setSource] = useState<Row>();
  const action = useCostAction();
  const query = useCostRead(
    path,
    {
      groupId: filters.groupId,
      start: filters.start,
      end: filters.end,
      platform: filters.platform,
      shopId: filters.shopId,
      page,
      numbers,
    },
    (data) =>
      !upload &&
      !removing &&
      !source &&
      (record(data?.labelSummary)?.processing === true ||
        Number(record(data?.totals)?.unmatched || 0) > 0)
        ? 15000
        : 0,
    !!filters.groupId && filters.groupId !== "UNGROUPED",
  );
  const data = query.data,
    summary = record(data?.sourceSummary) || {},
    marked = record(data?.marked) || {},
    period = record(data?.periodMarked) || {};
  return (
    <Panel title={t("刷单成本")}>
      <Space orientation="vertical" style={{ width: "100%" }}>
        {(!filters.groupId || filters.groupId === "UNGROUPED") && (
          <Alert type="info" title={t("请选择费用分组后查看刷单来源")} />
        )}
        <Alert
          type="info"
          title={t(
            "来源行合计覆盖全部分页，只受费用分组和精确订单号影响。平台、店铺和结算日期仅限制期间已结算标记。",
          )}
        />
        <Space wrap>
          <MetricCard
            label={t("范围内刷单合计（本金非零笔数）")}
            value={text(summary.principal_rows) ?? "—"}
          />
          <MetricCard
            label={t("范围内刷单费合计")}
            value={amountText(summary.service_fee_cny, "CNY")}
          />
          <MetricCard
            label={t("累计去重刷单费")}
            value={amountText(marked.service_fee_cny, "CNY")}
          />
          <MetricCard
            label={t("期间已结算刷单费")}
            value={amountText(period.service_fee_cny, "CNY")}
          />
          <MetricCard
            label={t("刷单标记订单 / 冲突")}
            value={`${text(marked.orders) ?? "—"} / ${text(marked.conflicts) ?? "—"}`}
          />
        </Space>
        <Failure error={query.error} />
        <Feedback action={action} />
        <Space wrap>
          <SearchNumbers
            onSearch={(v) => {
              setNumbers(v);
              setPage(1);
            }}
          />
          {session.canManage && (
            <>
              <Button
                disabled={!filters.groupId || filters.groupId === "UNGROUPED"}
                onClick={() => setUpload(true)}
              >
                {t("预览并导入刷单来源")}
              </Button>
              <Button
                disabled={
                  !filters.groupId ||
                  filters.groupId === "UNGROUPED" ||
                  action.pending
                }
                onClick={() =>
                  void action.run(
                    () =>
                      api.send(path + "/rematch", "POST", {
                        groupId: filters.groupId,
                      }),
                    () => query.refetch().then(() => {}),
                  )
                }
              >
                {t("重新匹配刷单来源")}
              </Button>
            </>
          )}
          <a
            href="/assets/templates/finance-brush-cost-template.xlsx?v=20261105-original"
            download
          >
            {t("下载刷单模板")}
          </a>
        </Space>
        <DataTable
          data={data?.imports}
          columns={[
            ["source_name", "来源文件"],
            ["row_count", "来源记录"],
            ["created_at", "导入时间"],
          ]}
          actions={(r) =>
            session.canManage ? (
              <Button onClick={() => setRemoving(r)}>
                {t("移除刷单来源")}
              </Button>
            ) : null
          }
        />
        <DataTable
          data={
            Array.isArray(data?.rows)
              ? data.rows.map((r) => {
                  const row = record(r) || {},
                    match = record(row.match) || {},
                    marker = record(row.marker) || {};
                  return {
                    ...row,
                    matchStatus: match.matchStatus,
                    matchNote: match.matchNote,
                    currency: match.currency,
                    source_count: marker.source_count,
                  };
                })
              : []
          }
          columns={[
            ["order_id", "订单编号"],
            ["shop_hint", "来源店铺"],
            ["principal_native", "本金原币"],
            ["currency", "原币"],
            ["service_fee_cny", "代刷费 CNY", "money"],
            ["matchStatus", "匹配状态"],
            ["matchNote", "匹配依据"],
            ["source_count", "同单来源数"],
            ["order_created_time", "下单时间"],
            ["order_timezone", "店铺时区"],
          ]}
          actions={(r) => (
            <Button onClick={() => setSource(r)}>{t("查看刷单来源")}</Button>
          )}
        />
        <Pages data={data} page={page} onChange={setPage} />
      </Space>
      {upload && session.canManage && (
        <BrushImport
          groupId={filters.groupId}
          onClose={() => setUpload(false)}
          onDone={(v) => {
            action.setNotice(operationReceipt(v));
            void query.refetch();
          }}
        />
      )}
      {removing && (
        <ConfirmOperation
          title="移除刷单来源"
          description="只撤销此文件的来源；剩余来源决定刷单标签是否保留。无来源才恢复经营统计，平台原始订单和结算记录保留。"
          onClose={() => setRemoving(undefined)}
          operation={() =>
            api.send(
              path +
                "/imports/" +
                id(removing.id) +
                "?groupId=" +
                id(filters.groupId),
              "DELETE",
            )
          }
          onDone={() => {
            void query.refetch();
          }}
        />
      )}
      {source && (
        <FinanceModal
          open
          title={t("刷单来源证据")}
          onCancel={() => setSource(undefined)}
          footer={null}
        >
          <DataTable
            data={[source]}
            columns={[
              ["import_id", "来源文件标识"],
              ["source_row", "原表行"],
              ["order_id", "订单编号"],
              ["shop_hint", "店铺原名"],
              ["principal_native", "本金原币"],
              ["service_fee_cny", "代刷费 CNY", "money"],
              ["matchNote", "匹配依据"],
            ]}
          />
          <DataTable
            data={[record(source.source) || {}]}
            columns={[
              ["orderId", "原始订单编号"],
              ["shopHint", "原始店铺"],
              ["principalNative", "原始本金"],
              ["serviceFeeCny", "原始代刷费"],
            ]}
          />
          <SourceCells source={record(source.source) || {}} />
        </FinanceModal>
      )}
    </Panel>
  );
}
function BrushImport({
  groupId,
  onClose,
  onDone,
}: {
  groupId: string;
  onClose: () => void;
  onDone: (v: unknown) => void;
}) {
  const { api, t } = useFinance();
  const [file, setFile] = useState<File>(),
    [preview, setPreview] = useState<Row>();
  const action = useCostAction();
  const form = () => {
    if (!file) throw Error("请选择 Excel 或 CSV 来源");
    const f = new FormData();
    f.append("groupId", groupId);
    f.append("file", file);
    return f;
  };
  return (
    <FinanceModal
      open
      title={t("预览并导入刷单来源")}
      onCancel={onClose}
      footer={null}
    >
      <Space orientation="vertical">
        <Alert
          type="info"
          title={t(
            "使用原始模板，1–2000 行；订单编号保留文本。店铺原名、订单号和原币金额须完全匹配。",
          )}
        />
        <label>
          {t("刷单来源文件")}
          <input
            type="file"
            accept=".xlsx,.csv"
            aria-label={t("刷单来源文件")}
            disabled={action.pending}
            onChange={(e) => {
              setFile(e.target.files?.[0]);
              setPreview(undefined);
            }}
          />
        </label>
        <Feedback action={action} />
        <Button
          loading={action.pending}
          onClick={() =>
            void action.run(
              () => api.upload(path + "/preview", form()),
              (v) => setPreview(record(v)),
            )
          }
        >
          {t("预览刷单来源")}
        </Button>
        {preview && (
          <>
            <Alert
              type="info"
              title={`${t("来源记录")} ${text(preview.count) ?? "—"} · ${t("已匹配")} ${text(preview.matched) ?? "—"} · ${amountText(preview.serviceFeeCny, "CNY")}`}
            />
            <DataTable
              data={rows(preview.rows)
                .slice(0, 30)
                .map((r) => ({ ...r, ...record(r.match) }))}
              columns={[
                ["orderId", "订单编号"],
                ["shopHint", "店铺"],
                ["principalNative", "本金原币"],
                ["serviceFeeCny", "代刷费 CNY", "money"],
                ["matchStatus", "匹配状态"],
                ["matchNote", "核对说明"],
              ]}
            />
            <p>{t("预览前 30 条；确认后保存全部有效来源行。")}</p>
            <Button
              loading={action.pending}
              onClick={() =>
                void action.run(
                  () => api.upload(path + "/import", form()),
                  (v) => {
                    onClose();
                    onDone(v);
                  },
                )
              }
            >
              {t("确认导入刷单来源")}
            </Button>
          </>
        )}
      </Space>
    </FinanceModal>
  );
}
function SourceCells({ source }: { source: Row }) {
  const { t } = useFinance();
  const headers = Array.isArray(source.headers) ? source.headers : [],
    cells = Array.isArray(source.cells) ? source.cells : [];
  return (
    <>
      <Alert
        type="info"
        title={t("原表字段按列保留，重复表头和错误单元格也作为原始证据展示。")}
      />
      {source.dateWarning === true && (
        <Alert
          type="warning"
          title={t("来源结算月份包含错误单元格，请核对原表。")}
        />
      )}
      <DataTable
        data={headers.map((header, index) => ({
          id: String(index),
          position: index + 1,
          header: text(header),
          value: text(cells[index]),
        }))}
        columns={[
          ["position", "原表列"],
          ["header", "原始表头"],
          ["value", "原始值"],
        ]}
      />
    </>
  );
}
