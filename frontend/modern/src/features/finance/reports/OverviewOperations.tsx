import { useState } from "react";
import { Button, Space } from "antd";
import { Download, SlidersHorizontal, Upload } from "lucide-react";
import type { Row } from "../../../shared/contracts";
import { rows } from "../../../shared/rows";
import { useFinance } from "../context";
import { responseScope } from "../domain";
import { useReport } from "../presentation";
import { ExportDialog } from "../operations/ExportDialog";
import { FieldSettings } from "../operations/FieldSettings";
import { ImportDialog } from "../operations/ImportDialog";

const exportViews = [
  { value: "ALL", label: "全部财务明细" },
  { value: "ORDER", label: "已结算订单" },
  { value: "REFUND", label: "平台退款明细" },
  { value: "SETTLEMENT", label: "结算明细" },
  { value: "BILL", label: "结算账单" },
  { value: "FEE", label: "平台费用" },
  { value: "AD", label: "广告费用（已入账）" },
  { value: "AD_GMV", label: "GMV Max 分析" },
  { value: "COST", label: "物流贴单成本" },
  { value: "PENDING", label: "待结算订单" },
  { value: "RECONCILIATION", label: "结算对账" },
] as const;

export function OverviewOperations({ data }: { data?: Row | undefined }) {
  const { filters, session, t, refresh } = useFinance();
  const metadata = useReport("/shops", {});
  const [dialog, setDialog] = useState("");
  const selection = {
    ...responseScope(filters, data),
    view: "ALL",
    orderScope: "SETTLED",
  };
  return (
    <>
      <Space wrap aria-label={t("财务总览操作")}>
        <Button
          icon={<SlidersHorizontal size={16} aria-hidden="true" />}
          onClick={() => setDialog("fields")}
        >
          {t("字段设置")}
        </Button>
        {session.canManage && (
          <Button
            icon={<Upload size={16} aria-hidden="true" />}
            onClick={() => setDialog("import")}
          >
            {t("导入账单")}
          </Button>
        )}
        <Button
          type="primary"
          icon={<Download size={16} aria-hidden="true" />}
          disabled={!data}
          onClick={() => setDialog("export")}
        >
          {t("导出财务报表")}
        </Button>
      </Space>
      <FieldSettings
        view="ALL"
        open={dialog === "fields"}
        onClose={() => setDialog("")}
        onSaved={() => setDialog("")}
      />
      <ImportDialog
        open={dialog === "import"}
        onClose={() => setDialog("")}
        shops={rows(metadata.data?.items)}
        onImported={() => {
          setDialog("");
          void refresh();
        }}
      />
      <ExportDialog
        open={dialog === "export"}
        onClose={() => setDialog("")}
        selection={selection}
        viewOptions={exportViews}
      />
    </>
  );
}
