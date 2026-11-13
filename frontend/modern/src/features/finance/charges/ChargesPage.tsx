import { useEffect, useState } from "react";
import { Tabs } from "antd";
import { useFinance } from "../context";
import { selectionKey } from "../domain";
import { LabelsPage } from "./LabelsPage";
import { BrushPage } from "./BrushPage";
export function ChargesPage({
  initialTab = "labels",
}: {
  initialTab?: string;
  route?: string;
}) {
  const { active, session } = useFinance();
  return active && session.canView ? (
    <Charges
      key={session.organizationId + session.userId}
      initialTab={initialTab}
    />
  ) : null;
}
function Charges({ initialTab }: { initialTab: string }) {
  const { t, filters } = useFinance();
  const [tab, setTab] = useState(initialTab);
  useEffect(() => setTab(initialTab), [initialTab]);
  const scope = selectionKey({ ...filters });
  return (
    <Tabs
      activeKey={tab}
      onChange={setTab}
      destroyOnHidden
      items={[
        {
          key: "labels",
          label: t("贴单成本"),
          children: <LabelsPage key={scope} />,
        },
        {
          key: "brush",
          label: t("刷单成本"),
          children: <BrushPage key={scope} />,
        },
      ]}
    />
  );
}
