import { useRef, useState } from "react";
import { Alert, Button, Form, Input, Select, Space } from "antd";
import { FinanceApiError } from "../../../shared/api";
import type { Row } from "../../../shared/contracts";
import { rows, text, numeric } from "../../../shared/rows";
import { FinanceModal } from "../../../shared/dialogs";
import { useFinance } from "../context";
import { Failure, FactTable, useOwnerFence } from "../presentation";
export interface ImportDialogProps {
  open: boolean;
  onClose: () => void;
  shops: Row[];
  kind?: "BILL" | "COST" | "AD";
  funding?: boolean;
  onImported?: () => void;
}
export function ImportDialog(props: ImportDialogProps) {
  const { session, active } = useFinance();
  return props.open && active && session.canManage ? (
    <ImportSession
      key={session.organizationId + ":" + session.userId}
      {...props}
    />
  ) : null;
}
function ImportSession({
  onClose,
  shops,
  kind = "BILL",
  funding = false,
  onImported,
}: ImportDialogProps) {
  const { api, t, session } = useFinance(),
    owner = useOwnerFence();
  const [form] = Form.useForm();
  const [file, setFile] = useState<File>(),
    [preview, setPreview] = useState<Row>(),
    [mapping, setMapping] = useState<Record<string, string>>({}),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(),
    [result, setResult] = useState<Row>(),
    [unknownWrite, setUnknownWrite] = useState(false);
  const [signature, setSignature] = useState("");
  const previewRevision = useRef(0);
  const invalidate = () => {
    previewRevision.current++;
    setPreview(undefined);
    setResult(undefined);
  };
  const payload = async (isPreview: boolean) => {
    const values = await form.validateFields();
    if (!file) throw new Error(t("请选择文件"));
    if (funding && file.size > 12 * 1024 * 1024)
      throw new Error(t("文件不能超过 12 MiB"));
    const data = new FormData();
    data.append("file", file);
    if (funding) {
      for (const [key, value] of Object.entries({
        ...values,
        organizationId: session.organizationId,
        preview: String(isPreview),
      }))
        data.append(key, String(value));
    } else if (!isPreview) {
      data.append("shopId", String(values.shopId));
      data.append("kind", String(values.kind));
      data.append("mapping", JSON.stringify(mapping));
    }
    return data;
  };
  const run = async (commit: boolean) => {
    setBusy(true);
    setError(undefined);
    let dispatched = false;
    const revision = previewRevision.current;
    try {
      if (
        commit &&
        (!preview ||
          signature !==
            JSON.stringify([file?.name, file?.size, form.getFieldsValue()]))
      )
        throw new Error(t("来源或文件已变化，请重新预览"));
      if (
        commit &&
        !funding &&
        ["external_id", "date", "currency", "amount"].some((id) => !mapping[id])
      )
        throw new Error(t("请映射所有必需字段"));
      const body = await payload(!commit);
      if (!owner.isCurrent()) return;
      if (revision !== previewRevision.current)
        throw new Error(t("文件或来源已变化，请重新校验"));
      dispatched = true;
      const response = await api.upload<Row>(
        funding
          ? "/advertising/imports"
          : commit
            ? "/imports"
            : "/imports/preview",
        body,
      );
      if (!owner.isCurrent()) return;
      if (commit) {
        setResult(response);
        setPreview(undefined);
        onImported?.();
      } else {
        if (revision !== previewRevision.current)
          throw new Error(t("文件或来源已变化，请重新校验"));
        if ((numeric(response.rows) || 0) > 20000)
          throw new Error(t("文件最多 20,000 行"));
        setPreview(response);
        setSignature(
          JSON.stringify([file?.name, file?.size, form.getFieldsValue()]),
        );
      }
    } catch (e) {
      if (owner.isCurrent()) {
        setError(e);
        if (
          commit &&
          dispatched &&
          (!(e instanceof FinanceApiError) || e.status >= 500)
        )
          setUnknownWrite(true);
      }
    } finally {
      if (owner.isCurrent()) setBusy(false);
    }
  };
  const columns = Array.isArray(preview?.columns)
    ? preview.columns.flatMap((v) => (text(v) ? [text(v)!] : []))
    : [];
  return (
    <FinanceModal
      open
      title={t(funding ? "导入广告资金消耗" : "导入财务记录")}
      onCancel={onClose}
      width={800}
      footer={
        <Space>
          <Button onClick={onClose}>{t("关闭")}</Button>
          <Button loading={busy} onClick={() => void run(false)}>
            {t("校验并预览")}
          </Button>
          <Button
            type="primary"
            loading={busy}
            disabled={!preview || unknownWrite}
            onClick={() => void run(true)}
          >
            {t("确认导入")}
          </Button>
        </Space>
      }
    >
      <Alert
        type="info"
        title={t("最多 20,000 行；日期为 YYYY-MM-DD，文件内编号必须唯一。")}
        description={t(
          funding
            ? "填写真实平台账户身份；仅导入资金消耗，现金、信用额度、赠款分别保留，不推测归店。"
            : "仅作为例外补录。广告不接受充值、转账或混合非消耗分类；已有广告 API 连接的店铺不可重复导入。",
        )}
      />
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          kind,
          ...(shops.length === 1 ? { shopId: shops[0]?.id } : {}),
          platform: "TIKTOK_SHOP",
          debitNegative: "true",
        }}
        onValuesChange={invalidate}
      >
        {funding ? (
          <>
            <Form.Item
              name="platform"
              label={t("平台")}
              rules={[{ required: true }]}
            >
              <Select
                options={[
                  { value: "TIKTOK_SHOP", label: "TikTok" },
                  { value: "SHOPEE", label: "Shopee" },
                ]}
              />
            </Form.Item>
            <Form.Item
              name="accountId"
              label={t("真实平台账户 ID")}
              rules={[{ required: true, whitespace: true }]}
            >
              <Input />
            </Form.Item>
            <Form.Item
              name="accountName"
              label={t("账户名称")}
              rules={[{ required: true, whitespace: true }]}
            >
              <Input />
            </Form.Item>
            <Form.Item name="debitNegative" label={t("源金额符号")}>
              <Select
                options={[
                  { value: "true", label: t("扣费为负数") },
                  { value: "false", label: t("消耗为正数") },
                ]}
              />
            </Form.Item>
          </>
        ) : (
          <>
            <Form.Item
              name="shopId"
              label={t("店铺")}
              rules={[{ required: true }]}
            >
              <Select
                options={shops.map((s) => ({
                  value: text(s.id) || "",
                  label: text(s.display_name) || text(s.name) || text(s.id),
                }))}
              />
            </Form.Item>
            <Form.Item name="kind" label={t("记录类型")}>
              <Select
                options={["BILL", "COST", "AD"].map((value) => ({
                  value,
                  label: t(
                    { BILL: "账单", COST: "成本", AD: "独立广告" }[
                      value as "BILL"
                    ],
                  ),
                }))}
              />
            </Form.Item>
          </>
        )}
        <Form.Item label={t("CSV / XLSX 文件")}>
          <Input
            type="file"
            accept=".csv,.xlsx"
            onChange={(e) => {
              setFile(e.target.files?.[0]);
              invalidate();
            }}
          />
        </Form.Item>
      </Form>
      <Failure error={error} />
      {unknownWrite && (
        <Alert
          type="warning"
          title={t("提交结果待确认；请核对入库记录后重新打开，不会自动重发。")}
        />
      )}{" "}
      {preview && (
        <>
          <Alert
            type="success"
            title={t("预览行数") + ": " + String(preview.rows)}
          />
          {!funding && (
            <Space wrap>
              {[
                "external_id",
                "date",
                "currency",
                "amount",
                "order_id",
                "statement_id",
                "category",
              ].map((id) => (
                <label key={id}>
                  {t(
                    (
                      {
                        external_id: "唯一编号",
                        date: "日期",
                        currency: "币种",
                        amount: "金额",
                        order_id: "订单编号",
                        statement_id: "账单编号",
                        category: "分类",
                      } as Record<string, string>
                    )[id]!,
                  )}
                  <Select
                    aria-label={id}
                    style={{ width: 140 }}
                    value={mapping[id] || ""}
                    onChange={(value) =>
                      setMapping((current) => ({
                        ...current,
                        [id]: value || "",
                      }))
                    }
                    options={columns.map((value) => ({ value, label: value }))}
                  />
                </label>
              ))}
            </Space>
          )}
          <FactTable
            data={preview.sample}
            columns={(funding
              ? [
                  "record_date",
                  "campaign_id",
                  "currency",
                  "amount",
                  "cash_amount",
                  "credit_amount",
                  "grant_amount",
                ]
              : columns
            ).map((column) => [column, column])}
          />
          {funding && (
            <FactTable
              data={preview.totals}
              columns={[
                ["currency", "币种"],
                ["amount", "合计", "money"],
                ["cash_amount", "现金", "money"],
                ["credit_amount", "信用额度", "money"],
                ["grant_amount", "赠款", "money"],
              ]}
            />
          )}
        </>
      )}
      {result && (
        <Alert
          type="success"
          title={t("导入已完成")}
          description={`${t("行数")}: ${text(result.rows) || "—"} · ${t("重复")}: ${text(result.duplicateRows) ?? text(result.duplicate) ?? "—"} · ${t("新增")}: ${text(result.inserted) || "—"}`}
        />
      )}
    </FinanceModal>
  );
}
