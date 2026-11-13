import { useState, type ReactNode } from "react";
import { Alert, Button, Input, Segmented, Space, Tabs } from "antd";
import type { FinanceRequestParams, Row } from "../../../shared/contracts";
import { numeric, record, rows, text } from "../../../shared/rows";
import { BusinessTable } from "../../../shared/components";
import { useFinance, useFinanceQuery } from "../context";
import { exactNumbers, responseScope, selectionKey } from "../domain";
import {
  Failure,
  FactTable,
  ScopeEvidence,
  useReport,
  useReportReceipt,
} from "../presentation";
import {
  ExportDialog,
  ExportJobs,
  FieldSettings,
  ImportDialog,
  fieldIds,
} from "../operations";
import { useWorkspaceStatus, OperationalEvidence } from "../operational";
import { RecordDetail, renderField } from "./RecordDetail";
export interface RecordsPageProps {
  view: string;
  orderScope?: "ALL" | "SETTLED";
  advertisingOnly?: boolean;
  refundContent?: ReactNode;
}
export function RecordsPage(props: RecordsPageProps) {
  const { filters, session } = useFinance();
  return (
    <RecordSession
      key={[
        session.organizationId,
        session.userId,
        selectionKey({ ...filters }),
        props.view,
        props.orderScope,
      ].join(":")}
      {...props}
    />
  );
}
function RecordSession({
  view,
  orderScope = "ALL",
  advertisingOnly = false,
  refundContent,
}: RecordsPageProps) {
  const { filters, t, session, refresh, reads } = useFinance();
  const [kind, setKind] = useState(view),
    [scope, setScope] = useState(orderScope),
    [tab, setTab] = useState("records"),
    [page, setPage] = useState(1),
    [input, setInput] = useState(""),
    [numbers, setNumbers] = useState(""),
    [error, setError] = useState<unknown>(),
    [detail, setDetail] = useState(""),
    [operation, setOperation] = useState("");
  const reconciliation = view === "RECONCILIATION";
  const workspaceView =
    advertisingOnly || ["AD", "AD_GMV"].includes(kind)
      ? "ads-support"
      : reconciliation
        ? "reconciliation"
        : ["ORDER", "REFUND"].includes(view)
          ? "orders"
          : ["FEE", "COST"].includes(view)
            ? "fees"
            : "settlements";
  const operational = useWorkspaceStatus(workspaceView, tab === "records");
  const params = {
    ...filters,
    view: workspaceView,
    kind: reconciliation ? "BILL" : kind,
    page,
    numbers,
    ...(kind === "ORDER" ? { orderScope: scope } : {}),
  };
  const result = useFinanceQuery({
    queryKey: ["/workspace/query", selectionKey(params)],
    enabled: tab === "records",
    queryFn: ({ signal }) => reads.records(params, signal),
  });
  const query = { ...result, data: result.data?.data };
  const data = query.data;
  useReportReceipt(data, params);
  const selection = responseScope(filters, data);
  const frozen: FinanceRequestParams = {
    ...selection,
    view: reconciliation ? "RECONCILIATION" : kind,
    numbers,
    ...(kind === "ORDER" ? { orderScope: scope } : {}),
    ...(advertisingOnly ? { advertisingOnly: true } : {}),
  };
  const fieldQuery = useReport(
    "/fields/" + (reconciliation ? "RECONCILIATION" : kind),
    {},
    tab === "records" && !!data && !record(data.fieldSettings),
  );
  const settings = record(data?.fieldSettings) || record(fieldQuery.data),
    visible = fieldIds(settings?.visible);
  const catalog = rows(settings?.fields);
  const fields = catalog.filter(
    (field) =>
      visible.includes(text(field.id) || "") &&
      !(kind === "ORDER" && field.id === "external_id") &&
      (!field.cny || !!data?.fx || rows(data?.items).some((row) => !!row.fx)),
  );
  if (
    kind === "ORDER" &&
    visible.includes("external_id") &&
    !fields.some((field) => field.id === "order_id")
  )
    fields.unshift({ id: "order_id", label: "订单编号", type: "text" });
  const metadata = useReport("/shops", {}, operation === "import");
  const search = () => {
    try {
      setNumbers(exactNumbers(input));
      setPage(1);
      setError(undefined);
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Space orientation="vertical" className="finance-page-stack" size="medium">
      {view === "ORDER" && (
        <Tabs
          activeKey={tab}
          onChange={setTab}
          items={[
            { key: "records", label: t("订单") },
            { key: "refunds", label: t("退款与售后") },
          ]}
        />
      )}{" "}
      {tab === "refunds" ? (
        refundContent || (
          <Alert
            type="info"
            title={t("退款与售后工作区待接入，请使用旧版完整操作。")}
          />
        )
      ) : (
        <>
          {view === "SETTLEMENT" && (
            <Segmented
              value={kind}
              onChange={(value) => {
                setKind(value);
                setPage(1);
              }}
              options={[
                { value: "SETTLEMENT", label: t("结算明细") },
                { value: "BILL", label: t("平台账单") },
                { value: "PENDING", label: t("预计待结算") },
              ]}
            />
          )}{" "}
          {view === "ORDER" && (
            <Segmented
              value={scope}
              onChange={(value) => {
                setScope(value === "SETTLED" ? "SETTLED" : "ALL");
                setPage(1);
              }}
              options={[
                { value: "ALL", label: t("全部订单") },
                { value: "SETTLED", label: t("已结算订单") },
              ]}
            />
          )}
          <Alert
            type="info"
            title={t(
              kind === "ORDER" && scope === "ALL"
                ? "全部订单按店铺下单日期；精确编号可跨日期查询。"
                : kind === "PENDING"
                  ? "预计待结算按下单日期，金额为动态估计。"
                  : kind === "ORDER"
                    ? "订单按首次平台结算日期归属，跨月订单保留。"
                    : reconciliation
                      ? "平台账单与 API 交易核对；已结算不代表银行到账。"
                      : "销售收入与平台净结算分开，正费用为扣减，负费用为返还。",
            )}
          />
          <Space wrap>
            <Input
              aria-label={t("精确编号查询")}
              placeholder={t("最多 100 个订单或账单编号")}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onPressEnter={search}
            />
            <Button onClick={search}>{t("查询")}</Button>
            <Button onClick={() => setOperation("fields")}>
              {t("字段设置")}
            </Button>
            <Button
              disabled={!data || query.isPending}
              onClick={() => setOperation("export")}
            >
              {t("导出")}
            </Button>
            {view === "FEE" && (
              <Button onClick={() => setOperation("jobs")}>
                {t("费用矩阵导出")}
              </Button>
            )}
            {session.canManage &&
              ["SETTLEMENT", "BILL", "COST", "AD"].includes(view) && (
                <Button onClick={() => setOperation("import")}>
                  {t("导入")}
                </Button>
              )}
          </Space>
          <OperationalEvidence
            operations={operational.operations}
            error={operational.error}
          />
          <Failure error={error || query.error || fieldQuery.error} />
          {data && (
            <>
              <ScopeEvidence
                data={
                  operational.operations &&
                  Object.hasOwn(operational.operations, "settlementCalculation")
                    ? { ...data, settlementCalculation: undefined }
                    : data
                }
              />
              {record(data.productCosts) && (
                <FactTable
                  title="当前记录成本完整性"
                  data={[record(record(data.productCosts)?.total) || {}]}
                  columns={[
                    ["order_count", "订单数"],
                    ["pending_count", "待补齐订单"],
                    ["known_amount_cny", "已算成本（人民币）", "money"],
                    ["amount_cny", "完整成本（人民币）", "money"],
                  ]}
                />
              )}
              {view === "FEE" && (
                <FactTable
                  title="费用构成"
                  data={data.fees}
                  columns={[
                    ["category", "费用项目"],
                    ["amount", "金额", "money"],
                    ["records", "笔数"],
                  ]}
                />
              )}
              <BusinessTable<Row>
                rowKey={(row) => text(row.id) || JSON.stringify(row)}
                loading={query.isFetching}
                dataSource={rows(data.items)}
                pagination={{
                  current: page,
                  pageSize: 50,
                  total: numeric(data.total) || 0,
                  showSizeChanger: false,
                  onChange: setPage,
                  showTotal: (total) => t("全部查询记录") + ": " + total,
                }}
                columns={[
                  ...fields.map((field) => ({
                    key: text(field.id) || "",
                    title: t(
                      field.id === "record_date" && kind === "ORDER"
                        ? scope === "ALL"
                          ? "下单日期（店铺时区）"
                          : "首次平台结算日期"
                        : text(field.label) || text(field.id) || "",
                    ),
                    render: (_: unknown, row: Row) => renderField(row, field),
                  })),
                  {
                    key: "action",
                    title: t("操作"),
                    render: (_, row) => (
                      <Button
                        type="link"
                        onClick={() => setDetail(text(row.id) || "")}
                      >
                        {t("详情")}
                      </Button>
                    ),
                  },
                ]}
              />
            </>
          )}
        </>
      )}
      {detail && (
        <RecordDetail
          recordId={detail}
          selection={{
            ...selection,
            ...(kind === "ORDER" ? { orderScope: scope } : {}),
          }}
          onClose={() => setDetail("")}
        />
      )}
      <FieldSettings
        view={reconciliation ? "RECONCILIATION" : kind}
        open={operation === "fields"}
        onClose={() => setOperation("")}
      />
      <ExportDialog
        open={operation === "export"}
        selection={frozen}
        onClose={() => setOperation("")}
      />
      <ExportJobs
        kind="fee"
        open={operation === "jobs"}
        selection={frozen}
        onClose={() => setOperation("")}
      />
      <ImportDialog
        open={operation === "import"}
        shops={rows(metadata.data?.items ?? data?.shops)}
        kind={view === "AD" ? "AD" : view === "COST" ? "COST" : "BILL"}
        onClose={() => setOperation("")}
        onImported={() => void refresh()}
      />
    </Space>
  );
}
