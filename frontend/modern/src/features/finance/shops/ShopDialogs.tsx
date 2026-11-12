import { useState } from "react";
import { Alert, Button, Input, Select, Space, Switch, Typography } from "antd";
import type { Row } from "../../../shared/contracts";
import { numeric, rows, text } from "../../../shared/rows";
import { FinanceModal } from "../../../shared/dialogs";
import { useFinance, useFinanceQuery } from "../context";
import { Failure } from "../presentation";
import { enabled, shopName, useShopCommand } from "./common";
export function ShopConfigModal({
  shop,
  groups,
  onClose,
}: {
  shop: Row;
  groups: Row[];
  onClose: () => void;
}) {
  const { t, refresh, session } = useFinance(),
    command = useShopCommand();
  const [groupId, setGroup] = useState(text(shop.group_id) || ""),
    [currency, setCurrency] = useState(text(shop.currency) || ""),
    [timezone, setZone] = useState(text(shop.timezone) || "Asia/Shanghai"),
    [alias, setAlias] = useState(
      text(shop.alias) || text(shop.display_name) || "",
    ),
    [sync, setSync] = useState(enabled(shop.enabled));
  return (
    <FinanceModal
      open
      title={t("配置财务店铺")}
      onCancel={onClose}
      footer={
        <Button
          type="primary"
          loading={command.pending}
          disabled={!session.canManage || !currency.trim() || !timezone.trim()}
          onClick={() =>
            void command.run(
              "/shops/" + encodeURIComponent(String(shop.id)),
              "PUT",
              {
                groupId,
                currency: currency.trim().toUpperCase(),
                timezone: timezone.trim(),
                enabled: sync,
                alias: alias.trim(),
              },
              async () => {
                onClose();
                await refresh();
              },
            )
          }
        >
          {t("保存配置")}
        </Button>
      }
    >
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Typography.Text>
          {t("平台原名")}: {text(shop.name)}
        </Typography.Text>
        <Failure error={command.error} />
        <label>
          {t("店铺别名")}
          <Input
            aria-label={t("店铺别名")}
            value={alias}
            maxLength={100}
            onChange={(e) => setAlias(e.target.value)}
          />
        </label>
        <label>
          {t("店铺分组")}
          <Select
            aria-label={t("店铺分组")}
            value={groupId}
            options={[
              { value: "", label: t("未分组") },
              ...groups.map((g) => ({
                value: String(g.id),
                label: text(g.name) || "",
              })),
            ]}
            onChange={setGroup}
          />
        </label>
        <label>
          {t("店铺结算币种")}
          <Input
            aria-label={t("店铺结算币种")}
            value={currency}
            onChange={(e) => setCurrency(e.target.value)}
          />
        </label>
        <label>
          {t("店铺业务时区")}
          <Input
            aria-label={t("店铺业务时区")}
            value={timezone}
            onChange={(e) => setZone(e.target.value)}
          />
        </label>
        <label>
          {t("自动同步")}{" "}
          <Switch
            aria-label={t("自动同步")}
            checked={sync}
            onChange={setSync}
          />
        </label>
      </Space>
    </FinanceModal>
  );
}
export function RemoveShopModal({
  shop,
  onClose,
}: {
  shop: Row;
  onClose: () => void;
}) {
  const { t, refresh, setFilters, session } = useFinance(),
    command = useShopCommand();
  const [confirmation, setConfirmation] = useState("");
  const name = shopName(shop);
  return (
    <FinanceModal
      open
      title={t("删除财务店铺及数据")}
      onCancel={onClose}
      footer={
        <Button
          danger
          type="primary"
          disabled={!session.canManage || confirmation !== name}
          loading={command.pending}
          onClick={() => {
            if (confirmation !== name) return;
            void command.run(
              "/shops/" + encodeURIComponent(String(shop.id)),
              "DELETE",
              undefined,
              async () => {
                setFilters((f) =>
                  f.shopId === shop.id
                    ? { ...f, shopId: "", fxSnapshot: "" }
                    : f,
                );
                onClose();
                await refresh();
              },
            );
          }}
        >
          {t("永久删除")}
        </Button>
      }
    >
      <Alert
        type="error"
        title={t("此操作不可恢复")}
        description={
          <>
            {t(
              "将永久删除该店铺的财务订单、退款、结算、费用、成本关联、导入记录及本地授权凭据，同时删除该店绑定的财务广告账户。",
            )}
            「{name}」
          </>
        }
      />
      <Typography.Paragraph>
        {t(
          "提交后立即停止同步，历史数据在后台分批清理。其他店铺、分组共享成本表和商品运营中的店铺不受影响。",
        )}
      </Typography.Paragraph>
      <label>
        {t("输入店铺名称确认删除")}：{name}
        <Input
          aria-label={t("输入店铺名称确认删除")}
          autoComplete="off"
          value={confirmation}
          onChange={(e) => setConfirmation(e.target.value)}
        />
      </label>
      <Failure error={command.error} />
    </FinanceModal>
  );
}
export function GroupsModal({ onClose }: { onClose: () => void }) {
  const { api, t, refresh, setFilters } = useFinance(),
    command = useShopCommand();
  const [name, setName] = useState(""),
    [names, setNames] = useState<Record<string, string>>({});
  const query = useFinanceQuery({
    queryKey: ["shop-groups"],
    queryFn: ({ signal }) => api.get<unknown>("/groups", {}, signal),
  });
  const reload = async () => {
    await Promise.all([query.refetch(), refresh()]);
  };
  return (
    <FinanceModal
      open
      title={t("店铺分组管理")}
      onCancel={onClose}
      footer={null}
    >
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Typography.Paragraph>
          {t(
            "同公司有财务权限的成员共享分组；已有店铺可在配置中调整归属。非空分组不能删除。",
          )}
        </Typography.Paragraph>
        <Failure error={query.error || command.error} />
        <span role="status">{command.notice}</span>
        <label>
          {t("新分组名称")}
          <Input
            aria-label={t("新分组名称")}
            maxLength={80}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <Button
          disabled={!name.trim()}
          loading={command.pending}
          onClick={() =>
            void command.run(
              "/groups",
              "POST",
              { name: name.trim() },
              async () => {
                setName("");
                await reload();
              },
            )
          }
        >
          {t("添加分组")}
        </Button>
        {rows(query.data).map((g) => {
          const id = String(g.id),
            value = names[id] ?? text(g.name) ?? "";
          return (
            <Space key={id} wrap>
              <Input
                aria-label={t("分组名称") + " " + text(g.name)}
                maxLength={80}
                value={value}
                onChange={(e) =>
                  setNames((n) => ({ ...n, [id]: e.target.value }))
                }
              />
              <span>
                {text(g.shop_count) ?? "—"} {t("家店铺")}
              </span>
              <Button
                loading={command.pending}
                disabled={!value.trim()}
                onClick={() =>
                  void command.run(
                    "/groups/" + encodeURIComponent(id),
                    "PUT",
                    { name: value.trim() },
                    reload,
                  )
                }
              >
                {t("保存")}
              </Button>
              <Button
                danger
                loading={command.pending}
                disabled={numeric(g.shop_count) !== 0}
                onClick={() =>
                  void command.run(
                    "/groups/" + encodeURIComponent(id),
                    "DELETE",
                    undefined,
                    async () => {
                      setFilters((f) =>
                        f.groupId === id
                          ? { ...f, groupId: "", shopId: "", fxSnapshot: "" }
                          : f,
                      );
                      await reload();
                    },
                  )
                }
              >
                {t("删除")}
              </Button>
            </Space>
          );
        })}
      </Space>
    </FinanceModal>
  );
}
