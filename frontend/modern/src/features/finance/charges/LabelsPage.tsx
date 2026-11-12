import { useState } from "react";
import { Alert, Button, Space } from "antd";
import { Panel, MetricCard } from "../../../shared/components";
import { amountText, record, rows, text } from "../../../shared/rows";
import type { Row } from "../../../shared/contracts";
import { useFinance } from "../context";
import { Failure } from "../presentation";
import {
  Choice,
  DataTable,
  Feedback,
  Pages,
  SearchNumbers,
  useCostAction,
  useCostRead,
} from "../costs/common";
import { ConfirmOperation } from "../costs/ConfirmOperation";
import { id } from "../costs/domain";
const path = "/workspace/label-costs";
export function LabelsPage() {
  const { filters, api, session, t } = useFinance();
  const [page, setPage] = useState(1),
    [numbers, setNumbers] = useState(""),
    [fileId, setFileId] = useState(""),
    [matchStatus, setMatchStatus] = useState(""),
    [files, setFiles] = useState<File[]>([]),
    [removing, setRemoving] = useState<Row>();
  const action = useCostAction();
  const query = useCostRead(
    path,
    {
      start: filters.start,
      end: filters.end,
      groupId: filters.groupId,
      shopId: filters.shopId,
      platform: filters.platform,
      fileId,
      matchStatus,
      numbers,
      page,
    },
    (data, error) =>
      error
        ? 10000
        : data?.processing === true ||
            rows(data?.files).some((f) =>
              ["QUEUED", "PARSING", "PREPARED", "REMOVE_QUEUED"].includes(
                text(f.status) || "",
              ),
            )
          ? 3000
          : 0,
  );
  const data = query.data;
  const processing =
    data?.processing === true ||
    rows(data?.files).some((f) =>
      ["QUEUED", "PARSING", "PREPARED", "REMOVE_QUEUED"].includes(
        text(f.status) || "",
      ),
    );
  const period = record(data?.period) || {},
    state = record(data?.state) || {};
  async function upload() {
    let accepted = 0,
      duplicate = 0;
    await action.run(async () => {
      if (!files.length) throw Error("请选择月度 XLSX 账单");
      for (const file of files) {
        if (!/\.xlsx$/i.test(file.name) || file.size > 96 * 1024 * 1024)
          throw Error("仅支持不超过 96 MB 的 XLSX 账单");
      }
      try {
        for (const file of files) {
          if (!action.valid()) return;
          const form = new FormData();
          form.append("file", file);
          const receipt = await api.upload<Row>(path + "/import", form);
          if (receipt.duplicate) duplicate++;
          else accepted++;
        }
      } catch (error) {
        throw Error(
          `已接收 ${accepted} 份，重复 ${duplicate} 份；${error instanceof Error ? error.message : "上传失败"}`,
        );
      } finally {
        if (action.valid()) void query.refetch();
      }
      return `已接收 ${accepted} 份，重复 ${duplicate} 份；后台完成后统一发布`;
    });
  }
  return (
    <Panel title={t("月度贴单账单")}>
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Alert
          type="info"
          title={t(
            "公司级月度仓库来源，按订单首次结算日期统计；刷单排除。上传及移除后等待后台统一更新。",
          )}
        />
        <Space wrap>
          <MetricCard
            label={t("期间贴单成本")}
            value={amountText(period.amount_cny, "CNY")}
          />
          <MetricCard
            label={t("期间订单")}
            value={text(period.orders) ?? "—"}
          />
          <MetricCard
            label={t("冲突数量")}
            value={text(period.conflicts) ?? "—"}
          />
        </Space>
        {processing && (
          <Alert type="info" title={t("后台处理中，当前显示已发布结果")} />
        )}
        <Failure error={query.error} />
        <Failure
          error={
            state.error_message ? Error(String(state.error_message)) : undefined
          }
        />
        <Feedback action={action} />
        {session.canManage && (
          <Space wrap>
            <label>
              {t("选择月度账单")}
              <input
                type="file"
                accept=".xlsx"
                multiple
                aria-label={t("选择月度账单")}
                disabled={action.pending}
                onChange={(e) => setFiles(Array.from(e.target.files || []))}
              />
            </label>
            <Button loading={action.pending} onClick={() => void upload()}>
              {t("上传月度账单")}
            </Button>
            <Button
              loading={action.pending}
              onClick={() =>
                void action.run(
                  () => api.send(path + "/rematch", "POST"),
                  () => query.refetch().then(() => {}),
                )
              }
            >
              {t("重新匹配全部账单")}
            </Button>
          </Space>
        )}
        <DataTable
          data={data?.files}
          columns={[
            ["source_name", "来源文件"],
            ["status", "后台状态"],
            ["row_count", "来源行数"],
            ["matched_count", "已匹配"],
            ["unmatched_count", "未匹配"],
            ["review_count", "待核对"],
            ["conflict_count", "冲突"],
            ["amount_cny", "已确认金额", "money"],
            ["error_message", "处理错误"],
          ]}
          actions={(f) => (
            <Space wrap>
              <Button
                onClick={() => {
                  setFileId(text(f.id) || "");
                  setPage(1);
                }}
              >
                {t("查看来源明细")}
              </Button>
              {session.canManage && (
                <>
                  <Button
                    disabled={
                      action.pending ||
                      ["PARSING", "PREPARED", "REMOVE_QUEUED"].includes(
                        text(f.status) || "",
                      )
                    }
                    onClick={() => setRemoving(f)}
                  >
                    {t("移除月度账单")}
                  </Button>
                  {f.status === "FAILED" && (
                    <Button
                      disabled={
                        action.pending ||
                        f.canRetry === false ||
                        f.fileAvailable === false ||
                        f.storageExists === false
                      }
                      onClick={() =>
                        void action.run(
                          () =>
                            api.send(
                              path + "/imports/" + id(f.id) + "/retry",
                              "POST",
                            ),
                          () => query.refetch().then(() => {}),
                        )
                      }
                    >
                      {t("重试账单处理")}
                    </Button>
                  )}
                </>
              )}
            </Space>
          )}
        />
        <Space wrap>
          <Choice
            label="来源文件"
            value={fileId}
            onChange={(v) => {
              setFileId(v);
              setPage(1);
            }}
            options={[
              { value: "", label: "全部文件" },
              ...rows(data?.files).map((f) => ({
                value: text(f.id) || "",
                label: text(f.source_name) || text(f.id) || "",
              })),
            ]}
          />
          <Choice
            label="贴单匹配状态"
            value={matchStatus}
            onChange={(v) => {
              setMatchStatus(v);
              setPage(1);
            }}
            options={["", "MATCHED", "UNMATCHED", "REVIEW", "CONFLICT"]}
          />
          <SearchNumbers
            onSearch={(v) => {
              setNumbers(v);
              setPage(1);
            }}
          />
          <Button onClick={() => void query.refetch()}>
            {t("刷新贴单账单")}
          </Button>
        </Space>
        <DataTable
          data={data?.rows}
          columns={[
            ["order_id", "订单编号"],
            ["shop_name", "店铺"],
            ["source_name", "来源文件"],
            ["source_row", "来源行"],
            ["warehouse", "成本仓"],
            ["document_no", "参考号"],
            ["transaction_date", "账单日期"],
            ["fee_type", "费用类型"],
            ["amount_cny", "人民币金额", "money"],
            ["unit_price_cny", "单价", "money"],
            ["billed_quantity", "计费数量"],
            ["billing_status", "计费状态"],
            ["match_status", "匹配状态"],
            ["match_note", "核对说明"],
          ]}
        />
        <Pages data={data} page={page} onChange={setPage} />
      </Space>
      {removing && (
        <ConfirmOperation
          title="移除月度账单"
          description="只移除此来源，其他账单、商品成本和手动成本保留；金额待后台完成后统一更新。"
          onClose={() => setRemoving(undefined)}
          operation={() =>
            api.send(path + "/imports/" + id(removing.id), "DELETE")
          }
          onDone={(value) => {
            action.setNotice(text(record(value)?.status) || t("移除已排队"));
            void query.refetch();
          }}
        />
      )}
    </Panel>
  );
}
