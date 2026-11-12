import { useState } from "react";
import { Alert, Button, Space } from "antd";
import type { Row } from "../../../shared/contracts";
import { FinanceModal } from "../../../shared/dialogs";
import { record, rows, text } from "../../../shared/rows";
import { useFinance, useFinanceQuery } from "../context";
import { Failure } from "../presentation";
import {
  Check,
  DataTable,
  Evidence,
  Feedback,
  Field,
  Pages,
  requireEvidence,
  useCostAction,
  useCostRead,
} from "./common";
import { costPath, id, operationReceipt } from "./domain";
const endpoint = costPath + "/input-changes";
const labels: Record<string, string> = {
  BLOCKED: "资料有误，需修正",
  REVIEW: "等待核对确认",
  READY: "等待确认",
  APPLIED: "已应用",
  REJECTED: "已驳回",
  STALE: "预览已过期",
};
export function InputReview() {
  const { filters, t } = useFinance();
  const [page, setPage] = useState(1),
    [selected, setSelected] = useState(""),
    [notice, setNotice] = useState("");
  const query = useCostRead(
    endpoint,
    { groupId: filters.groupId, shopId: filters.shopId, page },
    selected ? 0 : 15000,
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title={t(
          "人工资料暂存不会改变有效成本。请核对原始声明、校验原因、差异和实际影响范围。",
        )}
      />
      <Failure error={query.error} />
      {notice && <Alert type="info" role="status" title={notice} />}
      <Button onClick={() => void query.refetch()}>{t("刷新核对记录")}</Button>
      <DataTable
        data={rows(query.data?.items).map((c) => ({
          ...c,
          statusLabel: labels[text(c.status) || ""] || c.status,
          scope:
            typeof c.actualScope === "string"
              ? c.actualScope
              : record(c.impact)?.actualScope,
        }))}
        columns={[
          ["sourceName", "操作来源"],
          ["type", "操作类型"],
          ["statusLabel", "状态"],
          ["submittedBy", "提交者"],
          ["scope", "实际影响范围"],
        ]}
        actions={(c) => (
          <Button onClick={() => setSelected(text(c.id) || "")}>
            {t("查看核对单")}
          </Button>
        )}
      />
      <Pages data={query.data} page={page} onChange={setPage} />
      {selected && (
        <ReviewDetail
          changeId={selected}
          onClose={() => setSelected("")}
          onDone={(v) => {
            setNotice(operationReceipt(v));
            void query.refetch();
          }}
        />
      )}
    </Space>
  );
}
function ReviewDetail({
  changeId,
  onClose,
  onDone,
}: {
  changeId: string;
  onClose: () => void;
  onDone: (v: unknown) => void;
}) {
  const { api, t } = useFinance();
  const [opening] = useState(() => crypto.randomUUID());
  const query = useFinanceQuery({
    queryKey: ["cost-input-detail", changeId, opening],
    gcTime: 0,
    queryFn: ({ signal }) =>
      api.get<Row>(endpoint + "/" + id(changeId), {}, signal),
  });
  return (
    <FinanceModal
      open
      title={t("人工成本操作核对")}
      onCancel={onClose}
      footer={null}
      width={960}
    >
      <Failure error={query.error} />
      <Button onClick={() => void query.refetch()}>
        {t("重新读取最新预览")}
      </Button>
      {query.data && (
        <ReviewActions
          key={String(query.data.version)}
          change={query.data}
          onDone={onDone}
          onClose={onClose}
          onRefresh={() => query.refetch().then(() => {})}
        />
      )}
    </FinanceModal>
  );
}
function ReviewActions({
  change,
  onClose,
  onDone,
  onRefresh,
}: {
  change: Row;
  onClose: () => void;
  onDone: (v: unknown) => void;
  onRefresh: () => Promise<void>;
}) {
  const { api, session, t } = useFinance();
  const metadata = record(change.metadata) || {},
    validation = record(change.validation) || {},
    impact = record(change.impact) || {},
    scope = record(change.actualScope) || {},
    summary = record(change.inputSummary) || {};
  const [reason, setReason] = useState(
      text(metadata.reason ?? change.reason) || "",
    ),
    [evidence, setEvidence] = useState(
      text(metadata.evidenceRef ?? change.evidenceRef) || "",
    ),
    [ack, setAck] = useState(false),
    [history, setHistory] = useState(false),
    [revision, setRevision] = useState(false),
    [mode, setMode] = useState(""),
    [meta, setMeta] = useState<Row>(metadata);
  const action = useCostAction();
  const historical =
      impact.hasHistorical === true || scope.hasHistorical === true,
    needsRevision =
      validation.priceOverride === true ||
      metadata.revision === true ||
      rows(validation.issues).some((i) =>
        ["PRICE_OVERRIDE", "PRICE_REVISION_REQUIRED"].includes(String(i.code)),
      );
  const editable = session.canManage;
  const canConfirm =
    editable &&
    change.canConfirm === true &&
    ["READY", "REVIEW"].includes(String(change.status));
  async function submit(kind: string, body: Row) {
    await action.run(
      async () => {
        try {
          return await api.send(
            endpoint + "/" + id(change.id) + "/" + kind,
            "POST",
            { version: change.version, ...body },
          );
        } catch (e) {
          await onRefresh();
          throw e;
        }
      },
      (v) => {
        onClose();
        onDone(v);
      },
    );
  }
  const diffs = Array.isArray(change.diff)
    ? rows(change.diff)
    : rows(record(change.diff)?.items ?? record(change.diff)?.rows);
  const display = (v: unknown): string =>
    v == null
      ? "无"
      : typeof v === "object"
        ? Object.entries(record(v) || {})
            .map(([k, x]) => `${k}: ${text(x) ?? "见配置明细"}`)
            .join("；")
        : text(v) || "";
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type={change.status === "BLOCKED" ? "error" : "info"}
        title={`${text(change.sourceName) || text(change.type) || ""} · ${t(labels[String(change.status)] || String(change.status))}`}
      />
      {change.requiresIndependentReview === true && (
        <Alert
          type="warning"
          title={t(
            "需要另一位有财务管理权限的人员复核，提交者及最后编辑者不能自审。",
          )}
        />
      )}
      <DataTable
        data={[
          {
            ...metadata,
            ...summary,
            submittedBy: change.submittedBy,
            confirmedBy: change.confirmedBy,
            groupId: change.groupId,
            shopId: change.shopId,
            risk: validation.risk,
            changedCells: validation.changedCells,
            unchangedCells: validation.unchangedCells,
          },
        ]}
        columns={[
          ["sourceKey", "原始来源标识"],
          ["groupId", "分组"],
          ["shopId", "店铺"],
          ["submittedBy", "提交人"],
          ["confirmedBy", "确认人"],
          ["code", "目标型号"],
          ["warehouse", "目标成本仓"],
          ["unit", "实际计价单位"],
          ["identifierType", "编号类型"],
          ["spuUniform", "SPU 统一成本声明"],
          ["risk", "风险判断"],
          ["changedCells", "新增或变更单元"],
          ["unchangedCells", "未变单元"],
        ]}
      />
      <Alert
        type="warning"
        title={t("实际影响范围")}
        description={
          text(change.actualScope) ||
          text(scope.description) ||
          text(impact.actualScope) ||
          text(impact.description) ||
          t("以实际分组、店铺及历史范围为准")
        }
      />
      <DataTable
        data={[
          {
            ...impact,
            amountChange:
              impact.amountChange == null
                ? t("未核定；不能把未知影响当作零")
                : text(impact.amountChange),
          },
        ]}
        columns={[
          ["scopeShops", "涉及店铺"],
          ["scopeOrderCount", "范围内订单"],
          ["amountChange", "预计金额变化"],
        ]}
      />
      {impact.coverage === "SCOPE_UPPER_BOUND" && (
        <Alert
          type="warning"
          title={t(
            "订单数为可能涉及的范围上限；当前查询月份不会限制真实操作范围。",
          )}
        />
      )}
      <DataTable
        data={rows(validation.issues).slice(0, 100)}
        columns={[
          ["row", "源行"],
          ["sku", "型号"],
          ["warehouse", "成本仓"],
          ["month", "月份"],
          ["message", "校验说明"],
          ["code", "校验代码"],
          ["source", "数据来源"],
          ["action", "处理方式"],
        ]}
      />
      <DataTable
        data={diffs.slice(0, 100).map((d, i) => ({
          ...d,
          id: String(i),
          field: d.sku ?? d.field ?? d.code,
          oldValue: display(d.oldPrice ?? d.before),
          newValue: display(d.newPrice ?? d.after),
        }))}
        columns={[
          ["field", "型号 / 配置"],
          ["warehouse", "成本仓"],
          ["month", "月份"],
          ["oldValue", "原值"],
          ["newValue", "新值"],
          ["oldSource", "原来源"],
          ["newSource", "新来源"],
        ]}
      />
      <Field
        label="操作原因"
        value={reason}
        onChange={setReason}
        disabled={!editable}
      />
      <Field
        label="依据编号或说明"
        value={evidence}
        onChange={setEvidence}
        disabled={!editable}
      />
      <Feedback action={action} />
      {canConfirm && (
        <>
          <Check label="我已核对实际影响范围" value={ack} onChange={setAck} />
          {historical && (
            <Check
              label="我已确认会影响历史及后续订单"
              value={history}
              onChange={setHistory}
            />
          )}{" "}
          {needsRevision && (
            <Check
              label="明确修订已有价格"
              value={revision}
              onChange={setRevision}
            />
          )}
          <Button
            loading={action.pending}
            disabled={
              !ack ||
              (historical && !history) ||
              (needsRevision && !revision) ||
              !reason.trim() ||
              !evidence.trim()
            }
            onClick={() =>
              void submit("confirm", {
                reason: reason.trim(),
                evidenceRef: evidence.trim(),
                acknowledgeImpact: true,
                acknowledgeHistorical: history,
                revision,
              })
            }
          >
            {t(change.requiresIndependentReview ? "复核并应用" : "确认应用")}
          </Button>
        </>
      )}
      {editable && (
        <Space wrap>
          {change.canRevise === true &&
            !["APPLIED", "REJECTED"].includes(String(change.status)) && (
              <Button onClick={() => setMode("revise")}>{t("补充资料")}</Button>
            )}
          {change.canReject === true &&
            !["APPLIED", "REJECTED"].includes(String(change.status)) && (
              <Button
                disabled={!reason.trim() || action.pending}
                onClick={() => setMode("reject")}
              >
                {t("驳回")}
              </Button>
            )}
          {change.canUndo === true && change.status === "APPLIED" && (
            <Button
              onClick={() => {
                setReason("");
                setEvidence("");
                setMode("undo");
              }}
            >
              {t("撤销本次操作")}
            </Button>
          )}
        </Space>
      )}
      {mode === "revise" && (
        <>
          <Alert
            type="info"
            title={t(
              "补充后重新校验，旧预览失效。编号或价格错误请修正原表重新上传。",
            )}
          />
          <Evidence importing value={meta} onChange={setMeta} />
          <Button
            loading={action.pending}
            onClick={() =>
              void action.run(
                async () => {
                  requireEvidence(meta);
                  return api.send(
                    endpoint + "/" + id(change.id) + "/revise",
                    "POST",
                    { version: change.version, metadata: meta },
                  );
                },
                (v) => {
                  onClose();
                  onDone(v);
                },
              )
            }
          >
            {t("保存并重新校验")}
          </Button>
        </>
      )}
      {mode === "reject" && (
        <>
          <Alert
            type="warning"
            title={t("驳回只取消本次暂存，有效成本保留。")}
          />
          <Button
            loading={action.pending}
            onClick={() => void submit("reject", { reason: reason.trim() })}
          >
            {t("确认驳回")}
          </Button>
        </>
      )}
      {mode === "undo" && (
        <>
          <Alert
            type="warning"
            title={t(
              "撤销会生成反向核对单并重新检查当前配置，不会直接覆盖其他后续修改。",
            )}
          />
          <Button
            loading={action.pending}
            disabled={!reason.trim() || !evidence.trim()}
            onClick={() =>
              void submit("undo", {
                reason: reason.trim(),
                evidenceRef: evidence.trim(),
              })
            }
          >
            {t("生成撤销核对单")}
          </Button>
        </>
      )}
    </Space>
  );
}
