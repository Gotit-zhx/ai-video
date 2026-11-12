import { useState } from "react";
import { Alert, Button, Input, Space, Typography } from "antd";
import type { Row } from "../../../shared/contracts";
import { record, rows, text } from "../../../shared/rows";
import {
  BusinessTable,
  InfoTip,
  Panel,
  StatusTag,
} from "../../../shared/components";
import { FinanceModal } from "../../../shared/dialogs";
import { useFinance } from "../context";
import { timestampText } from "../domain";
import { Failure } from "../presentation";
import { enabled, useShopCommand } from "./common";

const statusLabels: Record<string, string> = {
  READY: "等待采集",
  SYNCING: "正在采集",
  SUCCESS: "最近采集成功",
  NEEDS_PERMISSION: "缺少官方广告权限",
  NEEDS_AUTHORIZATION: "需要重新授权",
  RATE_LIMITED: "等待平台限流恢复",
  ERROR: "采集异常",
  PAUSED: "已暂停",
  WAITING_SHOP: "等待匹配财务店铺",
  REMOVED: "已移除",
};
interface ShopeeAdsPanelProps {
  data?: Row | undefined;
  error?: unknown;
  loading?: boolean;
  compact?: boolean;
  onAuthorize?: () => void;
  onToggle?: (item: Row) => void;
  pending?: boolean;
}
/** Official CPC coverage is a source fact, independent of booked expenses or profit. */
export function ShopeeAdsPanel({
  data,
  error,
  loading = false,
  compact,
  onAuthorize,
  onToggle,
  pending = false,
}: ShopeeAdsPanelProps) {
  const { t, filters, session, navigate } = useFinance();
  const all = rows(data?.items);
  const scoped = all.filter(
    (item) =>
      (!filters.platform || filters.platform === "SHOPEE") &&
      (!filters.groupId ||
        (filters.groupId === "UNGROUPED"
          ? !item.group_id
          : item.group_id === filters.groupId)) &&
      (!filters.shopId || item.shop_id === filters.shopId) &&
      (!filters.currency || item.currency === filters.currency),
  );
  if (compact && filters.platform === "TIKTOK_SHOP") return null;
  const configured = data?.configured === true;
  return (
    <Panel
      title={
        <Space>
          {t("Shopee 官方广告采集")}
          <InfoTip
            title={t(
              "首次补齐最近 90 天可用日报，之后按小时调度并刷新近期数据；已同步日报数不是商品覆盖率。",
            )}
          />
        </Space>
      }
      extra={
        compact ? (
          <Button onClick={() => navigate("shops")}>
            {t("授权与采集详情")}
          </Button>
        ) : session.canManage && configured && onAuthorize ? (
          <Button onClick={onAuthorize}>{t("生成 Shopee 广告授权链接")}</Button>
        ) : undefined
      }
    >
      <Failure error={error} />
      {!!error && data && (
        <Alert
          type="warning"
          title={t("当前刷新失败，以下为上次读取的 Shopee 广告状态")}
        />
      )}
      {loading && !data && (
        <Typography.Text>{t("正在读取 Shopee 广告状态")}</Typography.Text>
      )}
      {data?.configured === false && (
        <Alert
          type="warning"
          title={t("Shopee 广告应用尚未配置")}
          description={
            text(data.configurationError) ||
            t(
              "需要正式通过审核的独立 Ads Service 应用；ERP 店铺授权不能代替广告授权。",
            )
          }
        />
      )}
      {configured && all.length === 0 && (
        <Alert
          type="info"
          title={t("尚未连接 Shopee 广告店铺")}
          description={t(
            "需要店铺管理员在官方页面完成授权后，系统才会自动采集广告日报。",
          )}
        />
      )}
      {data && (
        <Typography.Paragraph>
          {text(data.coverageBasis) ||
            t(
              "仅统计官方店铺 CPC 日报 expense；GMS 覆盖关系待核实，现金、信用额度、赠款及净消耗不倒算。",
            )}
        </Typography.Paragraph>
      )}
      {!compact && (
        <BusinessTable<Row>
          rowKey="external_shop_id"
          dataSource={scoped}
          loading={loading}
          pagination={{ pageSize: 20, showSizeChanger: false }}
          columns={[
            {
              title: t("官方店铺"),
              render: (_, item) => (
                <Space orientation="vertical">
                  <strong>{text(item.name) || t("名称未知")}</strong>
                  <span>{text(item.external_shop_id) || "—"}</span>
                </Space>
              ),
            },
            {
              title: t("财务店铺"),
              render: (_, item) =>
                text(item.shop_alias) ||
                text(item.shop_name) ||
                (item.shop_id ? text(item.shop_id) : t("等待匹配财务店铺")),
            },
            {
              title: t("原币"),
              render: (_, item) => text(item.currency) || t("未知"),
            },
            {
              title: t("采集状态"),
              render: (_, item) => (
                <Space orientation="vertical">
                  <StatusTag
                    status={
                      item.status === "SUCCESS"
                        ? "success"
                        : [
                              "ERROR",
                              "NEEDS_PERMISSION",
                              "NEEDS_AUTHORIZATION",
                            ].includes(String(item.status))
                          ? "error"
                          : "default"
                    }
                  >
                    {t(
                      statusLabels[text(item.status) || ""] ||
                        text(item.status) ||
                        "未知",
                    )}
                  </StatusTag>
                  {text(item.error_message) && (
                    <Typography.Text type="danger">
                      {text(item.error_message)}
                    </Typography.Text>
                  )}
                </Space>
              ),
            },
            {
              title: t("已同步日报数"),
              render: (_, item) => text(item.records) ?? "—",
            },
            {
              title: t("最近成功"),
              render: (_, item) =>
                timestampText(item.last_success_at, "Asia/Shanghai"),
            },
            {
              title: t("下次调度"),
              render: (_, item) => timestampText(item.next_at, "Asia/Shanghai"),
            },
            ...(session.canManage && onToggle
              ? [
                  {
                    title: t("操作"),
                    render: (_: unknown, item: Row) => (
                      <Button
                        disabled={pending}
                        loading={pending}
                        onClick={() => onToggle(item)}
                      >
                        {t(
                          enabled(item.enabled)
                            ? "暂停 Shopee 广告采集"
                            : "启用 Shopee 广告采集",
                        )}
                      </Button>
                    ),
                  },
                ]
              : []),
          ]}
        />
      )}
      {compact && configured && (
        <Typography.Text>
          {t("公司已连接")} {all.length} · {t("当前范围显示")} {scoped.length} ·{" "}
          {t("采集异常")}{" "}
          {
            scoped.filter((item) =>
              ["ERROR", "NEEDS_PERMISSION", "NEEDS_AUTHORIZATION"].includes(
                String(item.status),
              ),
            ).length
          }
        </Typography.Text>
      )}
    </Panel>
  );
}

