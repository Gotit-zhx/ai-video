import { useEffect, useRef, useState } from "react";
import {
  Alert,
  Button,
  Form,
  Input,
  Select,
  Space,
  Tabs,
  Typography,
} from "antd";
import { FinanceApiError } from "../../../shared/api";
import type { Row } from "../../../shared/contracts";
import { numeric, record, rows, text } from "../../../shared/rows";
import {
  BusinessTable,
  MetricCard,
  Panel,
  StatusTag,
} from "../../../shared/components";
import { FinanceModal } from "../../../shared/dialogs";
import { useFinance, useFinanceQuery } from "../context";
import {
  allocationPayload,
  bookedAmount,
  changeFinanceFilters,
  fundingFields,
  responseScope,
  selectionKey,
} from "../domain";
import {
  Failure,
  FactTable,
  MonetaryFact,
  ScopeEvidence,
  useOwnerFence,
  useReport,
  useReportReceipt,
} from "../presentation";
import { useWorkspaceStatus, OperationalEvidence } from "../operational";
import { ExportDialog, ImportDialog } from "../operations";
import { RecordsPage } from "../records/RecordsPage";
import {
  AccountCoverage,
  BookedBreakdowns,
  GmvSummary,
} from "./AdvertisingSupport";
import { amountText } from "../../../shared/rows";
import { ShopeeAdsPanel } from "../shops/ShopeeAdsPanel";
const moneyFields = [...fundingFields, "net_amount"] as const;
const labels: Record<string, string> = {
  amount: "广告消耗（投放报表）",
  cash_amount: "现金消耗",
  credit_amount: "信用额度消耗",
  grant_amount: "广告费用赠款消耗",
  net_amount: "净广告消耗",
};
const sources: Record<string, string> = {
  IMPORT_FUNDING: "资金消耗报表",
  API_GMV: "GMV Max 报表",
  API_BASIC: "普通账户投放",
  API_BC_COST: "BC 资金费用",
  API_CAMPAIGN: "系列投放报表",
  API_SHOPEE_CPC: "Shopee 店铺 CPC 日报",
  LEGACY_IMPORT: "历史导入",
};
function allocationAllowed(row: Row) {
  return (
    !["OFFICIAL", "SOURCE_REVIEW"].includes(String(row.assignment_status)) &&
    (!row.shop_id ||
      row.shop_id === "UNASSIGNED" ||
      String(row.source).includes("IMPORT"))
  );
}
export function AdvertisingPage({
  initialTab = "spend",
}: {
  initialTab?: string;
}) {
  const { filters, t, session, setFilters, refresh, reads } = useFinance();
  const [page, setPage] = useState(1),
    [input, setInput] = useState(""),
    [search, setSearch] = useState(""),
    [assignment, setAssignment] = useState(""),
    [tab, setTab] = useState(
      ["spend", "booked", "AD", "ledger", "AD_GMV", "accounts"].includes(
        initialTab,
      )
        ? initialTab
        : "spend",
    ),
    [operation, setOperation] = useState(""),
    [sourceSelection, setSourceSelection] = useState<{
      key: string;
      allocate: boolean;
    } | null>(null);
  const ownsStatus = !["AD", "ledger", "AD_GMV"].includes(tab);
  const operational = useWorkspaceStatus("ads", ownsStatus);
  const params = { ...filters, spendPage: page, search, assignment };
  const result = useFinanceQuery({
    queryKey: ["/workspace/query", selectionKey({ ...params, view: "ads" })],
    queryFn: ({ signal }) => reads.advertising(params, signal),
  });
  const query = { ...result, data: record(result.data?.data.adSpend) };
  const data = query.data;
  useReportReceipt(data, params);
  const selection = responseScope(filters, data);
  const overview = useReport(
    "/overview",
    selection,
    !!data && ["booked", "AD_GMV"].includes(tab),
  );
  const legacyAccounts = useReport(
    "/ads",
    { ...selection, start: filters.start, end: filters.end },
    !!data && result.data?.legacy === true,
  );
  const accounts = {
    ...legacyAccounts,
    data:
      operational.operations?.adAccounts ??
      (result.data?.legacy
        ? legacyAccounts.data
        : result.data?.data.adAccounts),
  };
  const legacyMetadata = useReport("/shops", {}, result.data?.legacy === true);
  const metadata = {
    ...legacyMetadata,
    data: result.data?.legacy
      ? legacyMetadata.data
      : { items: result.data?.data.shops, groups: result.data?.data.groups },
  };
  const ad = record(overview.data?.advertising) || {};
  const moneyColumns = moneyFields.map((field) => ({
    title: t(labels[field]!),
    key: field,
    render: (_: unknown, row: Row) => <MonetaryFact row={row} field={field} />,
  }));
  const summary = (group: boolean) => (
    <Panel title={t(group ? "分组广告消耗" : "店铺广告消耗")}>
      <BusinessTable<Row>
        rowKey={(row) => JSON.stringify(row)}
        dataSource={rows(group ? data?.groups : data?.shops)}
        pagination={false}
        columns={[
          { title: t("平台"), dataIndex: "platform" },
          {
            title: t(group ? "分组" : "店铺"),
            render: (_, row) => (
              <Button
                type="link"
                onClick={() =>
                  setFilters((current) =>
                    changeFinanceFilters(
                      current,
                      group
                        ? {
                            groupId: text(row.group_id) || "UNGROUPED",
                            shopId: "",
                          }
                        : { shopId: text(row.shop_id) || "" },
                    ),
                  )
                }
              >
                {text(group ? row.group_name : row.shop_name) ||
                  t(group ? "未分组" : "未分配")}
              </Button>
            ),
          },
          { title: t("币种"), dataIndex: "currency" },
          ...moneyColumns,
          { title: t("待分配记录"), dataIndex: "pending_records" },
          { title: t("记录数"), dataIndex: "records" },
        ]}
      />
    </Panel>
  );
  return (
    <Space orientation="vertical" size="medium" className="finance-page-stack">
      <Alert
        type="info"
        title={t("投放报表与已入账广告范围不同，不可直接相加。")}
        description={t(
          "现金、信用额度、赠款不互相倒算；净消耗不等于现金付款。缺少记录不代表实际零消耗；来源待核实部分保留未知。",
        )}
      />
      <Failure error={query.error} />
      {ownsStatus && (
        <OperationalEvidence
          operations={operational.operations}
          error={operational.error}
        />
      )}
      {data && (
        <>
          <ScopeEvidence
            data={
              !ownsStatus ||
              (operational.operations &&
                Object.hasOwn(operational.operations, "settlementCalculation"))
                ? { ...data, settlementCalculation: undefined }
                : data
            }
          />
          <Tabs
            activeKey={tab}
            onChange={setTab}
            items={[
              { key: "spend", label: t("投放消耗") },
              { key: "booked", label: t("已入账广告") },
              { key: "AD", label: t("普通独立广告") },
              { key: "ledger", label: t("平台账单广告") },
              { key: "AD_GMV", label: t("GMV Max 分析") },
              { key: "accounts", label: t("账户与覆盖") },
            ]}
          />
          {tab === "spend" && (
            <>
              <Space wrap>
                <Button onClick={() => setOperation("export")}>
                  {t("导出全部投放消耗")}
                </Button>
                {session.canManage && (
                  <Button onClick={() => setOperation("import")}>
                    {t("导入资金消耗")}
                  </Button>
                )}
              </Space>
              <ShopeeAdsPanel
                data={record(
                  operational.operations?.shopeeAds ?? data.shopeeAds,
                )}
                compact
              />
              {rows(data.totals).length ? (
                rows(data.totals).map((total, index) => (
                  <div className="finance-metric-grid" key={index}>
                    {moneyFields.map((field) => (
                      <MetricCard
                        key={field}
                        label={t(labels[field]!)}
                        value={<MonetaryFact row={total} field={field} />}
                        detail={text(total.currency)}
                      />
                    ))}
                  </div>
                ))
              ) : (
                <Alert
                  type="warning"
                  title={t("当前范围尚未取得投放消耗，不能解释为零消耗。")}
                />
              )}
              <Space wrap>
                <StatusTag>
                  {t("已排除重叠来源")}: {text(data.overlapExcluded) || "—"}
                </StatusTag>
                <StatusTag status="warning">
                  {t("来源重叠待核实")}: {text(data.overlapUnresolved) || "—"}
                </StatusTag>
                <StatusTag>
                  {t("全范围来源记录")}: {text(data.scopeRecords) || "—"}
                </StatusTag>
              </Space>
              <Typography.Paragraph>
                {t(
                  "同账户、日期及币种的 Shopee 官方店铺日报优先，其他来源按系列和账户核对重叠；排除汇总的来源记录仍保留。",
                )}
              </Typography.Paragraph>
              {summary(true)}
              {summary(false)}
              <Panel title={t("店铺消耗明细")}>
                <Space wrap>
                  <Input
                    aria-label={t("系列或账户查询")}
                    value={input}
                    onChange={(event) => setInput(event.target.value)}
                  />
                  <Button
                    onClick={() => {
                      setSearch(input);
                      setPage(1);
                    }}
                  >
                    {t("查询")}
                  </Button>
                  <Select
                    aria-label={t("归属状态")}
                    style={{ minWidth: 160 }}
                    value={assignment}
                    onChange={(value) => {
                      setAssignment(value);
                      setPage(1);
                    }}
                    options={[
                      { value: "", label: t("全部归属状态") },
                      ...[
                        "PENDING",
                        "PARTIAL",
                        "CONFIRMED",
                        "OFFICIAL",
                        "REVIEW",
                        "SOURCE_REVIEW",
                      ].map((value) => ({
                        value,
                        label: t(
                          (
                            {
                              PENDING: "待分配",
                              PARTIAL: "余款待分配",
                              CONFIRMED: "已核实分配",
                              OFFICIAL: "来源已归店",
                              REVIEW: "来源变化待复核",
                              SOURCE_REVIEW: "来源待核实",
                            } as Record<string, string>
                          )[value]!,
                        ),
                      })),
                    ]}
                  />
                </Space>
                <Typography.Paragraph>
                  {t("此查询及归属筛选仅影响明细，汇总覆盖全部范围。")}
                </Typography.Paragraph>
                <BusinessTable<Row>
                  rowKey={(row) => JSON.stringify(row)}
                  dataSource={rows(data.items)}
                  pagination={{
                    current: page,
                    pageSize: 50,
                    total: numeric(data.total) || 0,
                    showSizeChanger: false,
                    onChange: setPage,
                  }}
                  columns={[
                    { title: t("业务日期"), dataIndex: "record_date" },
                    {
                      title: t("来源"),
                      dataIndex: "source",
                      render: (v: unknown) =>
                        t(sources[text(v) || ""] || text(v) || "未知"),
                    },
                    { title: t("店铺"), dataIndex: "shop_name" },
                    { title: t("账户"), dataIndex: "account_id" },
                    { title: t("系列"), dataIndex: "campaign_name" },
                    { title: t("币种"), dataIndex: "currency" },
                    ...moneyColumns,
                    { title: t("归属状态"), dataIndex: "assignment_status" },
                    {
                      title: t("操作"),
                      render: (_, row) =>
                        row.source === "API_SHOPEE_CPC" ? (
                          <Typography.Text>{t("官方店铺日报")}</Typography.Text>
                        ) : (
                          <Button
                            onClick={() =>
                              setSourceSelection({
                                key: text(row.source_key) || "",
                                allocate: allocationAllowed(row),
                              })
                            }
                            disabled={!text(row.source_key)}
                          >
                            {t(
                              session.canManage && allocationAllowed(row)
                                ? "查看 / 核实分配"
                                : "来源详情",
                            )}
                          </Button>
                        ),
                    },
                  ]}
                />
              </Panel>
              <Panel title={t("账户资金诊断")}>
                <Typography.Paragraph>
                  {t(
                    "BC 资金、税额、充值、余额和授信为独立范围，不计入上述店铺消耗合计。",
                  )}
                </Typography.Paragraph>
                <BusinessTable<Row>
                  rowKey={(row) => JSON.stringify(row)}
                  dataSource={rows(data.accountFunding)}
                  pagination={false}
                  columns={[
                    { title: t("账户"), dataIndex: "account_name" },
                    { title: t("账户 ID"), dataIndex: "account_id" },
                    { title: t("来源"), dataIndex: "source" },
                    ...moneyColumns,
                    {
                      title: t("税额（单列）"),
                      render: (_, row) => (
                        <MonetaryFact row={row} field="tax_amount" />
                      ),
                    },
                  ]}
                />
              </Panel>
              <FactTable
                title="历史汇率证据"
                data={data.fxEvidence}
                columns={[
                  ["currency", "原币"],
                  ["businessDate", "业务日期"],
                  ["rateDate", "汇率日期"],
                  ["rate", "历史汇率"],
                  ["provider", "来源"],
                  ["status", "状态", "status"],
                ]}
              />
            </>
          )}
          {tab === "booked" && (
            <>
              <Failure error={overview.error} />
              <div className="finance-metric-grid">
                {(
                  [
                    "total",
                    "independent",
                    "settlement",
                    "commission",
                    "unassigned",
                    "gmv",
                  ] as const
                ).map((key) => (
                  <MetricCard
                    key={key}
                    label={t(
                      {
                        total: "已入账广告合计",
                        independent: "普通独立广告",
                        settlement: "平台账单广告",
                        commission: "其中广告佣金",
                        unassigned: "未分配广告",
                        gmv: "GMV Max（不叠加）",
                      }[key],
                    )}
                    value={amountText(
                      bookedAmount(ad[key]),
                      text(record(overview.data?.filters)?.displayCurrency),
                    )}
                    detail={
                      record(ad[key])?.hasRecords === false
                        ? t("没有已入库记录")
                        : record(ad[key])?.complete === false
                          ? t("已知部分")
                          : undefined
                    }
                  />
                ))}
              </div>
              <Typography.Paragraph>
                {text(ad.coverageNote)}
              </Typography.Paragraph>
              <Typography.Paragraph>
                {text(ad.profitBasis)}
              </Typography.Paragraph>
              <FactTable
                title="投放效果（完整输入才显示比率）"
                data={record(ad.performance) ? [record(ad.performance)!] : []}
                columns={[
                  ["impressions", "曝光"],
                  ["clicks", "点击"],
                  ["conversions", "转化"],
                  ["clickThroughRate", "点击率"],
                  ["costPerClick", "单次点击成本", "money"],
                  ["costPerThousand", "千次曝光成本", "money"],
                  ["costPerConversion", "单次转化成本", "money"],
                  ["conversionRate", "转化率"],
                ]}
              />
              <FactTable
                title="已入账广告趋势"
                data={ad.trend}
                columns={[
                  ["period", "日期"],
                  ["AD_TOTAL", "已入账广告", "money"],
                  ["AD_SETTLEMENT", "账单广告", "money"],
                  ["AD_INDEPENDENT", "独立广告", "money"],
                ]}
              />
              <FactTable
                title="已入账广告来源"
                data={ad.categories}
                columns={[
                  ["category", "来源"],
                  ["amount", "金额", "money"],
                  ["records", "记录数"],
                ]}
              />
            </>
          )}
          {tab === "booked" && (
            <BookedBreakdowns
              report={ad}
              shops={rows(metadata.data?.items)}
              groups={rows(metadata.data?.groups)}
              accounts={accounts.data}
              accountFailed={!!accounts.error}
            />
          )}
          {["booked", "AD_GMV"].includes(tab) && (
            <GmvSummary summary={ad.gmv} />
          )}
          {["AD", "ledger", "AD_GMV"].includes(tab) && (
            <RecordsPage
              view={tab === "ledger" ? "FEE" : tab}
              advertisingOnly={tab === "ledger"}
            />
          )}
          <section hidden={tab !== "accounts" && tab !== "spend"}>
            <Failure error={accounts.error} />
            {!!accounts.data && (
              <AccountCoverage
                accounts={accounts.data}
                shops={rows(metadata.data?.items)}
              />
            )}
          </section>
        </>
      )}
      <ExportDialog
        open={operation === "export"}
        selection={{ ...selection, search, assignment }}
        path="/advertising/export"
        onClose={() => setOperation("")}
      />
      <ImportDialog
        open={operation === "import"}
        funding
        shops={rows(metadata.data?.items)}
        onImported={() => void refresh()}
        onClose={() => setOperation("")}
      />
      {sourceSelection?.key && (
        <AllocationDialog
          key={sourceSelection.key}
          sourceKey={sourceSelection.key}
          allowAllocation={sourceSelection.allocate}
          shops={rows(metadata.data?.items)}
          onClose={() => setSourceSelection(null)}
        />
      )}
    </Space>
  );
}
function AllocationDialog({
  sourceKey,
  allowAllocation,
  shops,
  onClose,
}: {
  sourceKey: string;
  allowAllocation: boolean;
  shops: Row[];
  onClose: () => void;
}) {
  const { api, t, session, refresh, active } = useFinance(),
    owner = useOwnerFence();
  const [form] = Form.useForm();
  const [error, setError] = useState<unknown>(),
    [busy, setBusy] = useState(false),
    [uncertain, setUncertain] = useState(false);
  const source = useFinanceQuery({
    queryKey: ["ad-source", sourceKey],
    queryFn: ({ signal }) =>
      api.get<Row>(
        "/advertising/spend/" + encodeURIComponent(sourceKey),
        {},
        signal,
      ),
  });
  const allocatable =
    allowAllocation &&
    session.canManage &&
    active &&
    !!source.data &&
    (!source.data.shop_id || source.data.shop_id === "UNASSIGNED");
  const canWrite = useRef(false),
    inFlight = useRef(false);
  canWrite.current = allocatable;
  useEffect(() => {
    if (source.data)
      form.setFieldsValue({
        allocations: rows(source.data.allocations).filter(
          (row) =>
            !row.fingerprint || row.fingerprint === source.data?.fingerprint,
        ),
      });
  }, [source.data]);
  const fields = fundingFields.filter(
    (field) => numeric(source.data?.[field]) !== undefined,
  );
  const save = async () => {
    if (!canWrite.current || inFlight.current || !owner.isCurrent()) return;
    inFlight.current = true;
    setBusy(true);
    let dispatched = false;
    try {
      const values = await form.validateFields();
      const payload = allocationPayload(
        source.data || {},
        rows(values.allocations),
        shops,
        session.organizationId,
      );
      if (!owner.isCurrent() || !canWrite.current) return;
      dispatched = true;
      await api.send(
        "/advertising/spend/" + encodeURIComponent(sourceKey) + "/allocations",
        "PUT",
        payload,
      );
      if (owner.isCurrent()) {
        await refresh();
        onClose();
      }
    } catch (e) {
      if (owner.isCurrent()) {
        setError(e);
        if (dispatched && (!(e instanceof FinanceApiError) || e.status >= 500))
          setUncertain(true);
      }
    } finally {
      inFlight.current = false;
      if (owner.isCurrent()) setBusy(false);
    }
  };
  return (
    <FinanceModal
      open
      title={t("广告来源与核实分配")}
      onCancel={onClose}
      width={850}
      footer={
        allocatable ? (
          <Button
            type="primary"
            loading={busy}
            disabled={!source.data || uncertain}
            onClick={() => void save()}
          >
            {t("保存核实金额")}
          </Button>
        ) : null
      }
    >
      <Failure error={error || source.error} />
      {source.data && (
        <>
          <FactTable
            data={[source.data]}
            columns={[
              ["platform", "平台"],
              ["account_id", "账户"],
              ["campaign_id", "系列"],
              ["record_date", "业务日期"],
              ["currency", "原币"],
              ["source", "来源"],
              ["fingerprint", "来源版本"],
              ...fundingFields.map(
                (field) => [field, labels[field]!, "money"] as const,
              ),
            ]}
          />
          <Alert
            type="info"
            title={t(
              "按原币核实；仅填写来源已有分项，未分配余款继续保留，历史版本保留。",
            )}
          />
          <Form form={form} disabled={!allocatable} layout="vertical">
            <Form.List name="allocations">
              {(items, { add, remove }) => (
                <Space orientation="vertical" style={{ width: "100%" }}>
                  {items.map((item) => (
                    <Space key={item.key} wrap align="start">
                      <Form.Item
                        name={[item.name, "shop_id"]}
                        label={t("店铺")}
                        rules={[{ required: true }]}
                      >
                        <Select
                          style={{ minWidth: 150 }}
                          options={shops
                            .filter(
                              (shop) => shop.platform === source.data?.platform,
                            )
                            .map((shop) => ({
                              value: text(shop.id) || "",
                              label:
                                text(shop.display_name) ||
                                text(shop.name) ||
                                text(shop.id),
                            }))}
                        />
                      </Form.Item>
                      {fields.map((field) => (
                        <Form.Item
                          key={field}
                          name={[item.name, field]}
                          label={t(labels[field]!)}
                          rules={[{ required: true }]}
                        >
                          <Input style={{ width: 120 }} inputMode="decimal" />
                        </Form.Item>
                      ))}
                      {allocatable && (
                        <Button onClick={() => remove(item.name)}>
                          {t("移除")}
                        </Button>
                      )}
                    </Space>
                  ))}
                  {allocatable && (
                    <Button onClick={() => add({})}>{t("添加店铺分配")}</Button>
                  )}
                </Space>
              )}
            </Form.List>
          </Form>
          {uncertain && (
            <Alert
              type="warning"
              title={t(
                "提交结果待确认，请关闭后重新读取来源版本，不自动重发。",
              )}
            />
          )}
        </>
      )}
    </FinanceModal>
  );
}
