import { fireEvent, waitFor, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import type {
  FinanceApi,
  FinanceRequestParams,
  Row,
} from "../src/shared/contracts";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  renderFinance,
} from "./fixtures";
import { FinanceApplication } from "../src/features/finance/FinanceApplication";
import { useFinance } from "../src/features/finance/context";
import { ReportsPage } from "../src/features/finance/reports/ReportsPage";
import { RecordsPage } from "../src/features/finance/records/RecordsPage";
import { RecordDetail } from "../src/features/finance/records/RecordDetail";
import { operatingRows } from "../src/features/finance/domain";
const apiRead =
  (
    read: (path: string, params?: FinanceRequestParams) => unknown,
  ): FinanceApi["get"] =>
  async <T,>(path: string, params?: FinanceRequestParams) =>
    read(path, params) as T;
const shops = [
  {
    id: "a",
    name: "Alpha",
    group_id: "g",
    platform: "TIKTOK_SHOP",
    currency: "USD",
  },
  {
    id: "b",
    name: "Beta",
    group_id: "other",
    platform: "TIKTOK_SHOP",
    currency: "THB",
  },
];
function FilterProbe() {
  return (
    <output aria-label="selection">
      {JSON.stringify(useFinance().filters)}
    </output>
  );
}
async function choose(
  container: HTMLElement,
  overlays: HTMLElement,
  label: string,
  option: string,
) {
  const input = within(container).getByRole("combobox", { name: label });
  fireEvent.mouseDown(input);
  const list = overlays.querySelector(
    '[id="' + input.getAttribute("aria-controls") + '"]',
  )!;
  const dropdown = list.closest(".ant-select-dropdown")!;
  fireEvent.click(
    (await within(dropdown as HTMLElement).findAllByText(option)).at(-1)!,
  );
}
it("I1 independently controls filtered CNY and original mode after scope changes", async () => {
  const v = renderFinance(
    <FinanceApplication pages={{ shops: FilterProbe }} />,
    {
      route: "shops",
      api: createFinanceApiFixture({
        get: apiRead(() => ({
          items: shops,
          groups: [{ id: "g", name: "Group" }],
        })),
      }),
    },
  );
  await waitFor(() =>
    expect(v.getByRole("combobox", { name: "原币筛选" })).toBeInTheDocument(),
  );
  await choose(v.container, v.surface.overlayContainer, "原币筛选", "USD");
  expect(v.getByLabelText("selection")).toHaveTextContent('"currency":"USD"');
  expect(v.getByLabelText("selection")).toHaveTextContent(
    '"displayCurrency":"CNY"',
  );
  await choose(v.container, v.surface.overlayContainer, "金额展示币种", "原币");
  await choose(v.container, v.surface.overlayContainer, "原币筛选", "USD");
  await choose(v.container, v.surface.overlayContainer, "平台", "TikTok Shop");
  expect(v.getByLabelText("selection")).toHaveTextContent('"currency":""');
  expect(v.getByLabelText("selection")).toHaveTextContent(
    '"displayCurrency":"ORIGINAL"',
  );
  expect(v.getByText("自动选择原币")).toBeInTheDocument();
});
it("I1 all-native advertising emits ORIGINAL with no native restriction", async () => {
  const read = vi.fn((path: string) =>
    path === "/shops"
      ? { items: shops }
      : path === "/advertising/spend"
        ? { items: [], totals: [] }
        : {},
  );
  const v = renderFinance(<FinanceApplication />, {
    route: "ads",
    api: createFinanceApiFixture({ get: apiRead(read) }),
  });
  await choose(v.container, v.surface.overlayContainer, "金额展示币种", "原币");
  await waitFor(() =>
    expect(read).toHaveBeenCalledWith(
      "/advertising/spend",
      expect.objectContaining({ displayCurrency: "ORIGINAL", currency: "" }),
    ),
  );
  expect(v.getByText("全部原币（分币种展示）")).toBeInTheDocument();
});
it("I2 sums available order and fee facts while incomplete settled gross stays unknown", () => {
  const facts = {
    shops: [
      { id: "a", group_id: "g" },
      { id: "b", group_id: "g" },
    ],
    ranking: [
      { shop_id: "a", currency: "CNY", kind: "ORDER", records: 2, amount: 30 },
      {
        shop_id: "b",
        currency: "CNY",
        kind: "ORDER",
        records: null,
        amount: null,
      },
      { shop_id: "a", currency: "CNY", kind: "FEE", amount: 7 },
      { shop_id: "b", currency: "CNY", kind: "FEE", amount: null },
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
  expect(operatingRows(facts, true)[0]).toMatchObject({
    order_count: 2,
    order_amount: 30,
    fees: 7,
    sales: null,
  });
});
it("I3 attaches partial and historical-estimate metadata to table money and profit", async () => {
  const data = {
    filters: { displayCurrency: "CNY" },
    shops: [shops[0]],
    groups: [{ id: "g", name: "Group" }],
    ranking: [
      { shop_id: "a", currency: "CNY", kind: "ORDER", records: 2, amount: 30 },
    ],
    profit: { amount: 18, estimated: true },
    advertising: {
      shops: [
        {
          shop_id: "a",
          currency: "CNY",
          records: 2,
          known_amounts: 1,
          amount: null,
          knownAmount: 0,
          complete: false,
        },
      ],
      groups: [
        {
          group_id: "g",
          currency: "CNY",
          records: 2,
          known_amounts: 1,
          amount: null,
          knownAmount: 0,
          complete: false,
        },
      ],
    },
    productCosts: {
      shops: [
        {
          shop_id: "a",
          pending_count: 1,
          known_amount_cny: 50,
          amount_cny: null,
          estimated_count: 1,
          label_cost_cny: 0,
        },
      ],
    },
  };
  const v = renderFinance(<ReportsPage view="overview" />, {
    api: createFinanceApiFixture({
      get: apiRead((path) => (path === "/overview" ? data : {})),
    }),
  });
  await v.findByText("CNY 18.00");
  expect(v.getByRole("button", { name: /利润额/ })).toHaveTextContent("暂估");
  const row = v
    .getAllByRole("row")
    .find((row) => row.textContent?.includes("Alpha"))!;
  expect(row).toHaveTextContent("已知部分");
  expect(row).toHaveTextContent("已算部分");
  expect(row).toHaveTextContent("历史价格暂估");
});
const fxCatalog = {
  fields: [
    { id: "external_id", label: "编号", type: "text" },
    { id: "fx_time", label: "汇率快照创建时间", type: "datetime", cny: true },
    { id: "fx_provider", label: "汇率来源", type: "text", cny: true },
    { id: "fx_business_date", label: "换算业务日期", type: "date", cny: true },
    { id: "fx_rate_date", label: "汇率公布日期", type: "date", cny: true },
  ],
  visible: [
    "external_id",
    "fx_time",
    "fx_provider",
    "fx_business_date",
    "fx_rate_date",
  ],
};
const fxRecord = {
  id: "fx-row",
  kind: "SETTLEMENT",
  external_id: "FX-1",
  fx: {
    id: "quote",
    time: "2026-10-04T12:00:00Z",
    provider: "Historical Bank",
    businessDate: "2026-10-01",
    rateDate: "2026-09-30",
  },
};
it("I6 grid and detail resolve allowlisted row.fx dates and provider", async () => {
  const get = apiRead((path) =>
    path === "/overview"
      ? { fx: { id: "quote" } }
      : path.startsWith("/fields")
        ? fxCatalog
        : path.startsWith("/records/")
          ? fxRecord
          : { items: [fxRecord], total: 1 },
  );
  const v = renderFinance(<RecordsPage view="SETTLEMENT" />, {
    api: createFinanceApiFixture({ get }),
  });
  await v.findByText("Historical Bank");
  expect(v.getByText("2026-10-01")).toBeInTheDocument();
  expect(v.getByText("2026-09-30")).toBeInTheDocument();
  fireEvent.click(v.getByRole("button", { name: /详\s*情/ }));
  const overlay = within(v.surface.overlayContainer);
  await overlay.findByText("FX-1");
  expect(overlay.getAllByText("Historical Bank").length).toBeGreaterThan(0);
  expect(overlay.getByText("2026-09-30")).toBeInTheDocument();
  expect(overlay.getByText(/2026-10-04 12:00:00/)).toBeInTheDocument();
});
it("I7 renders a single instant under explicit UTC Beijing and shop bases while dates stay unchanged", async () => {
  const stamp = "2026-10-01T12:34:56Z";
  const fields = [
    { id: "create_time", label: "平台创建时间（UTC）", type: "datetime" },
    {
      id: "payment_time_beijing",
      label: "平台付款时间（北京时间）",
      type: "datetime",
    },
    {
      id: "order_created_time",
      label: "下单时间（店铺当地）",
      type: "datetime",
    },
    { id: "record_date", label: "业务日期", type: "date" },
  ];
  const get = apiRead((path) =>
    path.startsWith("/fields")
      ? { fields }
      : {
          kind: "ORDER",
          order_timezone: "Asia/Bangkok",
          create_time: stamp,
          payment_time_beijing: stamp,
          order_created_time: stamp,
          record_date: "2026-09-30",
        },
  );
  const v = renderFinance(
    <RecordDetail recordId="time" selection={{}} onClose={() => {}} />,
    { api: createFinanceApiFixture({ get }) },
  );
  const overlay = within(v.surface.overlayContainer);
  expect(await overlay.findByText(/2026-10-01 12:34:56/)).toBeInTheDocument();
  expect(overlay.getByText(/2026-10-01 20:34:56/)).toBeInTheDocument();
  expect(overlay.getByText(/2026-10-01 19:34:56/)).toBeInTheDocument();
  expect(overlay.getByText("2026-09-30")).toBeInTheDocument();
});
it("I8 retains available money with cache timestamp and settlement progress visible", async () => {
  const data = {
    totals: [],
    profit: { settledGrossAmount: 90 },
    cache: { refreshing: true, generatedAt: "2026-10-01T01:02:03Z" },
    settlementCalculation: {
      status: "RUNNING",
      scope: "October settlement",
      processed: 4,
      total: 12,
    },
  };
  const v = renderFinance(<ReportsPage view="overview" />, {
    api: createFinanceApiFixture({
      get: apiRead((path) => (path === "/overview" ? data : {})),
    }),
  });
  await v.findByText("CNY 90.00");
  expect(v.getByText("正在后台更新，当前显示已有快照")).toBeInTheDocument();
  expect(v.getByText(/2026-10-01 09:02:03/)).toBeInTheDocument();
  expect(v.getByText("结算金额计算中")).toBeInTheDocument();
  expect(v.getByText(/4 \/ 12/)).toBeInTheDocument();
  expect(v.getByText("October settlement")).toBeInTheDocument();
});
it("I4 booked group/shop sections retain source breakdowns and authoritative GMV metrics", async () => {
  const part = (amount: number) => ({
    amount,
    currency: "CNY",
    records: 1,
    known_amounts: 1,
    complete: true,
    hasRecords: true,
  });
  const summary = {
    ...part(15),
    settlement: part(10),
    commission: part(2),
    independent: part(5),
    gmv: {
      ...part(7),
      revenue: 140,
      known_revenues: 1,
      conversions: 4,
      known_conversions: 1,
      roi: 20,
    },
  };
  const read = vi.fn((path: string) =>
    path === "/advertising/spend"
      ? { totals: [], items: [] }
      : path === "/overview"
        ? {
            filters: { displayCurrency: "CNY" },
            advertising: {
              total: part(15),
              gmv: summary.gmv,
              groups: [{ ...summary, group_id: "g" }],
              shops: [{ ...summary, shop_id: "a", group_id: "g" }],
            },
          }
        : path === "/shops"
          ? { items: shops, groups: [{ id: "g", name: "Group" }] }
          : path.startsWith("/fields")
            ? { fields: [], visible: [] }
            : path === "/records"
              ? { items: [], total: 0 }
              : [],
  );
  const host = createFinanceHostFixture();
  const v = renderFinance(<ReportsPage view="ads" initialAdTab="booked" />, {
    host: host.host,
    api: createFinanceApiFixture({ get: apiRead(read) }),
  });
  await v.findByText("分组广告费用（已入账）");
  expect(v.getByText("店铺广告费用（已入账）")).toBeInTheDocument();
  expect(v.getByText("GMV Max 专项分析")).toBeInTheDocument();
  const gmvTable = within(
    v.getByRole("region", { name: "GMV Max 专项分析" }),
  ).getByRole("table");
  await waitFor(() => expect(gmvTable).toHaveTextContent("CNY 140.00"));
  expect(gmvTable).toHaveTextContent("20");
  expect(gmvTable).toHaveTextContent("4");
  const table = within(
    v.getByRole("region", { name: "店铺广告费用（已入账）" }),
  ).getByRole("table");
  const alpha = within(table).getByRole("row", { name: /Alpha/ });
  expect(alpha).toHaveTextContent("10.00");
  expect(alpha).toHaveTextContent("5.00");
  expect(alpha).toHaveTextContent("7.00");
  expect(within(table).getByRole("row", { name: /Beta/ })).toHaveTextContent(
    "尚无可归属的接口数据",
  );
  fireEvent.click(
    within(alpha).getByRole("button", { name: "查看平台账单广告" }),
  );
  expect(host.navigate).toHaveBeenCalledWith("ads?source=ledger");
  await waitFor(() =>
    expect(read).toHaveBeenCalledWith(
      "/advertising/spend",
      expect.objectContaining({ shopId: "a" }),
    ),
  );
});
it("I5 selected scope removes unrelated account/store streams and distinguishes partial zero from no records", async () => {
  const account = {
    advertiser_id: "shared",
    name: "Shared account",
    currency: "USD",
    requestedDays: 3,
    discovery_status: "SUCCESS",
    stores: [
      {
        store_id: "store-a",
        store_name: "Linked Alpha",
        shop_id: "a",
        match_status: "MATCHED",
        active: true,
      },
      {
        store_id: "store-b",
        store_name: "Unrelated Beta",
        shop_id: "b",
        match_status: "MATCHED",
        active: true,
      },
    ],
    streams: [
      { channel: "BASIC", store_id: "", status: "SUCCESS" },
      { channel: "GMV_PRODUCT", store_id: "store-a", status: "SUCCESS" },
      { channel: "GMV_LIVE", store_id: "store-a", status: "ERROR" },
      { channel: "GMV_PRODUCT", store_id: "store-b", status: "SUCCESS" },
    ],
    coverage: [
      {
        channel: "BASIC",
        store_id: "",
        covered_days: 3,
        records: 0,
        amount: 0,
      },
      {
        channel: "GMV_PRODUCT",
        store_id: "store-a",
        covered_days: 1,
        records: 2,
        amount: 0,
      },
      {
        channel: "GMV_PRODUCT",
        store_id: "store-b",
        covered_days: 3,
        records: 8,
        amount: 999,
      },
    ],
  };
  const get = apiRead((path) =>
    path === "/advertising/spend"
      ? { totals: [], items: [] }
      : path === "/shops"
        ? { items: shops }
        : path === "/ads"
          ? [
              account,
              {
                advertiser_id: "other",
                name: "Other account",
                currency: "THB",
                shop_id: "b",
                stores: [],
                streams: [],
              },
            ]
          : {},
  );
  const v = renderFinance(<ReportsPage view="ads" initialAdTab="accounts" />, {
    filters: { shopId: "a", currency: "USD" },
    api: createFinanceApiFixture({ get }),
  });
  await v.findByText("Shared account");
  expect(v.queryByText("Other account")).not.toBeInTheDocument();
  expect(v.queryByText("Unrelated Beta")).not.toBeInTheDocument();
  expect(v.queryByText("999.00")).not.toBeInTheDocument();
  const coverage = within(
    v.getByRole("region", { name: "广告接口覆盖" }),
  ).getByRole("table");
  expect(coverage).toHaveTextContent("3 / 3");
  expect(coverage).toHaveTextContent("1 / 3");
  expect(coverage).toHaveTextContent("已采集部分");
  expect(coverage).toHaveTextContent("报表合计为0");
  expect(coverage).toHaveTextContent("接口未返回记录");
  expect(coverage).toHaveTextContent("尚未取得范围内报表");
  const basic = within(coverage).getByRole("row", {
    name: /普通广告（账户报表）/,
  });
  expect(basic).not.toHaveTextContent("USD 0.00");
});
it("I5 group/platform/native filters apply to returned account metadata, not just request URLs", async () => {
  const { scopedAdAccounts, adCoverageRows } = await import(
    "../src/features/finance/reports/advertising-domain"
  );
  const base = {
    start: "2026-10-01",
    end: "2026-10-03",
    shopId: "",
    groupId: "g",
    platform: "TIKTOK_SHOP",
    currency: "USD",
    displayCurrency: "CNY",
    granularity: "day",
    fxSnapshot: "",
    dateBasis: "",
  };
  const list = [
    { advertiser_id: "a", shop_id: "a", currency: "USD" },
    { advertiser_id: "b", shop_id: "b", currency: "USD" },
    { advertiser_id: "c", shop_id: "a", currency: "THB" },
  ];
  expect(
    scopedAdAccounts(list, shops, base).map((a) => a.advertiser_id),
  ).toEqual(["a"]);
  expect(
    scopedAdAccounts(list, shops, { ...base, platform: "SHOPEE" }),
  ).toEqual([]);
  const [row] = adCoverageRows(
    {
      requestedDays: 3,
      streams: [{ channel: "BASIC", store_id: "", status: "ERROR" }],
      coverage: [
        {
          channel: "BASIC",
          store_id: "",
          covered_days: 3,
          records: null,
          amount: 0,
        },
      ],
    },
    shops,
    base,
  );
  expect(row).toMatchObject({
    data_state: "返回记录数未核实",
    previous: true,
    amount: undefined,
    records: undefined,
  });
});
it("I3 complete zero and known partial zero stay visually different in operating rows", async () => {
  const partial = {
    currency: "CNY",
    records: 2,
    known_amounts: 1,
    amount: null,
    knownAmount: 0,
    complete: false,
  };
  const complete = {
    currency: "CNY",
    records: 2,
    known_amounts: 2,
    amount: 0,
    knownAmount: 0,
    complete: true,
  };
  const data = {
    shops,
    ranking: [],
    profit: {},
    advertising: {
      shops: [
        { ...partial, shop_id: "a" },
        { ...complete, shop_id: "b" },
      ],
    },
  };
  const v = renderFinance(<ReportsPage view="overview" />, {
    api: createFinanceApiFixture({
      get: apiRead((path) => (path === "/overview" ? data : {})),
    }),
  });
  await v.findByText("Alpha");
  const alpha = v.getByRole("row", { name: /Alpha/ }),
    beta = v.getByRole("row", { name: /Beta/ });
  expect(alpha).toHaveTextContent("0.00");
  expect(alpha).toHaveTextContent("已知部分");
  expect(beta).toHaveTextContent("0.00");
  expect(beta).not.toHaveTextContent("已知部分");
});
it("I8 interrupted calculation shows retained progress beside the available report", async () => {
  const data = {
    profit: { amount: 0 },
    settlementCalculation: {
      status: "INTERRUPTED",
      scope: "History",
      processed: 2,
      total: 9,
    },
  };
  const v = renderFinance(<ReportsPage view="overview" />, {
    api: createFinanceApiFixture({
      get: apiRead((path) => (path === "/overview" ? data : {})),
    }),
  });
  expect(
    await v.findByText("历史结算计算暂时中断，已保留数据与进度"),
  ).toBeInTheDocument();
  expect(v.getByText(/2 \/ 9/)).toBeInTheDocument();
  expect(v.getByText("CNY 0.00")).toBeInTheDocument();
});
it("I4 missing GMV coverage preserves pending discovery rather than asserting absent ownership", async () => {
  const { missingAdReason } = await import(
    "../src/features/finance/reports/advertising-domain"
  );
  expect(
    missingAdReason(
      "a",
      "gmv",
      [{ advertiser_id: "pending", discovery_status: "PENDING", stores: [] }],
      false,
    ),
  ).toBe("店铺关系发现待完成");
});
