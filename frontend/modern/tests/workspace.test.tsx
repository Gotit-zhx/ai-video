import { act, fireEvent, waitFor } from "@testing-library/react";
import { expect, it } from "vitest";
import {
  FinanceApplication,
  normalizeFinanceRoute,
} from "../src/features/finance/FinanceApplication";
import { useFinance } from "../src/features/finance/context";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance,
} from "./fixtures";
function Probe() {
  const { filters } = useFinance();
  return <output>{JSON.stringify(filters)}</output>;
}
it("preserves fees aliases and routes expense ledger into the charges extension", () => {
  expect(normalizeFinanceRoute("#finance-fees")).toBe("fee-records");
  expect(normalizeFinanceRoute("/expense-ledger")).toBe("expense-ledger");
  const v = renderFinance(
    <FinanceApplication pages={{ charges: () => <p>Charges extension</p> }} />,
    { route: "expense-ledger", api: createFinanceApiFixture() },
  );
  expect(v.getByText("Charges extension")).toBeInTheDocument();
  expect(v.getByRole("navigation").querySelectorAll("button")).toHaveLength(9);
  expect(v.getByRole("link", { name: "返回旧版" })).toHaveAttribute(
    "href",
    "?financeUi=legacy#finance-overview",
  );
});
it("normalizes scope edits and restores safe defaults on owner change", async () => {
  const host = createFinanceHostFixture();
  const v = renderFinance(<FinanceApplication pages={{ shops: Probe }} />, {
    host: host.host,
    route: "shops",
    filters: {
      start: "2026-01-01",
      end: "2026-01-31",
      currency: "USD",
      displayCurrency: "ORIGINAL",
      fxSnapshot: "old",
    },
  });
  fireEvent.change(v.getByLabelText("开始日期"), {
    target: { value: "2026-02-01" },
  });
  expect(v.getByRole("status").textContent).not.toContain('"fxSnapshot":"old"');
  act(() => host.update({ ...defaultSession, organizationId: "org-2" }));
  await waitFor(() =>
    expect(v.getByRole("status").textContent).toContain(
      '"displayCurrency":"CNY"',
    ),
  );
});
