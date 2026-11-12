import { useState } from "react";
import { Alert, Button, Input, Select, Space, Switch, Typography } from "antd";
import type { Row } from "../../../shared/contracts";
import { rows, text } from "../../../shared/rows";
import { FinanceModal } from "../../../shared/dialogs";
import { useFinance, useFinanceQuery } from "../context";
import { Failure, FactTable, useVisible } from "../presentation";
import { businessToday, timestampText } from "../domain";
import { useShopCommand } from "./common";
export interface QianyiSettingsModalProps {
  open: boolean;
  onClose: () => void;
  onSaved?: () => void;
}
/** Company-level only. Close, inactive surface and owner changes discard secrets and pending UI. */
export function QianyiSettingsModal(props: QianyiSettingsModalProps) {
  const { session, active } = useFinance();
  return props.open && session.canManage && active ? (
    <QianyiModal
      key={session.organizationId + ":" + session.userId}
      {...props}
    />
  ) : null;
}
function QianyiModal({ onClose, onSaved }: QianyiSettingsModalProps) {
  const { api, t } = useFinance();
  const visible = useVisible();
  const query = useFinanceQuery({
    queryKey: ["qianyi-settings"],
    queryFn: ({ signal }) => api.get<Row>("/qianyi", {}, signal),
    refetchInterval: visible
      ? (query) => (query.state.data?.enabled === true ? 10000 : false)
      : false,
  });
  return (
    <FinanceModal
      open
      title={t("千易全局配置")}
      onCancel={onClose}
      footer={null}
      width={900}
    >
      <Failure error={query.error} />
      {query.data ? (
        <SettingsForm
          initial={query.data}
          current={query.data}
          onSaved={async () => {
            onSaved?.();
            await query.refetch();
          }}
        />
      ) : (
        <Alert type="info" title={t("正在读取公司千易配置")} />
      )}
    </FinanceModal>
  );
}
function SettingsForm({
  initial,
  current,
  onSaved,
}: {
  initial: Row;
  current: Row;
  onSaved: () => Promise<void>;
}) {
  const { api, t } = useFinance();
  const command = useShopCommand();
  const visible = useVisible();
  const [identity, setIdentity] = useState({
    environment: text(initial.environment) || "CN",
    appId: text(initial.app_id) || "",
    hasSecret: initial.hasSecret === true,
  });
  const [environment, setEnvironment] = useState(identity.environment),
    [appId, setApp] = useState(identity.appId),
    [secret, setSecret] = useState(""),
    [syncStart, setStart] = useState(
      text(initial.sync_start)?.slice(0, 10) || defaultStart(),
    ),
    [enabled, setEnabled] = useState(initial.enabled !== false),
    [page, setPage] = useState(1);
  const associations = useFinanceQuery({
    queryKey: ["qianyi-settings-shops", page],
    queryFn: ({ signal }) =>
      api.get<unknown>("/qianyi/shops", { page }, signal),
    refetchInterval: visible && current.enabled === true ? 10000 : false,
  });
  function save() {
    if (!appId.trim()) {
      command.setError(new Error(t("请输入千易 App ID")));
      return;
    }
    if (
      !secret &&
      (!identity.hasSecret ||
        identity.environment !== environment ||
        identity.appId !== appId.trim())
    ) {
      command.setError(new Error(t("更换环境或 App ID 必须重新输入 Secret")));
      return;
    }
    const today = businessToday(),
      min = String(Number(today.slice(0, 4)) - 5) + today.slice(4);
    if (
      !/^\d{4}-\d{2}-\d{2}$/.test(syncStart) ||
      syncStart > today ||
      syncStart < min
    ) {
      command.setError(
        new Error(t("历史同步开始日期必须在最近五年内且不晚于今天")),
      );
      return;
    }
    void command.run(
      "/qianyi",
      "PUT",
      {
        environment,
        appId: appId.trim(),
        appSecret: secret,
        syncStart,
        enabled,
      },
      async () => {
        setIdentity({ environment, appId: appId.trim(), hasSecret: true });
        setSecret("");
        await Promise.all([onSaved(), associations.refetch()]);
      },
    );
  }
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Paragraph>
        {t(
          "公司只需配置一次，系统按平台和平台店铺编号自动关联全部财务店铺。未关联或编号不唯一时不会按相似名称猜测。",
        )}
      </Typography.Paragraph>
      <Alert
        type="warning"
        title={t(
          "改变历史同步开始日期会清空关联店铺同步进度后重跑；相同日期保留检查点。更换 App ID 或环境必须输入新密钥，已有其他账户关联时服务端会拒绝切换。",
        )}
      />
      <label>
        {t("千易环境")}
        <Select
          aria-label={t("千易环境")}
          value={environment}
          options={["CN", "ASIA", "TEST"].map((value) => ({
            value,
            label: value,
          }))}
          onChange={setEnvironment}
        />
      </label>
      <label>
        {t("千易 App ID")}
        <Input
          aria-label={t("千易 App ID")}
          autoComplete="off"
          maxLength={128}
          value={appId}
          onChange={(e) => setApp(e.target.value)}
        />
      </label>
      <label>
        {t("千易 App Secret")}
        <Input.Password
          aria-label={t("千易 App Secret")}
          autoComplete="new-password"
          maxLength={4096}
          value={secret}
          placeholder={t(
            identity.hasSecret ? "已保存，留空保持原密钥" : "请输入 App Secret",
          )}
          onChange={(e) => setSecret(e.target.value)}
        />
      </label>
      <label>
        {t("历史同步开始日期")}
        <Input
          aria-label={t("历史同步开始日期")}
          type="date"
          value={syncStart}
          onChange={(e) => setStart(e.target.value)}
        />
      </label>
      <label>
        {t("千易自动同步")}{" "}
        <Switch
          aria-label={t("千易自动同步")}
          checked={enabled}
          onChange={setEnabled}
        />
      </label>
      <Space>
        <Button type="primary" loading={command.pending} onClick={save}>
          {t("保存全局配置")}
        </Button>
        <Button
          disabled={!current.configured || current.enabled === false}
          loading={command.pending}
          onClick={() =>
            void command.run("/qianyi/sync", "POST", undefined, async () => {
              await Promise.all([onSaved(), associations.refetch()]);
            })
          }
        >
          {t("立即关联并同步")}
        </Button>
      </Space>
      <Failure error={command.error || associations.error} />
      <span role="status">{command.notice}</span>
      <Typography.Paragraph>
        {t("状态")}: {text(current.status) || t("未知")} · {t("已关联")}{" "}
        {text(current.bound_count) ?? "—"} / {text(current.shop_count) ?? "—"} ·{" "}
        {t("成本口径已核验")} {text(current.verified_count) ?? "—"} ·{" "}
        {t("最近成功")}{" "}
        {timestampText(current.last_success_at, "Asia/Shanghai")}
      </Typography.Paragraph>
      {text(current.error_message) && (
        <Alert type="warning" title={text(current.error_message)} />
      )}
      <FactTable
        data={associations.data}
        columns={[
          ["name", "财务店铺"],
          ["platform", "平台"],
          ["region", "国家"],
          ["remote_shop_name", "千易店铺"],
          ["status", "同步状态", "status"],
          ["last_success_at", "最近成功"],
          ["error_message", "异常说明"],
          ["cost_basis_verified", "成本口径已核验"],
        ]}
      />
      <Space>
        <Button disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>
          {t("上一页")}
        </Button>
        <span>
          {t("第")} {page} {t("页")}
        </span>
        <Button
          disabled={rows(associations.data).length < 50 || page >= 100000}
          onClick={() => setPage((p) => p + 1)}
        >
          {t("下一页")}
        </Button>
      </Space>
    </Space>
  );
}
function defaultStart() {
  const today = businessToday();
  const date = new Date(today + "T00:00:00Z");
  date.setUTCDate(1);
  date.setUTCMonth(date.getUTCMonth() - 3);
  return date.toISOString().slice(0, 10);
}
