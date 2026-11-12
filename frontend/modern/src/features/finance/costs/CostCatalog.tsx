import { useEffect, useRef, useState } from "react";
import { Alert, Button, Space } from "antd";
import type { Row } from "../../../shared/contracts";
import { FinanceModal } from "../../../shared/dialogs";
import { record, rows, text } from "../../../shared/rows";
import { useFinance } from "../context";
import {
  Check,
  Choice,
  DataTable,
  Evidence,
  Feedback,
  Field,
  requireEvidence,
  useCostAction,
} from "./common";
import {
  costCandidate,
  costMappingView,
  costPath,
  id,
  metadataBody,
  operationReceipt,
  safeInputs,
} from "./domain";
import { ConfirmOperation } from "./ConfirmOperation";
import { CostSettings, ManualEdit, MappingForm } from "./CostForms";
export function CostCatalog({
  data,
  tab,
  onRefresh,
  onTab,
}: {
  data: Row;
  tab: string;
  onRefresh: () => void;
  onTab: (t: string) => void;
}) {
  const { api, session, t } = useFinance();
  const [search, setSearch] = useState(""),
    [warehouse, setWarehouse] = useState(""),
    [month, setMonth] = useState(""),
    [status, setStatus] = useState(""),
    [importing, setImporting] = useState(false),
    [removing, setRemoving] = useState<{ kind: "file" | "manual"; row: Row }>(),
    [editing, setEditing] = useState<Row>(),
    [mapping, setMapping] = useState<Row>();
  const action = useCostAction();
  const autoSent = useRef(false);
  const costs = rows(data.costs),
    products = rows(data.products);
  const effectiveWarehouse =
    text(record(data.settings)?.warehouse) ||
    text(data.suggestedWarehouse) ||
    "";
  const mappings = record(data.mappings) || {};
  const canAuto = products.some((p) => {
    const candidate = costCandidate(p, costs, effectiveWarehouse);
    return (
      candidate.code &&
      !candidate.fixedGift &&
      !mappings[
        [data.shopId, p.product_id, p.sku_id, effectiveWarehouse].join(":")
      ] &&
      !mappings[[data.shopId, p.product_id, p.sku_id].join(":")]
    );
  });
  const auto = () =>
    action.run(
      () =>
        api.send(costPath + "/" + id(data.shopId) + "/auto-match", "POST", {}),
      () => onRefresh(),
    );
  useEffect(() => {
    if (
      !autoSent.current &&
      session.canManage &&
      data.shopId &&
      canAuto &&
      ["costs", "manual", "mapping"].includes(tab)
    ) {
      autoSent.current = true;
      void auto();
    }
  }, [session.canManage, data.shopId, canAuto, tab]);
  const done = (v: unknown) => {
    action.setNotice(operationReceipt(v));
    onRefresh();
  };
  const safety = safeInputs(data);
  const matches = (row: Row) =>
    (!warehouse || row.warehouse === warehouse) &&
    (!search ||
      [
        row.sku,
        row.seller_sku,
        row.sku_id,
        row.product_id,
        row.name,
        row.spec,
      ].some((v) => (text(v) || "").includes(search)));
  if (tab === "settings")
    return (
      <CostSettings
        key={String(data.shopId) + String(data.settingsVersion)}
        data={data}
        onDone={done}
      />
    );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title={t(
          safety
            ? "人工修改先暂存；请到导入与操作核对查看校验、差异及实际历史影响，确认前有效价格不变。"
            : "成本资料由分组共享；文件和手动成本独立保存，金额固定人民币。",
        )}
      />
      <Feedback action={action} />
      <Space wrap>
        <Field
          label="查询型号、SKU 或 SPU"
          value={search}
          onChange={setSearch}
        />
        <Choice
          label="筛选成本仓"
          value={warehouse}
          onChange={setWarehouse}
          options={[
            { value: "", label: "全部成本仓" },
            ...[
              ...new Set(
                [...costs, ...rows(data.manualCosts)]
                  .map((c) => text(c.warehouse) || "")
                  .filter(Boolean),
              ),
            ],
          ]}
        />
        {tab === "costs" && (
          <Field
            label="价格月份"
            type="month"
            value={month}
            onChange={setMonth}
          />
        )}
        <Button onClick={onRefresh}>{t("刷新成本资料")}</Button>
        {session.canManage && (
          <>
            <Button
              disabled={!data.groupId || data.groupId === "UNGROUPED"}
              onClick={() => setImporting(true)}
            >
              {t("导入分组成本表")}
            </Button>
            <Button
              disabled={!data.shopId || action.pending}
              onClick={() => void auto()}
            >
              {t("自动匹配 SKU / SPU")}
            </Button>
          </>
        )}
      </Space>
      {tab === "costs" && (
        <>
          <Alert
            type="info"
            title={t(
              "价格单元显示实际来源。后来文件优先；移除后回退剩余来源。公式请先转值，编号保留文本。",
            )}
          />
          <DataTable
            data={costs.filter(matches).flatMap((c) =>
              Object.entries(record(c.prices) || {})
                .filter(([m]) => !month || m === month)
                .map(([m, price]) => ({
                  ...c,
                  id: [c.sku, c.warehouse, m].join(":"),
                  month: m,
                  price,
                  source:
                    text(record(record(c.priceSources)?.[m])?.name) || "—",
                })),
            )}
            columns={[
              ["sku", "成本型号"],
              ["warehouse", "成本仓"],
              ["month", "月份"],
              ["price", "人民币单价"],
              ["source", "实际价格来源"],
            ]}
          />
          <DataTable
            data={data.imports}
            columns={[
              ["source_name", "来源文件"],
              ["row_count", "来源行数"],
              ["created_at", "导入时间"],
              ["legacy_merged", "历史合并资料"],
            ]}
            actions={(r) =>
              session.canManage ? (
                <Button onClick={() => setRemoving({ kind: "file", row: r })}>
                  {t("移除成本文件")}
                </Button>
              ) : null
            }
          />
        </>
      )}
      {tab === "manual" && (
        <>
          <Button disabled={!data.shopId} onClick={() => onTab("mapping")}>
            {t("去 SKU 关联添加手动成本")}
          </Button>
          <DataTable
            data={rows(data.manualCosts).filter(matches)}
            columns={[
              ["sku", "手动型号"],
              ["warehouse", "成本仓"],
              ["unitPriceCny", "人民币单价"],
              ["unit", "计价单位"],
              ["version", "资料版本"],
              ["updated_at", "更新时间"],
            ]}
            actions={(r) =>
              session.canManage ? (
                <Space wrap>
                  <Button onClick={() => setEditing(r)}>
                    {t("修改手动成本")}
                  </Button>
                  <Button
                    onClick={() => setRemoving({ kind: "manual", row: r })}
                  >
                    {t("删除手动成本")}
                  </Button>
                </Space>
              ) : null
            }
          />
        </>
      )}
      {tab === "mapping" && (
        <>
          <Alert
            type="info"
            title={`${t("当前成本仓")}：${effectiveWarehouse || t("尚未唯一确定")} · ${t("历史待补齐 SKU")} ${text(data.historicalPendingSkuCount) ?? "—"}`}
            description={t(
              "优先商品 SKU，再核对平台 SPU；完整组合装先匹配整套价格。GIFT 固定零成本。未知成本仓保持待补齐。",
            )}
          />
          {data.pendingSkusTruncated === true && (
            <Alert
              type="warning"
              title={t(
                "待补齐 SKU 超出返回上限，请按店铺核对；当前列表不代表完整目录。",
              )}
            />
          )}
          <Choice
            label="关联状态"
            value={status}
            onChange={setStatus}
            options={[
              { value: "", label: "全部状态" },
              { value: "pending", label: "待关联" },
              { value: "saved", label: "已有关系" },
              { value: "candidate", label: "自动候选" },
              { value: "gift", label: "固定零赠品" },
            ]}
          />
          <DataTable
            data={products
              .map((p): Row => {
                const current = costMappingView(p, data, effectiveWarehouse);
                return {
                  ...p,
                  matchCode: current.code,
                  matchFactor: current.factor,
                  matchMethod: t(current.method),
                  storedMethod: t(current.storedMethod),
                  matchNote: t(current.note),
                  matchStatus: current.status,
                };
              })
              .filter(
                (p) =>
                  (!status || p.matchStatus === status) &&
                  (!search ||
                    [p.seller_sku, p.sku_id, p.product_id, p.name].some((v) =>
                      (text(v) || "").includes(search),
                    )),
              )}
            columns={[
              ["name", "商品"],
              ["spec", "销售规格"],
              ["seller_sku", "商品 SKU"],
              ["product_id", "平台 SPU"],
              ["sku_id", "平台规格 ID"],
              ["matchCode", "成本型号"],
              ["matchFactor", "换算数量"],
              ["matchMethod", "当前候选 / 人工关系"],
              ["storedMethod", "服务端已保存来源"],
              ["matchNote", "核对说明"],
              ["pending_cost_reason", "历史待补原因"],
            ]}
            actions={(p) =>
              p.matchStatus === "gift" ? (
                <span>{t("无需关联")}</span>
              ) : session.canManage ? (
                <Button disabled={!data.shopId} onClick={() => setMapping(p)}>
                  {t("核对成本关联")}
                </Button>
              ) : null
            }
          />
        </>
      )}
      {importing && session.canManage && (
        <CostImport
          data={data}
          onRefresh={onRefresh}
          onClose={() => setImporting(false)}
          onDone={done}
        />
      )}
      {editing && session.canManage && (
        <ManualEdit
          data={data}
          cost={editing}
          onClose={() => setEditing(undefined)}
          onDone={done}
        />
      )}
      {mapping && session.canManage && (
        <MappingForm
          data={data}
          product={mapping}
          onClose={() => setMapping(undefined)}
          onDone={done}
        />
      )}
      {removing && (
        <ConfirmOperation
          title={removing.kind === "manual" ? "删除手动成本" : "移除成本文件"}
          description={
            removing.kind === "manual"
              ? "原关联保留为失效引用，相关订单回到待补齐，不会静默切换到 Excel 价。"
              : removing.row.legacy_merged
                ? "警告：这是历史完整合并资料，移除将撤掉其中全部价格；其他文件和手动成本保留，后台重新计算。"
                : "只移除此来源，回退仍存在的旧来源；无价格则待补齐，其他文件和手动成本保留，后台重新计算。"
          }
          safety={safety}
          onClose={() => setRemoving(undefined)}
          operation={(meta) =>
            api.send(
              costPath +
                "/groups/" +
                id(data.groupId) +
                (removing.kind === "manual" ? "/manual/" : "/files/") +
                id(removing.row.id ?? removing.row.manualId),
              "DELETE",
              removing.kind === "manual"
                ? metadataBody({ version: removing.row.version }, safety, meta)
                : safety
                  ? { metadata: meta }
                  : undefined,
            )
          }
          onDone={done}
        />
      )}
    </Space>
  );
}
function CostImport({
  data,
  onClose,
  onDone,
  onRefresh,
}: {
  data: Row;
  onClose: () => void;
  onDone: (v: unknown) => void;
  onRefresh: () => void;
}) {
  const { api, t } = useFinance();
  const [files, setFiles] = useState<File[]>([]),
    [meta, setMeta] = useState<Row>({}),
    [confirmed, setConfirmed] = useState(false);
  const action = useCostAction(),
    safety = safeInputs(data);
  return (
    <FinanceModal
      open
      title={t("导入分组成本表")}
      onCancel={onClose}
      footer={null}
    >
      <Space orientation="vertical">
        <Alert
          type="info"
          title={t(
            "每个文件独立保存，组内店铺共享；首个工作表公式转值，横向月份表或型号/仓库/月份/单价四列表。",
          )}
          description={t(
            safety
              ? "来源、计价单位和 SKU / SPU 类型用于人工核对；暂存不代表已应用。"
              : "同仓/型号/月较后来源优先；移除新来源可回退旧来源。",
          )}
        />
        <label>
          {t("选择成本文件")}
          <input
            type="file"
            accept=".xlsx,.csv"
            multiple
            aria-label={t("选择成本文件")}
            disabled={action.pending}
            onChange={(e) => setFiles(Array.from(e.target.files || []))}
          />
        </label>
        {safety && <Evidence importing value={meta} onChange={setMeta} />}
        <Check
          label="已核对分组及来源资料"
          value={confirmed}
          onChange={setConfirmed}
        />
        <Feedback action={action} />
        <Button
          disabled={!confirmed || !files.length}
          loading={action.pending}
          onClick={() =>
            void action.run(
              async () => {
                if (safety) requireEvidence(meta);
                let accepted = 0,
                  duplicate = 0;
                const receipts: string[] = [];
                try {
                  for (const file of files) {
                    if (!action.valid()) return;
                    if (!/\.(xlsx|csv)$/i.test(file.name))
                      throw Error("仅支持 XLSX 或 CSV");
                    const form = new FormData();
                    form.append("groupId", text(data.groupId) || "");
                    form.append("file", file);
                    if (safety) form.append("metadata", JSON.stringify(meta));
                    const r = await api.upload<Row>(costPath + "/import", form);
                    r.duplicate ? duplicate++ : accepted++;
                    receipts.push(operationReceipt(r));
                  }
                } catch (e) {
                  if (action.valid() && (accepted || duplicate)) onRefresh();
                  throw Error(
                    `已接收 ${accepted} 份，重复 ${duplicate} 份；${e instanceof Error ? e.message : "上传失败"}`,
                  );
                }
                return `已接收 ${accepted} 份，重复 ${duplicate} 份；${receipts.join("；")}`;
              },
              (v) => {
                onClose();
                onDone(v);
              },
            )
          }
        >
          {t(safety ? "暂存成本文件" : "确认导入成本文件")}
        </Button>
      </Space>
    </FinanceModal>
  );
}
