import { describe, expect, it } from "vitest";
import {
  defaultFinanceFilters,
  changeFinanceFilters,
  exactNumbers,
  liveAverage,
  operatingRows,
  allocationPayload,
} from "../src/features/finance/domain";

describe("finance presentation contracts", () => {
  it("uses the previous Shanghai month at the UTC month boundary", () => {
    expect(
      defaultFinanceFilters(new Date("2026-10-31T16:00:00Z")),
    ).toMatchObject({
      start: "2026-10-01",
      end: "2026-10-31",
      currency: "",
      displayCurrency: "CNY",
      fxSnapshot: "",
    });
  });
  it("invalidates FX and resolved currency on scope changes; clearing original restores CNY", () => {
    const f = {
      ...defaultFinanceFilters(),
      currency: "USD",
      displayCurrency: "ORIGINAL",
      fxSnapshot: "frozen",
    };
    expect(changeFinanceFilters(f, { shopId: "shop-2" })).toMatchObject({
      shopId: "shop-2",
      currency: "",
      fxSnapshot: "",
    });
    expect(changeFinanceFilters(f, { currency: "" })).toMatchObject({
      displayCurrency: "CNY",
      fxSnapshot: "",
    });
  });
  it("normalizes exact identifiers without treating leading zeros as numbers", () => {
    expect(exactNumbers("001，002\n001; 三")).toBe("001,002,三");
    expect(() =>
      exactNumbers(Array.from({ length: 101 }, (_, i) => String(i)).join(",")),
    ).toThrow();
  });
  it("derives AOV only from a complete authoritative paid total and positive known count", () => {
    expect(liveAverage({ amount: 120, known_amounts: 3 })).toBe(40);
    expect(liveAverage({ amount: null, known_amounts: 3 })).toBeUndefined();
    expect(liveAverage({ amount: 0, known_amounts: 0 })).toBeUndefined();
  });
  it("groups complete server ranking facts and preserves missing sales revenue", () => {
    const data = {
      shops: [
        { id: "a", group_id: "g" },
        { id: "b", group_id: "g" },
      ],
      groups: [{ id: "g", name: "Group" }],
      ranking: [
        {
          shop_id: "a",
          currency: "CNY",
          kind: "ORDER",
          records: 2,
          amount: 30,
        },
        {
          shop_id: "b",
          currency: "CNY",
          kind: "ORDER",
          records: 1,
          amount: 20,
        },
        {
          shop_id: "a",
          currency: "CNY",
          kind: "SETTLEMENT",
          settled_gross_amount: 25,
        },
        {
          shop_id: "b",
          currency: "CNY",
          kind: "SETTLEMENT",
          settled_gross_amount: null,
        },
      ],
    };
    expect(operatingRows(data, true)[0]).toMatchObject({
      id: "g",
      order_count: 3,
      order_amount: 50,
      sales: null,
    });
  });
  it("requires every source-provided allocation component and prevents invented amounts", () => {
    const source = {
      platform: "SHOPEE",
      fingerprint: "fp",
      amount: 10,
      cash_amount: 8,
      credit_amount: 2,
    };
    const shops = [{ id: "s", platform: "SHOPEE" }];
    expect(
      allocationPayload(
        source,
        [{ shop_id: "s", amount: "10", cash_amount: "8", credit_amount: "2" }],
        shops,
        "org",
      ),
    ).toEqual({
      organizationId: "org",
      fingerprint: "fp",
      allocations: [
        { shop_id: "s", amount: "10", cash_amount: "8", credit_amount: "2" },
      ],
    });
    expect(() =>
      allocationPayload(
        source,
        [{ shop_id: "s", amount: "11", cash_amount: "8", credit_amount: "3" }],
        shops,
        "org",
      ),
    ).toThrow();
    expect(() =>
      allocationPayload(
        source,
        [
          {
            shop_id: "s",
            amount: "10",
            cash_amount: "8",
            credit_amount: "2",
            grant_amount: "0",
          },
        ],
        shops,
        "org",
      ),
    ).toThrow();
  });
});
it("keeps no ad records, unknown totals, partial zero and complete zero distinct", async () => {
  const { bookedAmount } = await import("../src/features/finance/domain");
  expect(
    bookedAmount({
      amount: 0,
      knownAmount: 0,
      records: 0,
      known_amounts: 0,
      hasRecords: false,
      complete: true,
    }),
  ).toBeUndefined();
  expect(
    bookedAmount({
      amount: null,
      knownAmount: 0,
      records: 2,
      known_amounts: 0,
      hasRecords: true,
      complete: false,
    }),
  ).toBeUndefined();
  expect(
    bookedAmount({
      amount: null,
      knownAmount: 0,
      records: 2,
      known_amounts: 1,
      hasRecords: true,
      complete: false,
    }),
  ).toBe(0);
  expect(
    bookedAmount({
      amount: 0,
      records: 2,
      known_amounts: 2,
      hasRecords: true,
      complete: true,
    }),
  ).toBe(0);
});
