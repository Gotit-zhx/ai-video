import { useEffect, useRef, useState } from "react";
import { Alert, Button, Space } from "antd";
import type { FinanceRequestParams, Row } from "../../../shared/contracts";
import { FinanceApiError } from "../../../shared/api";
import { rows, text } from "../../../shared/rows";
import { BusinessTable, StatusTag } from "../../../shared/components";
import { FinanceDrawer } from "../../../shared/dialogs";
import { useFinance } from "../context";
import { Failure, useOwnerFence, useVisible } from "../presentation";
export interface ExportJobsProps {
  kind: "fee" | "cost";
  open: boolean;
  selection: FinanceRequestParams;
  onClose: () => void;
}
interface PendingRequest {
  body: FinanceRequestParams;
  key: string;
}
export function ExportJobs(props: ExportJobsProps) {
  const { session, active } = useFinance();
  return props.open && active ? (
    <JobSession
      key={session.organizationId + ":" + session.userId + ":" + props.kind}
      {...props}
    />
  ) : null;
}
function JobSession({ kind, selection, onClose }: ExportJobsProps) {
  const { api, t } = useFinance(),
    owner = useOwnerFence(),
    visible = useVisible();
  const endpoint = "/" + kind + "-exports";
  const [snapshot] = useState(() => ({ ...selection })),
    [jobs, setJobs] = useState<Row[]>([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(),
    [uncertain, setUncertain] = useState<PendingRequest>(),
    [notice, setNotice] = useState("");
  const revision = useRef(0),
    alive = useRef(true),
    readController = useRef<AbortController | null>(null),
    downloads = useRef(new Set<AbortController>());
  useEffect(
    () => () => {
      alive.current = false;
      readController.current?.abort();
      downloads.current.forEach((controller) => controller.abort());
    },
    [],
  );
  const valid = () => alive.current && owner.isCurrent();
  const load = async () => {
    const generation = revision.current;
    readController.current?.abort();
    const controller = new AbortController();
    readController.current = controller;
    try {
      const result = await api.get<unknown>(endpoint, {}, controller.signal);
      if (valid() && generation === revision.current) {
        setJobs(rows(result));
        setError(undefined);
      }
    } catch (e) {
      if (valid() && !controller.signal.aborted) setError(e);
    }
  };
  useEffect(() => {
    void load();
  }, []);
  useEffect(() => {
    if (
      !visible ||
      !jobs.some((j) => ["QUEUED", "RUNNING"].includes(text(j.status) || ""))
    )
      return;
    const timer = setInterval(() => void load(), 3000);
    return () => clearInterval(timer);
  }, [visible, jobs]);
  const submit = async (saved?: PendingRequest) => {
    if (busy || !valid()) return;
    const fields =
      kind === "fee"
        ? ["start", "end", "shopId", "groupId", "platform", "currency"]
        : [
            "start",
            "end",
            "shopId",
            "groupId",
            "platform",
            "currency",
            "kind",
            "recordId",
            "costStatus",
            "numbers",
          ];
    const request = saved || {
      body: {
        ...Object.fromEntries(fields.map((key) => [key, snapshot[key] ?? ""])),
        ...(kind === "fee" ? { displayCurrency: "ORIGINAL" } : {}),
      },
      key: crypto.randomUUID(),
    };
    setBusy(true);
    setError(undefined);
    revision.current++;
    try {
      const job = await api.send<Row>(endpoint, "POST", request.body, {
        headers: { "Idempotency-Key": request.key },
      });
      if (valid()) {
        revision.current++;
        setJobs((current) => [
          job,
          ...current.filter((item) => item.id !== job.id),
        ]);
        setUncertain(undefined);
      }
    } catch (e) {
      if (valid()) {
        setError(e);
        if (!(e instanceof FinanceApiError) || e.status >= 500)
          setUncertain(request);
      }
    } finally {
      if (valid()) setBusy(false);
    }
  };
  const download = async (job: Row) => {
    const controller = new AbortController();
    downloads.current.add(controller);
    try {
      await api.download(
        endpoint + "/" + encodeURIComponent(text(job.id) || "") + "/download",
        {},
        text(job.filename),
        { signal: controller.signal, timeoutMs: 180000 },
      );
      if (valid()) setNotice(t("已请求浏览器保存文件"));
    } catch (e) {
      if (valid()) setError(e);
    } finally {
      downloads.current.delete(controller);
    }
  };
  return (
    <FinanceDrawer
      open
      title={t(kind === "fee" ? "费用矩阵导出任务" : "成本导出任务")}
      onClose={onClose}
      size="large"
    >
      <Space wrap>
        <Button
          type="primary"
          disabled={!!uncertain}
          loading={busy}
          onClick={() => void submit()}
        >
          {t("创建导出任务")}
        </Button>
        <Button onClick={() => void load()}>{t("刷新任务状态")}</Button>
      </Space>
      <Alert
        type="info"
        title={t("仅显示本公司本人任务；文件保留 30 分钟，离开面板停止轮询。")}
        description={t(
          kind === "fee"
            ? "费用矩阵按原币分表，费用为行、店铺为列。"
            : "任务完成后可下载 Excel 或 ZIP。服务重启可能清除临时任务。",
        )}
      />
      <Failure error={error} />
      {uncertain && (
        <Alert
          type="warning"
          title={t("上次提交结果待确认，不会自动重复创建")}
          action={
            <Button loading={busy} onClick={() => void submit(uncertain)}>
              {t("确认使用同一请求重试")}
            </Button>
          }
        />
      )}
      <BusinessTable<Row>
        rowKey={(r) => text(r.id) || ""}
        dataSource={jobs}
        pagination={false}
        columns={[
          {
            title: t("文件"),
            dataIndex: "filename",
            render: (v: unknown) => text(v) || "—",
          },
          {
            title: t("状态"),
            dataIndex: "status",
            render: (v: unknown) => (
              <StatusTag>{t(text(v) || "未知")}</StatusTag>
            ),
          },
          {
            title: t("进度"),
            render: (_, r) =>
              r.total ? `${text(r.processed) || "0"} / ${text(r.total)}` : "—",
          },
          {
            title: t("错误"),
            dataIndex: "error",
            render: (v: unknown) => text(v) || "—",
          },
          {
            title: t("操作"),
            render: (_, r) => (
              <Button
                disabled={r.status !== "READY"}
                onClick={() => void download(r)}
              >
                {t("下载")}
              </Button>
            ),
          },
        ]}
      />
      {notice && <Alert type="success" title={notice} />}
    </FinanceDrawer>
  );
}
