import { useState } from "react";
import { Alert, Button, Input, Select, Space, Typography } from "antd";
import type { Row } from "../../../shared/contracts";
import { rows, text } from "../../../shared/rows";
import {
  BusinessTable,
  MetricCard,
  Panel,
  StatusTag,
} from "../../../shared/components";
import { useFinance, useFinanceQuery } from "../context";
import { Failure, FactTable, useVisible } from "../presentation";
import { selectionKey, timestampText } from "../domain";
import { AccountCoverage } from "../reports/AdvertisingSupport";
import { scopedAdAccounts } from "../reports/advertising-domain";
import { AuthorizationModal } from "./AuthorizationModal";
import { QianyiSettingsModal } from "./QianyiSettingsModal";
import { GroupsModal, RemoveShopModal, ShopConfigModal } from "./ShopDialogs";
import {
  channelName,
  execution,
  initialIncomplete,
  previousMonth,
  SyncDetails,
  syncLabel,
} from "./SyncDetails";
import { enabled, shopName, useShopCommand } from "./common";
type Dialog =
  | { type: "config" | "remove" | "sync" | "authorize"; shop: Row }
  | { type: "groups" | "qianyi" | "connect" }
  | { type: "ad"; account?: Row };
export function ShopsPage() {
  const { active, session } = useFinance();
  return active && session.canView ? (
    <ShopsContent key={session.organizationId + ":" + session.userId} />
  ) : null;
}
function ShopsContent() {
  const { api, t, session, filters, setFilters, refresh, navigate } =
    useFinance();
  const visible = useVisible(),
    command = useShopCommand();
  const [dialog, setDialog] = useState<Dialog | null>(null),
    [search, setSearch] = useState(""),
    [monthFilter, setMonthFilter] = useState("ALL");
  const shopsQuery = useFinanceQuery({
    queryKey: ["/shops", selectionKey({})],
    queryFn: ({ signal }) => api.get<Row>("/shops", {}, signal),
    refetchInterval:
      visible && !dialog
        ? (query) =>
            rows(query.state.data?.deletions).some(
              (job) => job.status !== "COMPLETE",
            )
              ? 3000
              : 15000
        : false,
  });
  const adsQuery = useFinanceQuery({
    queryKey: ["shop-ad-accounts", filters.start, filters.end],
    queryFn: ({ signal }) =>
      api.get<unknown>(
        "/ads",
        { start: filters.start, end: filters.end },
        signal,
      ),
    refetchInterval:
      visible && !dialog
        ? rows(shopsQuery.data?.deletions).some(
            (job) => job.status !== "COMPLETE",
          )
          ? 3000
          : 15000
        : false,
  });
  const shops = rows(shopsQuery.data?.items),
    states = rows(shopsQuery.data?.sync),
    groups = rows(shopsQuery.data?.groups),
    accounts = rows(adsQuery.data),
    deletions = rows(shopsQuery.data?.deletions);
  const shopStates = (shop: Row) => states.filter((s) => s.shop_id === shop.id);
  const scoped = shops.filter(
    (s) =>
      (!filters.groupId ||
        (filters.groupId === "UNGROUPED"
          ? !s.group_id
          : s.group_id === filters.groupId)) &&
      (!filters.platform || s.platform === filters.platform) &&
      (!filters.shopId || s.id === filters.shopId) &&
      (!filters.currency || s.currency === filters.currency),
  );
  const selected = scoped.filter((s) => {
    const month = previousMonth(shopStates(s));
    const haystack = [
      s.display_name,
      s.alias,
      s.name,
      s.external_shop_id,
      s.id,
      s.region,
      s.platform,
      groups.find((g) => g.id === s.group_id)?.name,
    ]
      .map((v) => text(v) || "")
      .join(" ")
      .toLocaleLowerCase();
    return (
      (monthFilter === "ALL" ||
        (monthFilter === "COMPLETE" ? month.complete : !month.complete)) &&
      search
        .trim()
        .toLocaleLowerCase()
        .split(/\s+/)
        .every((word) => haystack.includes(word))
    );
  });
  const scopedStates = states.filter((s) =>
      scoped.some((shop) => shop.id === s.shop_id),
    ),
    executing = scopedStates.filter((s) => execution(s) === "RUNNING"),
    prioritized = scoped.filter((s) =>
      shopStates(s).some((state) => state.shop_priority_requested_at),
    );
  const displayedAccounts = scopedAdAccounts(accounts, shops, filters);
  const close = () => setDialog(null);
  const manage = session.canManage;
  const currentShop =
    dialog && "shop" in dialog
      ? shops.find((s) => s.id === dialog.shop.id) || dialog.shop
      : null;
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Space wrap>
        {manage && (
          <>
            <Button
              type="primary"
              onClick={() => setDialog({ type: "connect" })}
            >
              {t("连接店铺")}
            </Button>
            <Button onClick={() => setDialog({ type: "groups" })}>
              {t("分组管理")}
            </Button>
            <Button onClick={() => setDialog({ type: "qianyi" })}>
              {t("千易全局配置")}
            </Button>
          </>
        )}
        <Button onClick={() => navigate("qianyi")}>{t("千易售后明细")}</Button>
      </Space>
      <Failure error={shopsQuery.error || command.error} />
      {shopsQuery.error && shopsQuery.data && (
        <Alert
          type="warning"
          title={t("当前刷新失败，以下为上次读取的店铺与同步状态")}
        />
      )}
      {command.notice && <Alert type="success" title={command.notice} />}
      <Space wrap>
        <MetricCard
          label={t("公司共享财务店铺")}
          value={shopsQuery.data ? shops.length : "—"}
        />
        <MetricCard
          label={t("公司已开启财务同步")}
          value={
            shopsQuery.data
              ? shops.filter((s) => enabled(s.enabled)).length
              : "—"
          }
        />
        <MetricCard
          label={t("当前范围上月抓取完成")}
          value={
            shopsQuery.data
              ? scoped.filter((s) => previousMonth(shopStates(s)).complete)
                  .length
              : "—"
          }
          detail={t("接口完整不代表官方对账通过")}
        />
        <MetricCard
          label={t("正在抓取")}
          value={shopsQuery.data ? executing.length : "—"}
        />
        <MetricCard
          label={t("历史首次补齐未完成")}
          value={
            shopsQuery.data
              ? scoped.filter((s) => initialIncomplete(shopStates(s))).length
              : "—"
          }
        />
        <MetricCard
          label={t("优先同步店铺")}
          value={shopsQuery.data ? prioritized.length : "—"}
        />
      </Space>
      <Panel title={t("当前同步进度")}>
        <Typography.Paragraph>
          {t(
            "自动刷新；优先请求按先后排队，平台限流时等待，其他可执行店铺继续推进。",
          )}
        </Typography.Paragraph>
        {executing.map((s) => (
          <p key={String(s.shop_id) + String(s.channel)}>
            {shopName(shops.find((shop) => shop.id === s.shop_id) || {})} ·{" "}
            {t(channelName(s.channel))} · {t(syncLabel(s))}
          </p>
        ))}
        {!executing.length && shopsQuery.data && (
          <Typography.Text>
            {t("当前没有正在抓取的项目，等待调度或平台允许的请求时间。")}
          </Typography.Text>
        )}
        {prioritized.map((shop) => {
          const state = shopStates(shop).find(
            (s) => s.shop_priority_requested_at,
          )!;
          return (
            <p key={String(shop.id)}>
              {shopName(shop)} · {t("优先同步顺序")}{" "}
              {text(state.priority_queue_position) ?? "—"} · {t("剩余项目")}{" "}
              {text(state.priority_pending_channels) ?? "—"}
            </p>
          );
        })}
      </Panel>
      <Panel title={t("财务店铺")}>
        <Space wrap>
          <label>
            {t("上月抓取情况")}
            <Select
              aria-label={t("上月抓取情况")}
              value={monthFilter}
              onChange={setMonthFilter}
              options={[
                { value: "ALL", label: t("全部店铺") },
                { value: "COMPLETE", label: t("上月抓取完成") },
                { value: "INCOMPLETE", label: t("上月抓取未完成") },
              ]}
            />
          </label>
          <label>
            {t("查询店铺")}
            <Input
              type="search"
              aria-label={t("查询店铺")}
              placeholder={t("名称 / 别名 / 店铺编号")}
              maxLength={120}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </label>
          <Button onClick={() => setSearch("")}>{t("清空")}</Button>
          <span>
            {t("当前范围")} {scoped.length} · {t("符合条件")} {selected.length}
          </span>
        </Space>
        <BusinessTable<Row>
          rowKey="id"
          dataSource={selected}
          loading={shopsQuery.isPending}
          pagination={{ pageSize: 20 }}
          scroll={{ x: 1000 }}
          columns={[
            {
              title: t("店铺 / 平台"),
              render: (_, s) => {
                const month = previousMonth(shopStates(s));
                return (
                  <Space orientation="vertical">
                    <strong>{shopName(s)}</strong>
                    <span>
                      {text(s.platform)} · {text(s.external_shop_id)}
                    </span>
                    <StatusTag status={month.complete ? "success" : "warning"}>
                      {t(month.complete ? "上月抓取完成" : "上月抓取未完成")}{" "}
                      {month.month}
                    </StatusTag>
                  </Space>
                );
              },
            },
            {
              title: t("店铺分组"),
              render: (_, s) =>
                text(groups.find((g) => g.id === s.group_id)?.name) ||
                t("未分组"),
            },
            { title: t("国家"), dataIndex: "region" },
            {
              title: t("币种 / 时区"),
              render: (_, s) => (
                <>
                  {text(s.currency) || t("待配置")}
                  <br />
                  {text(s.timezone) || t("未知")}
                </>
              ),
            },
            {
              title: t("授权"),
              render: (_, s) => (
                <StatusTag>
                  {t(text(s.authorization_status) || "未知")}
                </StatusTag>
              ),
            },
            {
              title: t("财务同步"),
              render: (_, s) => (
                <>
                  {t(enabled(s.enabled) ? "开启" : "已暂停")}
                  <br />
                  {shopStates(s).length
                    ? shopStates(s).map((state) => (
                        <div key={String(state.channel)}>
                          {t(channelName(state.channel))}: {t(syncLabel(state))}
                        </div>
                      ))
                    : t("尚未开启")}
                </>
              ),
            },
            {
              title: t("最近单项成功"),
              render: (_, s) =>
                timestampText(s.last_success_at, "Asia/Shanghai"),
            },
            {
              title: t("操作"),
              render: (_, s) => (
                <Space wrap>
                  <Button onClick={() => setDialog({ type: "sync", shop: s })}>
                    {t("同步详情")}
                  </Button>
                  {manage ? (
                    <>
                      <Button
                        onClick={() => setDialog({ type: "config", shop: s })}
                      >
                        {t("配置")}
                      </Button>
                      <Button
                        disabled={!enabled(s.enabled)}
                        loading={command.pending}
                        onClick={() =>
                          void command.run(
                            "/shops/" +
                              encodeURIComponent(String(s.id)) +
                              "/sync",
                            "POST",
                            undefined,
                            refresh,
                          )
                        }
                      >
                        {t("立即同步")}
                      </Button>
                      <Button
                        onClick={() =>
                          setDialog({ type: "authorize", shop: s })
                        }
                      >
                        {t("重新授权")}
                      </Button>
                      <Button
                        danger
                        onClick={() => setDialog({ type: "remove", shop: s })}
                      >
                        {t("移除")}
                      </Button>
                    </>
                  ) : (
                    <span>{t("只读")}</span>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Panel>
      {deletions.length > 0 && (
        <Panel title={t("店铺数据删除进度")}>
          <Typography.Paragraph>
            {t("后台分批清理，中断自动续接；共享成本表和其他店铺保留。")}
          </Typography.Paragraph>
          <FactTable
            data={deletions.map((j) => ({
              ...j,
              id: j.shop_id,
              status_label:
                j.status === "COMPLETE"
                  ? t("已彻底删除")
                  : j.status === "RETRY"
                    ? t("正在重试，进度已保留")
                    : t("正在删除"),
            }))}
            columns={[
              ["shop_label", "店铺"],
              ["status_label", "状态", "status"],
              ["deleted_rows", "已清理记录", "number"],
              ["requested_at", "提交时间"],
            ]}
          />
        </Panel>
      )}
      <Typography.Paragraph>
        {t(
          "立即同步会从现有进度优先推进各项任务，直到本轮完整扫描结束；重复点击不会重置进度。平台限流和异常退避仍会生效。首次分批补齐最近 90 天可用数据，完成后继续定时同步。",
        )}
      </Typography.Paragraph>
      <Panel title={t("广告账户")}>
        <Failure error={adsQuery.error} />
        {adsQuery.error && adsQuery.data !== undefined && (
          <Alert
            type="warning"
            title={t("当前刷新失败，以下为上次读取的广告账户与覆盖状态")}
          />
        )}
        <Typography.Paragraph>
          {t("公司已连接")} {adsQuery.data ? accounts.length : "—"} ·{" "}
          {t("当前范围显示")} {adsQuery.data ? displayedAccounts.length : "—"}{" "}
          {t(
            "个广告账户。未分配账户费用不猜测归属；普通账户报表与 GMV Max 分别展示。",
          )}
        </Typography.Paragraph>
        <Space>
          {manage && (
            <Button onClick={() => setDialog({ type: "ad" })}>
              {t("广告账户授权")}
            </Button>
          )}
          {displayedAccounts.length < accounts.length && (
            <Button
              onClick={() =>
                setFilters((f) => ({
                  ...f,
                  groupId: "",
                  shopId: "",
                  platform: "",
                  currency: "",
                  fxSnapshot: "",
                }))
              }
            >
              {t("查看全部广告账户")}
            </Button>
          )}
        </Space>
        <BusinessTable<Row>
          rowKey="advertiser_id"
          dataSource={displayedAccounts}
          pagination={false}
          loading={adsQuery.isPending}
          columns={[
            {
              title: t("广告账户"),
              render: (_, a) => (
                <>
                  {text(a.name)} · {text(a.advertiser_id)}
                </>
              ),
            },
            {
              title: t("归属"),
              render: (_, a) =>
                a.shop_id
                  ? shopName(
                      shops.find((s) => s.id === a.shop_id) || {
                        id: a.shop_id,
                      },
                    )
                  : t("未分配账户消耗；已发现店铺访问关系不等于归属"),
            },
            { title: t("币种"), dataIndex: "currency" },
            {
              title: t("状态"),
              render: (_, a) => (
                <StatusTag>
                  {!enabled(a.enabled)
                    ? t("已暂停")
                    : t(text(a.status) || "未知")}
                </StatusTag>
              ),
            },
            {
              title: t("最近同步"),
              render: (_, a) =>
                timestampText(a.last_success_at, "Asia/Shanghai"),
            },
            {
              title: t("操作"),
              render: (_, a) =>
                manage ? (
                  <Space>
                    <Button
                      loading={command.pending}
                      onClick={() =>
                        void command.run(
                          "/ads/" + encodeURIComponent(String(a.advertiser_id)),
                          "PUT",
                          { enabled: !enabled(a.enabled) },
                          refresh,
                        )
                      }
                    >
                      {t(enabled(a.enabled) ? "暂停" : "启用")}
                    </Button>
                    <Button
                      onClick={() => setDialog({ type: "ad", account: a })}
                    >
                      {t("更新授权")}
                    </Button>
                  </Space>
                ) : (
                  t("只读")
                ),
            },
          ]}
        />
      </Panel>
      {adsQuery.data !== undefined && (
        <AccountCoverage accounts={accounts} shops={shops} />
      )}
      {dialog?.type === "sync" && currentShop && (
        <SyncDetails
          shop={currentShop}
          states={shopStates(currentShop)}
          onClose={close}
        />
      )}
      {manage && (
        <>
          {dialog?.type === "config" && currentShop && (
            <ShopConfigModal
              shop={currentShop}
              groups={groups}
              onClose={close}
            />
          )}
          {dialog?.type === "remove" && currentShop && (
            <RemoveShopModal shop={currentShop} onClose={close} />
          )}
          {dialog?.type === "groups" && <GroupsModal onClose={close} />}
          {dialog?.type === "connect" && (
            <AuthorizationModal kind="shop" onClose={close} />
          )}
          {dialog?.type === "authorize" && currentShop && (
            <AuthorizationModal
              kind="shop"
              shop={currentShop}
              onClose={close}
            />
          )}
          {dialog?.type === "ad" && (
            <AuthorizationModal
              kind="ad"
              {...(dialog.account ? { account: dialog.account } : {})}
              onClose={close}
            />
          )}
          <QianyiSettingsModal
            open={dialog?.type === "qianyi"}
            onClose={close}
          />
        </>
      )}
    </Space>
  );
}
