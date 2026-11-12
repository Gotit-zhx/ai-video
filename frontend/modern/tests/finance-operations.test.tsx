import { fireEvent, waitFor, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import type { FinanceApi } from "../src/shared/contracts";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance,
} from "./fixtures";
import { ImportDialog } from "../src/features/finance/operations";
import { RecordDetail } from "../src/features/finance/records/RecordDetail";
it("funding import previews then commits exact identity and never fabricates component mappings", async () => {
  const upload = vi.fn(async (path: string, data: FormData) =>
    data.get("preview") === "true"
      ? {
          rows: 2,
          sample: [{ record_date: "2026-10-01", currency: "USD", amount: 12 }],
          totals: [{ currency: "USD", amount: 12 }],
        }
      : { rows: 2, inserted: 1, duplicateRows: 1 },
  );
  const v = renderFinance(
    <ImportDialog open funding shops={[]} onClose={() => {}} />,
    {
      api: createFinanceApiFixture({ upload: upload as FinanceApi["upload"] }),
    },
  );
  const overlay = within(v.surface.overlayContainer);
  fireEvent.change(overlay.getByRole("textbox", { name: "真实平台账户 ID" }), {
    target: { value: "123456" },
  });
  fireEvent.change(overlay.getByRole("textbox", { name: "账户名称" }), {
    target: { value: "Our source" },
  });
  const file = new File(["date,amount\n2026-10-01,12"], "funding.csv", {
    type: "text/csv",
  });
  fireEvent.change(
    v.surface.overlayContainer.querySelector("input[type=file]")!,
    { target: { files: [file] } },
  );
  fireEvent.click(overlay.getByRole("button", { name: "校验并预览" }));
  await overlay.findByText("预览行数: 2");
  fireEvent.click(overlay.getByRole("button", { name: "确认导入" }));
  await overlay.findByText("导入已完成");
  expect(upload).toHaveBeenCalledTimes(2);
  const form = upload.mock.calls[1]?.[1];
  expect(form?.get("organizationId")).toBe("org-1");
  expect(form?.get("accountId")).toBe("123456");
  expect(form?.get("preview")).toBe("false");
  expect(form?.get("file")).toBe(file);
  expect(form?.get("mapping")).toBeNull();
});
it("viewer cannot open generic or funding management imports", () => {
  const v = renderFinance(
    <ImportDialog open funding shops={[]} onClose={() => {}} />,
    {
      host: createFinanceHostFixture({ ...defaultSession, canManage: false })
        .host,
    },
  );
  expect(v.surface.overlayContainer).toBeEmptyDOMElement();
});
it("details display catalog allowlist and business dates plus separate SKU and settlement evidence", async () => {
  const get: FinanceApi["get"] = async <T,>(path: string) =>
    path.startsWith("/fields")
      ? ({
          fields: [
            { id: "external_id", label: "编号", type: "text" },
            { id: "record_date", label: "业务日期", type: "date" },
            { id: "amount", label: "销售收入", type: "money" },
          ],
        } as T)
      : ({
          kind: "SETTLEMENT",
          external_id: "BILL-001",
          record_date: "2026-10-31",
          amount: 0,
          raw: { secret: "SHOULD-NOT-RENDER" },
          product_sku_details: [
            { name: "Product", sellerSku: "SKU-9", quantity: 2 },
          ],
          settlement_fee_breakdown: [
            {
              label: "Commission",
              group: "Fee",
              amount: 1,
              field: "commission_fee",
            },
          ],
          productCost: {
            note: "Historical evidence",
            lines: [
              { costCode: "C-1", quantity: 2, unitPriceCny: 3, amountCny: 6 },
            ],
          },
        } as T);
  const v = renderFinance(
    <RecordDetail
      recordId="r"
      selection={{ fxSnapshot: "fixed" }}
      onClose={() => {}}
    />,
    { api: createFinanceApiFixture({ get }) },
  );
  const overlay = within(v.surface.overlayContainer);
  await overlay.findByText("BILL-001");
  expect(overlay.getByText("2026-10-31")).toBeInTheDocument();
  expect(overlay.getByText("0.00")).toBeInTheDocument();
  expect(overlay.getByText("Commission")).toBeInTheDocument();
  expect(overlay.queryByText("SHOULD-NOT-RENDER")).not.toBeInTheDocument();
  expect(overlay.getByText("C-1")).toBeInTheDocument();
});
it("generic import sends a reviewed field mapping and surfaces duplicate feedback", async () => {
  const upload = vi.fn(async (path: string) =>
    path.endsWith("/preview")
      ? {
          columns: ["external_id", "date", "currency", "amount"],
          sample: [
            {
              external_id: "A",
              date: "2026-10-01",
              currency: "USD",
              amount: 9,
            },
          ],
          rows: 1,
        }
      : { duplicate: true, rows: 1 },
  );
  const v = renderFinance(
    <ImportDialog
      open
      shops={[{ id: "shop-a", name: "Store A" }]}
      kind="BILL"
      onClose={() => {}}
    />,
    {
      api: createFinanceApiFixture({ upload: upload as FinanceApi["upload"] }),
    },
  );
  const overlay = within(v.surface.overlayContainer);
  const file = new File(
    ["id,date,currency,amount\nA,2026-10-01,USD,9"],
    "bills.csv",
  );
  fireEvent.change(
    v.surface.overlayContainer.querySelector("input[type=file]")!,
    { target: { files: [file] } },
  );
  fireEvent.click(overlay.getByRole("button", { name: "校验并预览" }));
  await overlay.findByText("预览行数: 1");
  for (const id of ["external_id", "date", "currency", "amount"]) {
    fireEvent.mouseDown(overlay.getByRole("combobox", { name: id }));
    const dropdown = Array.from(
      v.surface.overlayContainer.querySelectorAll(".ant-select-dropdown"),
    ).at(-1)!;
    fireEvent.click(
      dropdown.querySelector('.ant-select-item-option[title="' + id + '"]')!,
    );
  }
  fireEvent.click(overlay.getByRole("button", { name: "确认导入" }));
  await waitFor(() => {
    if (upload.mock.calls.length < 2)
      throw new Error(
        overlay
          .getAllByRole("alert")
          .map((n) => n.textContent)
          .join("|"),
      );
  });
  await overlay.findByText("导入已完成");
  const form = (upload.mock.calls[1] as unknown as [string, FormData])[1];
  expect(form.get("mapping")).toBe(
    '{"external_id":"external_id","date":"date","currency":"currency","amount":"amount"}',
  );
  expect(form.get("shopId")).toBe("shop-a");
  expect(form.get("kind")).toBe("BILL");
  expect(overlay.getByText(/重复.*true/)).toBeInTheDocument();
});