export function ShopeeAuthorizationModal({ onClose }: { onClose: () => void }) {
  const { active, session } = useFinance();
  return active && session.canManage ? (
    <ShopeeAuthorizationContent
      key={session.organizationId + ":" + session.userId}
      onClose={onClose}
    />
  ) : null;
}
function ShopeeAuthorizationContent({ onClose }: { onClose: () => void }) {
  const { t, session } = useFinance(),
    command = useShopCommand();
  const [link, setLink] = useState<{ url: string; expiresAt: string } | null>(
      null,
    ),
    [copyStatus, setCopyStatus] = useState("");
  return (
    <FinanceModal
      open
      title={t("Shopee 广告授权")}
      onCancel={onClose}
      footer={
        <Button
          type="primary"
          loading={command.pending}
          onClick={() => {
            setLink(null);
            setCopyStatus("");
            void command.run(
              "/shopee-ads/authorize",
              "POST",
              { organizationId: session.organizationId },
              (value) => {
                const result = record(value);
                let url: URL;
                try {
                  url = new URL(text(result?.authorizationUrl) || "");
                } catch {
                  throw new Error(t("平台授权地址无效"));
                }
                if (url.protocol !== "https:" || url.username || url.password)
                  throw new Error(t("平台授权地址无效"));
                setLink({
                  url: text(result?.authorizationUrl)!,
                  expiresAt: text(result?.expiresAt) || "",
                });
              },
            );
          }}
        >
          {t("生成授权链接")}
        </Button>
      }
    >
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Failure error={command.error} />
        <Alert
          type="info"
          title={t("请由对应店铺管理员在 Shopee 官方页面完成授权")}
          description={t(
            "核对官方店铺 ID，授权完成后自动采集；回调页进入旧版财务店铺管理，本标签页继续保留。",
          )}
        />
        {link && (
          <>
            <Input.TextArea
              aria-label={t("Shopee 官方授权链接")}
              readOnly
              value={link.url}
              autoSize={{ minRows: 2, maxRows: 5 }}
            />
            <Typography.Text>
              {t("有效期至")} {timestampText(link.expiresAt, "Asia/Shanghai")}
            </Typography.Text>
            <Space wrap>
              <Button
                onClick={() => {
                  void navigator.clipboard
                    .writeText(link.url)
                    .then(() => setCopyStatus(t("已复制")))
                    .catch(() => setCopyStatus(t("复制失败，请手动复制链接")));
                }}
              >
                {t("复制授权链接")}
              </Button>
              <Button href={link.url} target="_blank" rel="noopener noreferrer">
                {t("在当前设备打开授权")}
              </Button>
            </Space>
            {copyStatus && (
              <Typography.Text role="status">{copyStatus}</Typography.Text>
            )}
          </>
        )}
      </Space>
    </FinanceModal>
  );
}
