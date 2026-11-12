import { Alert, Button, Space, Typography } from "antd";
import type { Row } from "../../../shared/contracts";
import { numeric, record, rows, text } from "../../../shared/rows";
import { BusinessTable, Panel, StatusTag } from "../../../shared/components";
import { useFinance } from "../context";
import { bookedAmount, changeFinanceFilters, timestampText } from "../domain";
import { AmountWithStatus, FactTable } from "../presentation";
import {
  adCoverageRows,
  missingAdReason,
  scopedAccountStores,
  scopedAdAccounts,
  scopedAdShops,
} from "./advertising-domain";
function hasRecords(value: unknown): boolean {
  const row = record(value);
  return (
    !!row &&
    row.hasRecords !== false &&
    numeric(row.records) !== 0 &&
    (row.hasRecords === true ||
      numeric(row.records) !== undefined ||
      numeric(row.amount) !== undefined ||
      numeric(row.knownAmount) !== undefined)
  );
}
function BookedMoney({
  value,
  missing = "暂无记录",
}: {
  value: unknown;
  missing?: string;
}) {
  const { t } = useFinance();
  const row = record(value);
  return hasRecords(row) ? (
    <AmountWithStatus
      value={bookedAmount(row)}
      currency={text(row?.currency)}
      partial={row?.complete === false}
    />
  ) : (
    <Typography.Text type="secondary">{t(missing)}</Typography.Text>
  );
}
export function BookedBreakdowns({
  report,
  shops,
  groups,
  accounts,
  accountFailed,
}: {
  report: Row;
  shops: Row[];
  groups: Row[];
  accounts: unknown;
  accountFailed: boolean;
}) {
  const { t, filters, setFilters, navigate } = useFinance();
  const storeRows = [...rows(report.shops)];
  for (const shop of scopedAdShops(shops, accounts, filters))
    if (!storeRows.some((row) => row.shop_id === shop.id))
      storeRows.push({ shop_id: shop.id, group_id: shop.group_id });
  const drill = (row: Row, grouped: boolean, source: string) => {
    setFilters((current) =>
      changeFinanceFilters(
        current,
        grouped
          ? { groupId: text(row.group_id) || "UNGROUPED", shopId: "" }
          : { shopId: text(row.shop_id) || "" },
      ),
    );
    navigate("ads?source=" + source);
  };
  return (
    <>
      {[true, false].map((grouped) => {
        const title = grouped
          ? "分组广告费用（已入账）"
          : "店铺广告费用（已入账）";
        return (
          <section aria-label={t(title)} key={title}>
            <Panel title={t(title)}>
              <BusinessTable<Row>
                rowKey={(row) =>
                  String(grouped ? row.group_id : row.shop_id) +
                  String(row.currency)
                }
                dataSource={grouped ? rows(report.groups) : storeRows}
                pagination={false}
                columns={[
                  {
                    title: t(grouped ? "分组" : "店铺"),
                    render: (_, row) => {
                      const id =
                        text(grouped ? row.group_id : row.shop_id) || "";
                      const label = grouped
                        ? text(groups.find((group) => group.id === id)?.name) ||
                          t("未分组")
                        : text(
                            shops.find((shop) => shop.id === id)?.display_name,
                          ) ||
                          text(shops.find((shop) => shop.id === id)?.name) ||
                          (id === "UNASSIGNED" ? t("未分配") : id);
                      return (
                        <Button
                          type="link"
                          onClick={() => drill(row, grouped, "booked")}
                        >
                          {label}
                        </Button>
                      );
                    },
                  },
                  ...(
                    [
                      "settlement",
                      "commission",
                      "independent",
                      "gmv",
                      "total",
                    ] as const
                  ).map((key) => ({
                    key,
                    title: t(
                      {
                        settlement: "平台账单广告费",
                        commission: "其中广告佣金",
                        independent: "普通独立广告消耗",
                        gmv: "GMV Max 报表消耗（待核对）",
                        total: "广告费用（已入账）",
                      }[key],
                    ),
                    render: (_: unknown, row: Row) => {
                      const source = key === "total" ? row : row[key];
                      return (
                        <Space orientation="vertical" size="small">
                          <BookedMoney
                            value={source}
                            missing={
                              !grouped &&
                              (key === "independent" || key === "gmv")
                                ? missingAdReason(
                                    text(row.shop_id) || "",
                                    key,
                                    key === "gmv"
                                      ? scopedAdAccounts(
                                          accounts,
                                          shops,
                                          filters,
                                        )
                                      : accounts,
                                    accountFailed,
                                  )
                                : "暂无记录"
                            }
                          />
                          {["settlement", "independent", "gmv"].includes(key) &&
                            hasRecords(source) && (
                              <Button
                                size="small"
                                type="link"
                                onClick={() =>
                                  drill(
                                    row,
                                    grouped,
                                    key === "settlement"
                                      ? "ledger"
                                      : key === "gmv"
                                        ? "AD_GMV"
                                        : "AD",
                                  )
                                }
                              >
                                {t(
                                  key === "settlement"
                                    ? "查看平台账单广告"
                                    : key === "gmv"
                                      ? "查看 GMV Max 明细"
                                      : "查看普通独立广告",
                                )}
                              </Button>
                            )}
                        </Space>
                      );
                    },
                  })),
                ]}
              />
            </Panel>
          </section>
        );
      })}
    </>
  );
}
export function GmvSummary({ summary }: { summary: unknown }) {
  const { t, navigate } = useFinance();
  const row = record(summary) || {},
    count = numeric(row.records);
  const metric = (field: string, known: string, money: boolean) => {
    const value = hasRecords(row) ? numeric(row[field]) : undefined;
    const partial = count !== undefined && (numeric(row[known]) ?? 0) < count;
    return (
      <Space>
        <span>
          {money ? (
            <AmountWithStatus
              value={value}
              currency={text(row.currency)}
              partial={partial}
            />
          ) : hasRecords(row) ? (
            (text(row[field]) ?? "—")
          ) : (
            "—"
          )}
        </span>
        {!money && partial && (
          <StatusTag status="warning">{t("已知部分")}</StatusTag>
        )}
      </Space>
    );
  };
  return (
    <section aria-label={t("GMV Max 专项分析")}>
      <Panel
        title={t("GMV Max 专项分析")}
        extra={
          <Button onClick={() => navigate("ads?source=AD_GMV")}>
            {t("查看 GMV Max 明细")}
          </Button>
        }
      >
        <Typography.Paragraph>
          {t(
            "归因收入不是结算收入；与普通报表、平台账单的重叠待核对，不叠加入账广告或利润。",
          )}
        </Typography.Paragraph>
        <BusinessTable<Row>
          rowKey={() => "gmv"}
          dataSource={[row]}
          pagination={false}
          columns={[
            { title: t("专项消耗"), render: () => <BookedMoney value={row} /> },
            {
              title: t("归因收入"),
              render: () => metric("revenue", "known_revenues", true),
            },
            {
              title: t("SKU 订单数"),
              render: () => metric("conversions", "known_conversions", false),
            },
            {
              title: t("归因 ROI"),
              render: () => (hasRecords(row) ? (text(row.roi) ?? "—") : "—"),
            },
          ]}
        />
      </Panel>
    </section>
  );
}
export function AccountCoverage({
  accounts,
  shops,
}: {
  accounts: unknown;
  shops: Row[];
}) {
  const { t, navigate, filters } = useFinance();
  const selected = scopedAdAccounts(accounts, shops, filters);
  return (
    <Panel title={t("账户、店铺发现与同步覆盖")}>
      <Typography.Paragraph>
        {t("账户访问关系不证明店铺归属；仅展示已完成发现和已入库覆盖。")}
      </Typography.Paragraph>
      {!selected.length && (
        <Alert
          type="info"
          title={t(
            "当前范围没有已连接广告账户；账单和已导入费用仍按实际记录展示。",
          )}
        />
      )}
      {selected.map((account) => (
        <Panel
          key={text(account.advertiser_id)}
          title={text(account.name) || text(account.advertiser_id)}
        >
          <Space wrap>
            <StatusTag>
              {t("账户状态")}: {text(account.status)}
            </StatusTag>
            <StatusTag>
              {t("店铺发现")}: {text(account.discovery_status)}
            </StatusTag>
            <StatusTag>
              {t("资金状态")}:{" "}
              {text(record(account.funding)?.status) || t("未知")}
            </StatusTag>
          </Space>
          <Typography.Paragraph>
            {text(account.error_message) ||
              text(account.discovery_error) ||
              text(record(account.funding)?.error_message)}
          </Typography.Paragraph>
          <FactTable
            data={scopedAccountStores(account, shops, filters)}
            columns={[
              ["store_name", "店铺"],
              ["store_id", "平台店铺 ID"],
              ["shop_id", "财务店铺 ID"],
              ["match_status", "匹配状态", "status"],
              ["gmv_available", "GMV 可用"],
              ["report_eligible", "报表可用"],
            ]}
          />
          <section aria-label={t("广告接口覆盖")}>
            <BusinessTable<Row>
              rowKey={(row) => String(row.channel) + ":" + String(row.store_id)}
              dataSource={adCoverageRows(account, shops, filters)}
              pagination={false}
              columns={[
                {
                  title: t("报表范围"),
                  render: (_, row) =>
                    row.channel === "BASIC"
                      ? t("普通广告（账户报表）")
                      : "GMV Max · " + (text(row.store_name) || "—"),
                },
                { title: t("数据流"), dataIndex: "channel" },
                {
                  title: t("任务状态"),
                  render: (_, row) => (
                    <Space orientation="vertical">
                      <StatusTag>{t(text(row.status) || "未知")}</StatusTag>
                      {text(row.error)}
                    </Space>
                  ),
                },
                {
                  title: t("日期覆盖"),
                  render: (_, row) =>
                    numeric(row.requested_days)
                      ? `${text(row.covered_days)} / ${text(row.requested_days)}`
                      : t("未选择范围"),
                },
                {
                  title: t("数据状态"),
                  render: (_, row) => (
                    <Space wrap>
                      {row.partial === true && (
                        <StatusTag status="warning">
                          {t("已采集部分")}
                        </StatusTag>
                      )}
                      <span>{t(text(row.data_state) || "未知")}</span>
                      {row.previous === true && (
                        <StatusTag status="warning">
                          {t("上次成功数据")}
                        </StatusTag>
                      )}
                    </Space>
                  ),
                },
                {
                  title: t("返回记录数"),
                  render: (_, row) => text(row.records) ?? "—",
                },
                {
                  title: t("已采集消耗（原币）"),
                  render: (_, row) => (
                    <AmountWithStatus
                      value={row.amount}
                      currency={text(row.currency)}
                    />
                  ),
                },
                {
                  title: t("范围内最早采集时间（北京时间）"),
                  render: (_, row) =>
                    timestampText(row.oldest_observation, "Asia/Shanghai"),
                },
              ]}
            />
          </section>
          <Typography.Paragraph type="secondary">
            {t(
              "覆盖仅指成功请求的日期；没有返回记录不代表实际零消耗，报表合计为0不代表逐条为0。普通账户报表不保证包含所有 GMV Max，覆盖不等于结算对账完成。",
            )}
          </Typography.Paragraph>
        </Panel>
      ))}
      <Button onClick={() => navigate("shops")}>{t("前往财务店铺管理")}</Button>
    </Panel>
  );
}
