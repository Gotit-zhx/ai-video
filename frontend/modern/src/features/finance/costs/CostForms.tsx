import { useState } from "react";
import { Alert, Button, InputNumber, Space } from "antd";
import { FinanceModal } from "../../../shared/dialogs";
import type { Row } from "../../../shared/contracts";
import { record, rows, text } from "../../../shared/rows";
import { useFinance } from "../context";
import {
  Check,
  Choice,
  Evidence,
  Feedback,
  Field,
  requireEvidence,
  useCostAction,
} from "./common";
import {
  costCandidate,
  costPath,
  decimalValid,
  id,
  metadataBody,
  safeInputs,
} from "./domain";
export type FormDone = (v: unknown) => void;
export function CostSettings({
  data,
  onDone,
}: {
  data: Row;
  onDone: FormDone;
}) {
  const { api, t, session } = useFinance();
  const settings = record(data.settings) || {},
    initialRules = record(settings.rules) || {};
  const [warehouse, setWarehouse] = useState(text(settings.warehouse) || ""),
    [unit, setUnit] = useState(text(initialRules.unit) || ""),
    [basis, setBasis] = useState(text(initialRules.basis) || "order"),
    [missing, setMissing] = useState(text(initialRules.missing) || "pending"),
    [meta, setMeta] = useState<Row>({});
  const action = useCostAction(),
    safety = safeInputs(data);
  return (
    <Space orientation="vertical">
      <Alert
        type="info"
        title={t(
          "仓库按分组原名选择；缺型号不能自动使用其他国家成本。保存将更新相关历史及后续订单。",
        )}
      />
      <Choice
        label="店铺成本仓"
        value={warehouse}
        onChange={setWarehouse}
        disabled={!session.canManage}
        options={[
          { value: "", label: "按国家唯一匹配" },
          ...[
            ...new Set(
              [...rows(data.costs), ...rows(data.manualCosts)]
                .map((c) => text(c.warehouse) || "")
                .filter(Boolean),
            ),
          ],
        ]}
      />
      <Choice
        label="计价单位"
        value={unit}
        onChange={setUnit}
        disabled={!session.canManage}
        options={["", "包", "片", "箱", "套"]}
      />
      <Choice
        label="计价月份口径"
        value={basis}
        onChange={setBasis}
        disabled={!session.canManage}
        options={[
          { value: "order", label: "下单月份" },
          { value: "shipment", label: "发货月份" },
        ]}
      />
      <Choice
        label="当月缺价"
        value={missing}
        onChange={setMissing}
        disabled={!session.canManage}
        options={[
          { value: "pending", label: "保持待补齐" },
          { value: "previous", label: "使用更早历史价并标记暂估" },
        ]}
      />
      {safety && session.canManage && (
        <Evidence value={meta} onChange={setMeta} />
      )}
      <Feedback action={action} />
      {session.canManage && (
        <Button
          disabled={!data.shopId}
          loading={action.pending}
          onClick={() =>
            void action.run(() => {
              if (safety) requireEvidence(meta);
              return api.send(
                costPath + "/" + id(data.shopId) + "/settings",
                "PUT",
                metadataBody(
                  {
                    warehouse,
                    rules: {
                      ...initialRules,
                      currency: "CNY",
                      unit,
                      basis,
                      missing,
                    },
                    version: data.settingsVersion ?? 0,
                  },
                  safety,
                  meta,
                ),
              );
            }, onDone)
          }
        >
          {t("保存成本设置")}
        </Button>
      )}
    </Space>
  );
}
function PriceField({
  value,
  onChange,
}: {
  value: string;
  onChange: (v: string) => void;
}) {
  const { t } = useFinance();
  return (
    <label>
      {t("人民币单价")}
      <InputNumber<string>
        aria-label={t("人民币单价")}
        stringMode
        min="0"
        step="0.000001"
        value={value}
        onChange={(v) => onChange(v ?? "")}
        style={{ width: "100%" }}
      />
    </label>
  );
}
export function ManualEdit({
  data,
  cost,
  onClose,
  onDone,
}: {
  data: Row;
  cost: Row;
  onClose: () => void;
  onDone: FormDone;
}) {
  const { api, t } = useFinance();
  const [price, setPrice] = useState(text(cost.unitPriceCny) || ""),
    [unit, setUnit] = useState(text(cost.unit) || ""),
    [confirmed, setConfirmed] = useState(false),
    [zero, setZero] = useState(false),
    [meta, setMeta] = useState<Row>({});
  const safety = safeInputs(data),
    action = useCostAction();
  return (
    <FinanceModal
      open
      title={t("修改手动成本")}
      onCancel={onClose}
      footer={null}
    >
      <Space orientation="vertical">
        <Alert
          type="info"
          title={`${text(cost.sku)} · ${text(cost.warehouse)}`}
        />
        <PriceField value={price} onChange={setPrice} />
        <Field
          label="成本计价单位"
          value={unit}
          onChange={setUnit}
          maxLength={40}
        />
        <Check
          label="按新单价重算历史及后续订单"
          value={confirmed}
          onChange={setConfirmed}
        />
        <Check label="确认真实零成本" value={zero} onChange={setZero} />
        {safety && <Evidence value={meta} onChange={setMeta} />}
        <Feedback action={action} />
        <Button
          loading={action.pending}
          onClick={() =>
            void action.run(
              () => {
                validatePrice(price, unit, confirmed, zero);
                if (safety) requireEvidence(meta);
                return api.send(
                  costPath +
                    "/groups/" +
                    id(data.groupId) +
                    "/manual/" +
                    id(cost.id ?? cost.manualId),
                  "PUT",
                  metadataBody(
                    {
                      version: cost.version,
                      unitPriceCny: price,
                      unit: unit.trim(),
                      confirmed: true,
                      zeroConfirmed: zero,
                    },
                    safety,
                    meta,
                  ),
                );
              },
              (v) => {
                onClose();
                onDone(v);
              },
            )
          }
        >
          {t("提交手动成本")}
        </Button>
      </Space>
    </FinanceModal>
  );
}
function validatePrice(
  price: string,
  unit: string,
  confirmed: boolean,
  zero: boolean,
) {
  if (!decimalValid(price))
    throw Error("单价必须非负，整数最多 18 位，小数最多 6 位");
  if (!/[1-9]/.test(price) && !zero) throw Error("零元价格必须确认真实零成本");
  if (!unit.trim()) throw Error("请填写成本计价单位");
  if (!confirmed) throw Error("请核对规格、价格、单位和历史影响");
}
export function MappingForm({
  data,
  product,
  onClose,
  onDone,
}: {
  data: Row;
  product: Row;
  onClose: () => void;
  onDone: FormDone;
}) {
  const { api, t } = useFinance();
  const maps = record(data.mappings) || {};
  const defaultWarehouse =
    text(record(data.settings)?.warehouse) ||
    text(data.suggestedWarehouse) ||
    "";
  const [warehouse, setWarehouse] = useState(defaultWarehouse);
  const costs = rows(data.costs),
    manual = rows(data.manualCosts);
  const saved = (w: string) =>
    record(
      maps[[data.shopId, product.product_id, product.sku_id, w].join(":")],
    ) ||
    record(maps[[data.shopId, product.product_id, product.sku_id].join(":")]) ||
    {};
  const initial = saved(defaultWarehouse),
    candidate = costCandidate(product, costs, defaultWarehouse);
  const [mode, setMode] = useState(initial.manualCostId ? "manual" : "excel"),
    [code, setCode] = useState(text(initial.code) || candidate.code),
    [manualId, setManualId] = useState(text(initial.manualCostId) || ""),
    [factor, setFactor] = useState(text(initial.factor) || candidate.factor),
    [price, setPrice] = useState(""),
    [unit, setUnit] = useState(""),
    [confirmed, setConfirmed] = useState(false),
    [zero, setZero] = useState(false),
    [meta, setMeta] = useState<Row>({});
  const action = useCostAction(),
    safety = safeInputs(data);
  const warehouseChange = (v: string) => {
    setWarehouse(v);
    setCode("");
    setManualId("");
    setFactor("1");
    setConfirmed(false);
  };
  return (
    <FinanceModal
      open
      title={t("核对 SKU / SPU 成本关联")}
      onCancel={onClose}
      footer={null}
    >
      <Space orientation="vertical">
        <Alert
          type="info"
          title={`${text(product.name) || ""} · ${text(product.spec) || ""} · SKU ${text(product.seller_sku) || "—"} · SPU ${text(product.product_id) || "—"}`}
          description={t(
            "人民币单价 × 购买数量 × 换算数量；完整组合装价格优先。已有人工关联优先，所有候选须确认。",
          )}
        />
        <Choice
          label="成本来源"
          value={mode}
          onChange={(v) => {
            setMode(v);
            setCode("");
            setManualId("");
            setConfirmed(false);
          }}
          options={[
            { value: "excel", label: "已有表格价格" },
            { value: "manual", label: "已有手动成本" },
            { value: "new", label: "新增手动成本并关联" },
          ]}
        />
        {mode === "new" ? (
          <Field
            label="成本仓"
            value={warehouse}
            onChange={warehouseChange}
            maxLength={120}
          />
        ) : (
          <Choice
            label="成本仓"
            value={warehouse}
            onChange={warehouseChange}
            options={[
              { value: "", label: "请选择成本仓" },
              ...[
                ...new Set(
                  [...costs, ...manual]
                    .map((c) => text(c.warehouse) || "")
                    .filter(Boolean),
                ),
              ],
            ]}
          />
        )}
        {mode === "new" ? (
          <>
            <Field
              label="成本型号"
              value={code}
              onChange={setCode}
              maxLength={255}
            />
            <PriceField value={price} onChange={setPrice} />
            <Field
              label="成本计价单位"
              value={unit}
              onChange={setUnit}
              maxLength={40}
            />
            <Check label="确认真实零成本" value={zero} onChange={setZero} />
          </>
        ) : mode === "manual" ? (
          <Choice
            label="手动成本资料"
            value={manualId}
            onChange={(v) => {
              setManualId(v);
              setCode(
                text(manual.find((c) => text(c.id ?? c.manualId) === v)?.sku) ||
                  "",
              );
            }}
            options={[
              { value: "", label: "请选择手动成本" },
              ...manual
                .filter((c) => c.warehouse === warehouse)
                .map((c) => ({
                  value: text(c.id ?? c.manualId) || "",
                  label: `${text(c.sku)} · ${text(c.unitPriceCny)} CNY / ${text(c.unit)}`,
                })),
            ]}
          />
        ) : (
          <Choice
            label="成本型号"
            value={code}
            onChange={setCode}
            options={[
              { value: "", label: "请选择成本型号" },
              ...costs
                .filter((c) => c.warehouse === warehouse)
                .map((c) => ({
                  value: text(c.sku) || "",
                  label: text(c.sku) || "",
                })),
            ]}
          />
        )}
        <Field label="换算数量" value={factor} onChange={setFactor} />
        <Check
          label="已核对销售规格、单价和计价单位"
          value={confirmed}
          onChange={setConfirmed}
        />
        {safety && <Evidence value={meta} onChange={setMeta} />}
        <Feedback action={action} />
        <Button
          loading={action.pending}
          onClick={() =>
            void action.run(
              () => {
                if (
                  !warehouse ||
                  !code ||
                  !decimalValid(factor, true) ||
                  !confirmed
                )
                  throw Error("请选择型号和仓库、有效换算数量，并确认销售规格");
                if (safety) requireEvidence(meta);
                if (mode === "new") validatePrice(price, unit, confirmed, zero);
                const body: Row = {
                  product_id: product.product_id,
                  sku_id: product.sku_id,
                  code,
                  warehouse,
                  factor,
                  confirmed: true,
                  version: saved(warehouse).version ?? 0,
                };
                if (mode === "manual") {
                  if (!manualId) throw Error("请选择手动成本");
                  body.manualCostId = manualId;
                }
                if (mode === "new")
                  Object.assign(body, {
                    unitPriceCny: price,
                    unit: unit.trim(),
                    zeroConfirmed: zero,
                  });
                return api.send(
                  costPath +
                    "/" +
                    id(data.shopId) +
                    (mode === "new" ? "/manual-mapping" : "/mapping"),
                  mode === "new" ? "POST" : "PUT",
                  metadataBody(body, safety, meta),
                );
              },
              (v) => {
                onClose();
                onDone(v);
              },
            )
          }
        >
          {t("提交成本关联")}
        </Button>
      </Space>
    </FinanceModal>
  );
}
