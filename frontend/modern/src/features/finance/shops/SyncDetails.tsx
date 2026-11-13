import { Alert, Space, Typography } from "antd";
import type { Row } from "../../../shared/contracts";
import { numeric, text } from "../../../shared/rows";
import { BusinessTable, StatusTag } from "../../../shared/components";
import { FinanceModal } from "../../../shared/dialogs";
import { useFinance } from "../context";
import { timestampText } from "../domain";
import { shopName } from "./common";
export const channelName = (value: unknown) =>
  ({
    ORDER: "历史订单",
    ORDER_LIVE: "最新订单",
    ORDER_REPAIR: "结算订单补齐",
    SETTLEMENT: "结算",
    BILL: "账单",
    REFUND: "退款",
  })[String(value)] ||
  text(value) ||
  "未知";
export function execution(state: Row) {
  return text(state.execution_state) || text(state.status) || "UNKNOWN";
}
export function syncLabel(state: Row) {
  const value = execution(state);
  return (
    {
      RUNNING: "正在抓取",
      QUEUED: "排队等待",
      WAITING_SHOP: "等待本店其他项目",
      API_WAIT: "等待平台请求间隔",
      RETRY_WAIT: "异常退避等待",
      RATE_LIMITED: "平台限流等待",
      SCHEDULED: state.status === "SYNCING" ? "分页间隔等待" : "等待计划时间",
      SUCCESS: "本轮已完成",
      DISABLED: "已暂停",
      PAUSED: "已暂停",
    }[value] ||
    text(state.status) ||
    "未知"
  );
}
export function previousMonth(states: Row[]) {
  const required = ["ORDER", "SETTLEMENT"].map((channel) => ({
    channel,
    state: states.find((s) => s.channel === channel),
  }));
  const months = [
    ...new Set(
      required
        .map(({ state }) => text(state?.previous_month))
        .filter((v): v is string => !!v && /^\d{4}-\d{2}$/.test(v)),
    ),
  ];
  const month = months.length === 1 ? months[0] : "";
  const missing = required.filter(
    ({ state }) =>
      !month ||
      state?.previous_month !== month ||
      state.previous_month_checked !== true ||
      state.previous_month_complete !== true,
  );
  return {
    month,
    complete: missing.length === 0,
    note: missing.length
      ? missing
          .map(
            ({ state, channel }) =>
              text(state?.previous_month_reason) ||
              channelName(channel) + "的上月明细尚未核验完成",
          )
          .join("；")
      : "上月订单、结算扫描已完成；抓取完成不代表与官方原始文件逐笔对账，平台后续变动仍会更新",
  };
}
export const initialIncomplete = (states: Row[]) =>
  states.some(
    (s) =>
      ["ORDER", "SETTLEMENT"].includes(String(s.channel)) &&
      (s.initial_sync_complete === false ||
        (s.initial_sync_complete == null && !s.last_success_at)),
  );
export function SyncDetails({
  shop,
  states,
  onClose,
}: {
  shop: Row;
  states: Row[];
  onClose: () => void;
}) {
  const { t } = useFinance();
  const month = previousMonth(states);
  return (
    <FinanceModal
      open
      title={t("同步详情") + " · " + shopName(shop)}
      onCancel={onClose}
      footer={null}
      width={1000}
    >
      <Alert
        type={month.complete ? "success" : "warning"}
        title={
          t(month.complete ? "上月抓取完成" : "上月抓取未完成") +
          " " +
          (month.month || "")
        }
        description={t(month.note)}
      />
      <BusinessTable<Row>
        rowKey="channel"
        dataSource={states}
        pagination={false}
        columns={[
          { title: t("项目"), render: (_, s) => t(channelName(s.channel)) },
          {
            title: t("状态"),
            render: (_, s) => <StatusTag>{t(syncLabel(s))}</StatusTag>,
          },
          {
            title: t("进度与计划"),
            render: (_, s) => (
              <Space orientation="vertical">
                {s.channel === "ORDER_REPAIR" ? (
                  <span>{t("按结算账单补齐缺失订单，不按下单日期回扫")}</span>
                ) : (
                  <span>
                    {t("当前进度")}: {windowText(s, shop)}
                  </span>
                )}
                {s.blocking_channel ? (
                  <span>
                    {t("本店正在抓取")}: {t(channelName(s.blocking_channel))}
                  </span>
                ) : null}
                {s.queue_position ? (
                  <span>
                    {t("同平台待执行顺序")}: {text(s.queue_position)}
                  </span>
                ) : null}
                {s.shop_priority_requested_at ? (
                  <span>
                    {t("优先同步顺序")}:{" "}
                    {text(s.priority_queue_position) ?? "—"} · {t("剩余项目")}:{" "}
                    {text(s.priority_pending_channels) ?? "—"}
                  </span>
                ) : null}
                <span>
                  {t("下次计划")}: {timestampText(s.next_at, "Asia/Shanghai")}
                </span>
                <span>
                  {t("平台最早继续")}:{" "}
                  {timestampText(s.platform_retry_at, "Asia/Shanghai")}
                </span>
                <span>
                  {t("最近推进")}:{" "}
                  {timestampText(s.last_progress_at, "Asia/Shanghai")}
                </span>
                <span>
                  {t("最近单项成功")}:{" "}
                  {timestampText(s.last_success_at, "Asia/Shanghai")}
                </span>
                <span>
                  {t("历史首次补齐")}:{" "}
                  {s.initial_sync_complete === true
                    ? t("已完成")
                    : s.initial_sync_complete === false
                      ? t("未完成")
                      : t("未知")}
                </span>
                <span>
                  {t("已完成月份")}: {text(s.completed_month) ?? "—"}
                </span>
                {numeric(s.pending_date_bills) !== undefined && (
                  <span>
                    {t("结算日期待确认账单")}: {text(s.pending_date_bills)}
                  </span>
                )}
                <span>{text(s.error_message)}</span>
              </Space>
            ),
          },
        ]}
      />
      <Typography.Paragraph>
        {t(
          "立即同步从现有进度优先继续；同一店铺每次执行一项，平台限流与异常退避仍生效。抓取完成不等于官方对账完成。",
        )}
      </Typography.Paragraph>
    </FinanceModal>
  );
}
function windowText(s: Row, shop: Row) {
  const start = numeric(s.window_start),
    end = numeric(s.window_end);
  if (
    start === undefined ||
    end === undefined ||
    start < 946684800 ||
    end <= start ||
    end >= 4102444800
  )
    return "—";
  return (
    timestampText(
      new Date(start * 1000).toISOString(),
      text(shop.timezone) || "Asia/Shanghai",
    ) +
    " — " +
    timestampText(
      new Date((end - 1) * 1000).toISOString(),
      text(shop.timezone) || "Asia/Shanghai",
    )
  );
}
