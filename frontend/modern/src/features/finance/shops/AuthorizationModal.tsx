import { useRef, useState } from "react";
import { Alert, Button, Input, Select, Space, Typography } from "antd";
import type { Row } from "../../../shared/contracts";
import { record, rows, text } from "../../../shared/rows";
import { FinanceModal } from "../../../shared/dialogs";
import { useFinance, useFinanceQuery } from "../context";
import { Failure } from "../presentation";
import { timestampText } from "../domain";
import { useShopCommand } from "./common";
export interface AuthorizationModalProps {
  kind: "ad" | "shop";
  account?: Row;
  shop?: Row;
  onClose: () => void;
}
export function AuthorizationModal(props: AuthorizationModalProps) {
  const { active, session } = useFinance();
  return active && session.canManage ? (
    <AuthorizationContent
      key={session.organizationId + ":" + session.userId}
      {...props}
    />
  ) : null;
}
function AuthorizationContent({
  kind,
  account,
  shop,
  onClose,
}: AuthorizationModalProps) {
  const { api, t } = useFinance();
  const command = useShopCommand();
  const query = useFinanceQuery({
    queryKey: ["shop-authorization-options", kind],
    queryFn: ({ signal }) =>
      api.get<unknown>(
        kind === "ad" ? "/ads/application" : "/groups",
        {},
        signal,
      ),
  });
  const [platform, setPlatform] = useState(text(shop?.platform) || "SHOPEE"),
    [market, setMarket] = useState("ROW"),
    [groupId, setGroup] = useState(""),
    [advertiserId, setAdvertiser] = useState(
      text(account?.advertiser_id) || "",
    );
  const revision = useRef(0);
  const [link, setLink] = useState<{ url: string; expiresAt?: string } | null>(
    null,
  );
  const [copyStatus, setCopyStatus] = useState("");
  function invalidate() {
    revision.current++;
    setLink(null);
    setCopyStatus("");
  }
  const configured =
    kind === "shop"
      ? rows(query.data).length > 0
      : record(query.data)?.configured === true;
  const generate = () => {
    const token = revision.current;
    setLink(null);
    setCopyStatus("");
    const body =
      kind === "shop"
        ? { platform, market, groupId, shopId: text(shop?.id) || "" }
        : {
            advertiserId: advertiserId.trim(),
            shopId: text(account?.shop_id) || "",
            gmvStoreId: text(account?.gmv_store_id) || "",
          };
    if (!configured || (kind === "shop" && !groupId)) return;
    void command.run(
      kind === "shop" ? "/shops/authorize" : "/ads/authorize",
      "POST",
      body,
      (value) => {
        if (token !== revision.current) return;
        const result = record(value);
        let url: URL;
        try {
          url = new URL(text(result?.authorizationUrl) || "");
        } catch {
          throw new Error(t("平台授权地址无效"));
        }
        if (url.protocol !== "https:") throw new Error(t("平台授权地址无效"));
        setLink({
          url: url.href,
          ...(text(result?.expiresAt)
            ? { expiresAt: text(result?.expiresAt)! }
            : {}),
        });
      },
    );
  };
  return (
    <FinanceModal
      open
      title={t(
        kind === "shop"
          ? shop
            ? "重新授权财务店铺"
            : "连接财务店铺"
          : account?.advertiser_id
            ? "更新广告授权"
            : "广告账户授权",
      )}
      onCancel={onClose}
      footer={
        <Button
          type="primary"
          loading={command.pending}
          disabled={!configured || (kind === "shop" && !groupId)}
          onClick={generate}
        >
          {t("生成授权链接")}
        </Button>
      }
    >
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Failure error={query.error || command.error} />
        {query.isPending ? (
          <Alert type="info" title={t("正在读取授权配置")} />
        ) : !configured ? (
          <Alert
            type="warning"
            title={t(
              kind === "shop"
                ? "授权前请先创建店铺分组。"
                : "广告授权暂未准备好，请联系管理员完成服务端配置。",
            )}
          />
        ) : null}
        {kind === "shop" ? (
          <>
            <label>
              {t("授权平台")}
              <Select
                aria-label={t("授权平台")}
                value={platform}
                disabled={!!shop}
                options={[
                  { value: "SHOPEE", label: "Shopee" },
                  { value: "TIKTOK_SHOP", label: "TikTok Shop" },
                ]}
                onChange={(v) => {
                  invalidate();
                  setPlatform(v);
                }}
              />
            </label>
            <label>
              {t("授权市场")}
              <Select
                aria-label={t("授权市场")}
                value={market}
                options={[
                  { value: "ROW", label: t("东南亚 / 全球（非美国）") },
                  { value: "US", label: t("美国") },
                ]}
                onChange={(v) => {
                  invalidate();
                  setMarket(v);
                }}
              />
            </label>
            <label>
              {t("店铺分组（必选）")}
              <Select
                aria-label={t("店铺分组（必选）")}
                value={groupId}
                options={[
                  { value: "", label: t("请选择分组") },
                  ...rows(query.data).map((g) => ({
                    value: text(g.id) || "",
                    label: text(g.name) || "",
                  })),
                ]}
                onChange={(v) => {
                  invalidate();
                  setGroup(v);
                }}
              />
            </label>
            <Typography.Paragraph>
              {t(
                shop
                  ? "重新授权后自动开启同步，并使用本次所选分组，保留历史记录与同步进度。"
                  : "授权成功后自动开启同步，新店铺归入所选分组；同公司已存在的店铺保留原分组和数据，不重复导入。",
              )}
            </Typography.Paragraph>
          </>
        ) : (
          <>
            <label>
              {t("广告账户 ID")}
              <Input
                aria-label={t("广告账户 ID")}
                value={advertiserId}
                disabled={!!account?.advertiser_id}
                onChange={(e) => {
                  invalidate();
                  setAdvertiser(e.target.value);
                }}
              />
            </label>
            <Typography.Paragraph>
              {t(
                "留空连接本次授权的账户。系统自动发现店铺并按官方店铺 ID 匹配；账户访问权限不证明消耗归属，未分配费用不会强行分摊。",
              )}
            </Typography.Paragraph>
          </>
        )}
        {link && (
          <>
            <label>
              {t("官方授权链接")}
              <Input aria-label={t("官方授权链接")} readOnly value={link.url} />
            </label>
            <Button
              onClick={() => {
                void navigator.clipboard
                  ?.writeText(link.url)
                  .then(() => setCopyStatus(t("已复制链接")))
                  .catch(() => setCopyStatus(t("请选中链接后手动复制")));
                if (!navigator.clipboard)
                  setCopyStatus(t("请选中链接后手动复制"));
              }}
            >
              {t("复制授权链接")}
            </Button>
            <span role="status">{copyStatus}</span>
            <Typography.Paragraph>
              {t(
                "将链接发给账户管理员，在浏览器打开并完成授权。授权成功后会回到财务店铺管理。",
              )}
              {link.expiresAt &&
                `${t("有效至")} ${timestampText(link.expiresAt, "Asia/Shanghai")}`}
            </Typography.Paragraph>
            <a href={link.url} target="_blank" rel="noopener noreferrer">
              {t("在当前设备打开授权")}
            </a>
          </>
        )}
        <Typography.Text type="secondary">
          {t("请求失败后请检查状态，再明确重新生成；系统不会自动重试授权。")}
        </Typography.Text>
      </Space>
    </FinanceModal>
  );
}
