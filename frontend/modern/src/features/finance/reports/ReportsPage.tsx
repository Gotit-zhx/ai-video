import { useState } from "react";
import { Alert, Button, Descriptions, Space, Typography } from "antd";
import type { Row } from "../../../shared/contracts";
import { amountText, numeric, record, rows, text } from "../../../shared/rows";
import {
  BusinessTable,
  MetricCard,
  Panel,
  StatusTag,
} from "../../../shared/components";
import { FinanceModal } from "../../../shared/dialogs";
import { useFinance, useFinanceQuery } from "../context";
import {
  bookedAmount,
  businessToday,
  changeFinanceFilters,
  liveAverage,
  operatingRows,
  selectionKey,
} from "../domain";
import {
  AmountWithStatus,
  Failure,
  FactTable,
  ScopeEvidence,
  useReport,
  useReportReceipt,
  useVisible,
} from "../presentation";
import { ReportChart } from "./ReportChart";
import { AdvertisingPage } from "./AdvertisingPage";
import { RecordDetail } from "../records/RecordDetail";
export interface ReportsPageProps {
  view: "overview" | "live" | "ads";
  initialAdTab?: string;
}
export function ReportsPage({ view, initialAdTab }: ReportsPageProps) {
  const { session, filters } = useFinance();
  return view === "ads" ? (
    <AdvertisingPage
      initialTab={initialAdTab || "spend"}
      key={
        session.organizationId +
        session.userId +
        selectionKey({ ...filters }) +
        (initialAdTab || "spend")
      }
    />
  ) : (
    <OverviewReport
      key={
        view +
        session.organizationId +
        session.userId +
        selectionKey({ ...filters })
      }
      live={view === "live"}
    />
  );
}
function OverviewReport({ live }: { live: boolean }) {
  const { api, filters, t, active, navigate, setFilters } = useFinance(),
    visible = useVisible();
  const [dialog, setDialog] = useState(""),
    [detail, setDetail] = useState("");
  const today = businessToday();
  const selected = live
    ? {
        ...filters,
        start: today,
        end: today,
        displayCurrency: "CNY",
        currency: "",
        fxSnapshot: "",
        granularity: "day",
      }
    : filters;
  const query = useFinanceQuery({
    queryKey: [live ? "/screen" : "/overview", selectionKey({ ...selected })],
    enabled: !live || visible,
    queryFn: ({ signal }) =>
      live
        ? api.send<Row>(
            "/screen?" + new URLSearchParams({ ...selected }).toString(),
            "POST",
            undefined,
            { signal },
          )
        : api.get<Row>("/overview", { ...selected }, signal),
    refetchInterval: live && active && visible ? 15000 : false,
  });
  useReportReceipt(live ? undefined : query.data, { ...selected });
  const market = useReport("/fx/market", {}, !live);
  const data = query.data,
    profit = record(data?.profit) || {},
    advertising = record(data?.advertising) || {},
    cost = record(data?.productCosts) || {},
    total = (kind: string) =>
      rows(data?.totals).find((row) => row.kind === kind) || {};
  const currency =
    text(record(data?.filters)?.displayCurrency) ||
    text(record(data?.filters)?.currency) ||
    "CNY";
  const adTotal = record(advertising.total) || {};
  const cards: {
    label: string;
    value: unknown;
    partial?: boolean;
    estimated?: boolean;
    route?: string;
    dialog?: string;
  }[] = live
    ? [
        { label: "有效订单", value: total("ORDER").known_amounts },
        { label: "今日订单金额", value: total("ORDER").amount },
        { label: "平均客单价", value: liveAverage(total("ORDER")) },
        { label: "今日确认退款", value: total("REFUND").amount },
        { label: "今日销售收入", value: total("SETTLEMENT").revenue },
      ]
    : [
        {
          label: "销售收入",
          value: profit.settledGrossAmount ?? total("SETTLEMENT").revenue,
          partial: profit.settledGrossAmount == null,
          route: "settlements",
        },
        {
          label: "已结算订单金额",
          value: total("ORDER").amount,
          route: "orders?orderScope=SETTLED",
        },
        {
          label: "已结算平台费用",
          value: total("FEE").amount,
          route: "fee-records",
        },
        {
          label: "广告费用（已入账）",
          value: bookedAmount(adTotal),
          partial: adTotal.complete === false,
          route:
            "ads?source=" +
            (record(advertising.independent)?.hasRecords
              ? "AD"
              : record(advertising.settlement)?.hasRecords
                ? "ledger"
                : record(advertising.gmv)?.hasRecords
                  ? "AD_GMV"
                  : "booked"),
        },
        {
          label: "营业成本",
          value: profit.operatingCost ?? profit.knownOperatingCost,
          partial: profit.operatingCost == null,
          dialog: "cost",
        },
        {
          label: "利润额",
          value: profit.amount,
          estimated: profit.estimated === true,
          dialog: "profit",
        },
      ];
  const chart = (
    source: Row[],
    keys: readonly (readonly [string, string])[],
  ) => ({
    tooltip: { trigger: "axis" },
    legend: { data: keys.map(([, label]) => t(label)) },
    xAxis: {
      type: "category",
      data: source.map((row) => text(row.period) || text(row.hour) || ""),
    },
    yAxis: { type: "value" },
    series: keys.map(([key, label]) => ({
      name: t(label),
      type: "line",
      connectNulls: false,
      data: source.map((row) => numeric(row[key]) ?? null),
    })),
  });
  const drill = (id: string, grouped: boolean) =>
    setFilters((current) =>
      changeFinanceFilters(
        current,
        grouped ? { groupId: id || "UNGROUPED", shopId: "" } : { shopId: id },
      ),
    );
  const summaries = (grouped: boolean) => (
    <Panel title={t(grouped ? "分组经营明细" : "店铺经营明细")}>
      <BusinessTable<Row>
        rowKey={(r) => String(r.id) + String(r.currency)}
        dataSource={data ? operatingRows(data, grouped) : []}
        pagination={false}
        columns={[
          {
            title: t(grouped ? "分组" : "店铺"),
            dataIndex: "name",
            render: (v: unknown, r: Row) => (
              <Button
                type="link"
                onClick={() => drill(text(r.id) || "", grouped)}
              >
                {text(v)}
              </Button>
            ),
          },
          { title: t("币种"), dataIndex: "currency" },
          ...(
            [
              ["order_count", "已结算订单数"],
              ["order_amount", "订单金额"],
              ["sales", "销售收入"],
              ["fees", "已结算平台费用"],
              ["advertising", "广告费用（已入账）"],
              ["commission", "其中广告佣金"],
              ["gmv", "GMV Max 报表消耗（待核对）"],
              ["product_cost", "商品成本（人民币）"],
              ["label_cost", "贴单成本（人民币）"],
            ] as const
          ).map(([key, label]) => ({
            title: t(label),
            dataIndex: key,
            render: (v: unknown, row: Row) => (
              <AmountWithStatus
                value={v}
                partial={row[key + "_partial"] === true}
                partialLabel={key === "product_cost" ? "已算部分" : "已知部分"}
                estimated={row[key + "_estimated"] === true}
              />
            ),
          })),
        ]}
      />
    </Panel>
  );
  const sync = rows(data?.sync),
    orderSync = sync.filter((row) => row.channel === "ORDER_LIVE");
  return (
    <Space orientation="vertical" className="finance-page-stack" size="middle">
      <Failure error={query.error} />
      {query.isPending && (
        <Alert type="info" title={t("正在读取财务数据")} />
      )}{" "}
      {data && (
        <>
          <ScopeEvidence data={data} />
          {live && (
            <Panel title={t("实时同步状态")}>
              <StatusTag
                status={
                  sync.some((r) => r.status === "ERROR")
                    ? "error"
                    : "processing"
                }
              >
                {t(
                  sync.some((r) => r.status === "ERROR")
                    ? "部分同步异常"
                    : sync.some((r) =>
                          ["READY", "RUNNING", "SYNCING"].includes(
                            text(r.status) || "",
                          ),
                        )
                      ? "数据同步中"
                      : "数据看板在线",
                )}
              </StatusTag>
              <Typography.Paragraph>
                {t(
                  "页面 15 秒刷新；订单同步目标约 1 分钟。平台回传、历史回补和多店排队会有延迟。",
                )}
              </Typography.Paragraph>
              <FactTable
                data={orderSync}
                columns={[
                  ["shop_id", "店铺"],
                  ["last_success_at", "订单最近完整同步"],
                  ["status", "状态", "status"],
                ]}
              />
            </Panel>
          )}
          <div className="finance-metric-grid">
            {cards.map((card) => (
              <button
                className="finance-metric-action"
                key={card.label}
                type="button"
                disabled={!card.route && !card.dialog}
                onClick={() =>
                  card.route
                    ? navigate(card.route)
                    : setDialog(card.dialog || "")
                }
              >
                <MetricCard
                  label={t(card.label)}
                  value={
                    card.label === "有效订单"
                      ? text(card.value) || "—"
                      : amountText(card.value, currency)
                  }
                  detail={
                    card.estimated
                      ? t("历史价格暂估")
                      : card.partial
                        ? t("已知部分")
                        : card.value == null
                          ? t("数据待补齐")
                          : undefined
                  }
                  tone={
                    card.estimated || card.partial || card.value == null
                      ? "warning"
                      : "default"
                  }
                />
              </button>
            ))}
          </div>
          <Panel title={t("成本完整性")}>
            <FactTable
              data={[record(cost.total) || {}]}
              columns={[
                ["order_count", "订单数"],
                ["pending_count", "待补成本订单"],
                ["known_amount_cny", "已算商品成本（人民币）", "money"],
                ["amount_cny", "完整商品成本（人民币）", "money"],
                ["label_cost_cny", "贴单成本（人民币）", "money"],
              ]}
            />
            {Boolean(cost.processing || cost.rebuilding) && (
              <Alert type="info" title={t("成本计算中，已核算部分保留")} />
            )}
            <Typography.Paragraph>{text(cost.basis)}</Typography.Paragraph>
          </Panel>
          {!live && (
            <>
              <Typography.Paragraph>
                {text(profit.formula)}
              </Typography.Paragraph>
              <Typography.Paragraph>
                {text(advertising.profitBasis)}
              </Typography.Paragraph>
              <Panel title={t("当前市场汇率（仅供参考）")}>
                <Failure error={market.error} />
                <Descriptions
                  column={2}
                  items={["provider", "rateDate", "updatedAt", "status"].map(
                    (key) => ({
                      key,
                      label: t(
                        (
                          {
                            provider: "来源",
                            rateDate: "汇率日期",
                            updatedAt: "更新时间",
                            status: "状态",
                          } as Record<string, string>
                        )[key]!,
                      ),
                      children: text(market.data?.[key]) || "—",
                    }),
                  )}
                />
                <FactTable
                  data={
                    Array.isArray(market.data?.rates)
                      ? market.data?.rates
                      : Object.entries(record(market.data?.rates) || {}).map(
                          ([currency, rate]) => ({ currency, rate }),
                        )
                  }
                  columns={[
                    ["currency", "币种"],
                    ["rate", "参考汇率"],
                  ]}
                />
                <Typography.Text type="secondary">
                  {t("市场汇率不参与历史财务计算。")}
                </Typography.Text>
              </Panel>
            </>
          )}
          <div className="finance-report-grid">
            <ReportChart
              title={live ? "小时成交趋势" : "收入与结算趋势"}
              option={chart(
                rows(live ? data.hourly : data.trend),
                live
                  ? [["amount", "订单金额"]]
                  : [
                      ["SETTLEMENT_GROSS", "销售收入"],
                      ["ORDER", "已结算订单金额"],
                    ],
              )}
            />
            <FactTable
              title="店铺排名"
              data={rows(data.ranking).filter((r) => r.kind === "ORDER")}
              columns={[
                ["shop_id", "店铺"],
                ["amount", "订单金额", "money"],
                ["records", "订单数"],
              ]}
            />
            <FactTable
              title="订单状态分布"
              data={data.statuses}
              columns={[
                ["status", "状态", "status"],
                ["records", "订单数"],
              ]}
            />
            {!live && (
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
          </div>
          {live ? (
            <>
              <FactTable
                title="最新订单"
                data={data.recent}
                columns={[
                  ["external_id", "订单编号"],
                  ["shop_id", "店铺"],
                  ["record_date", "业务日期"],
                  ["status", "状态", "status"],
                  ["cny_amount", "人民币金额", "money"],
                  ["product_cost_cny", "已结算商品成本", "money"],
                ]}
                onRowClick={(row) => setDetail(text(row.id) || "")}
              />
            </>
          ) : (
            <>
              <ReportChart
                title="广告与物流贴单成本"
                option={chart(
                  rows(data.trend).map((row) => ({
                    ...row,
                    AD: rows(advertising.trend).find(
                      (ad) => ad.period === row.period,
                    )?.AD_TOTAL,
                  })),
                  [
                    ["AD", "广告费用（已入账）"],
                    ["COST", "物流贴单成本"],
                  ],
                )}
              />
              {summaries(true)}
              {summaries(false)}
              <FactTable
                title="广告来源分项"
                data={advertising.categories}
                columns={[
                  ["category", "来源"],
                  ["amount", "金额", "money"],
                  ["records", "笔数"],
                ]}
              />
              <Alert
                type="info"
                title={t(
                  "GMV Max 报表单列待核对，不叠加已入账广告或利润；广告佣金已包含于平台费用。",
                )}
              />
            </>
          )}
          <Typography.Text type="secondary">
            {t("更新时间")}: {text(data.updatedAt) || "—"}
          </Typography.Text>
        </>
      )}
      {dialog && (
        <FinanceModal
          open
          title={t(dialog === "profit" ? "利润计算口径" : "营业成本明细")}
          onCancel={() => setDialog("")}
          footer={<Button onClick={() => setDialog("")}>{t("关闭")}</Button>}
        >
          <Descriptions
            column={1}
            items={(dialog === "profit"
              ? [
                  "settledGrossAmount",
                  "platformFees",
                  "advertising",
                  "operatingCost",
                  "amount",
                ]
              : [
                  "productCost",
                  "knownProductCost",
                  "logisticsLabelCost",
                  "operatingCost",
                  "knownOperatingCost",
                ]
            ).map((key) => ({
              key,
              label: t(
                (
                  {
                    settledGrossAmount: "销售收入",
                    platformFees: "平台费用",
                    advertising: "独立广告消耗",
                    operatingCost: "营业成本",
                    amount: "利润额",
                    productCost: "商品成本",
                    knownProductCost: "已算商品成本",
                    logisticsLabelCost: "物流贴单成本",
                    knownOperatingCost: "已算营业成本",
                  } as Record<string, string>
                )[key]!,
              ),
              children: amountText(profit[key], currency),
            }))}
          />
          {[
            "formula",
            "basis",
            "settlementBasis",
            "operatingCostBasis",
            "marketingBasis",
            "refundBasis",
          ].map(
            (key) =>
              text(profit[key]) && (
                <Typography.Paragraph key={key}>
                  {text(profit[key])}
                </Typography.Paragraph>
              ),
          )}
          {Array.isArray(profit.notes) &&
            profit.notes.map((note, index) => (
              <Typography.Paragraph key={index}>
                {text(note)}
              </Typography.Paragraph>
            ))}
          <Button onClick={() => navigate("costs")}>
            {t("查看成本工作区")}
          </Button>
        </FinanceModal>
      )}
      {detail && (
        <RecordDetail
          recordId={detail}
          selection={{
            ...selected,
            orderScope: "ALL",
            fxSnapshot: text(record(data?.fx)?.id) || "",
          }}
          onClose={() => setDetail("")}
        />
      )}
    </Space>
  );
}
