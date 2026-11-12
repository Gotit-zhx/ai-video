/* Synthetic server facts shared by classic and modern acceptance. No financial math here. */
const shops = [
  {
    id: "s1",
    name: "Fixture Shop",
    display_name: "Fixture Shop",
    platform: "TIKTOK_SHOP",
    currency: "PHP",
    group_id: "g1",
    timezone: "Asia/Manila",
    enabled: true,
  },
  {
    id: "s2",
    name: "Fixture Shopee",
    platform: "SHOPEE",
    currency: "MYR",
    group_id: "g1",
    timezone: "Asia/Kuala_Lumpur",
    enabled: false,
  },
];
const groups = [
  { id: "g1", name: "Fixture Group", shop_count: 2 },
  { id: "g0", name: "Empty Group", shop_count: 0 },
];
const fields = {
  fields: [
    { id: "external_id", label: "编号", type: "text" },
    { id: "record_date", label: "业务日期", type: "date" },
    { id: "amount", label: "销售收入", type: "money" },
    { id: "currency", label: "原币", type: "text" },
  ],
  visible: ["external_id", "record_date", "amount"],
  exported: ["external_id", "amount"],
  defaults: { visible: ["external_id"], exported: ["external_id"] },
};
const overview = {
  filters: { start: "2026-10-01", end: "2026-10-31", displayCurrency: "CNY" },
  fx: {
    id: "fixture-fx-202610",
    status: "COMPLETE",
    provider: "FIXTURE",
    mode: "HISTORICAL",
    rates: [],
  },
  dateBasis: {
    version: "shop-calendar-v2-report-basis",
    selection: "SHOP_LOCAL_CALENDAR",
    endInclusive: true,
    operationalTimezone: "Asia/Shanghai",
    note: "统计日期按店铺当地时间；同步时间按北京时间显示。",
    qianyiReceiptTimezone: "Asia/Shanghai",
    completionNote:
      "抓取完成表示接口分页和明细完整，不代表已与官方原始文件逐笔对账。",
  },
  basis: "经营统计覆盖当前筛选和已入库记录，按业务日历史汇率换算。",
  totals: [
    { kind: "ORDER", amount: 128420, record_count: 1820, known_amounts: 1820 },
    { kind: "SETTLEMENT", amount: 98310, revenue: 145270 },
    { kind: "FEE", amount: 18640 },
  ],
  profit: {
    settledGrossAmount: 145270,
    operatingCost: 76540,
    knownOperatingCost: 76540,
    amount: 50090,
    estimated: false,
  },
  advertising: {
    total: {
      amount: 4920,
      knownAmount: 4920,
      hasRecords: true,
      complete: true,
    },
    independent: { amount: 2860, hasRecords: true, complete: true },
    settlement: { amount: 2060, hasRecords: true, complete: true },
    commission: { amount: 680, hasRecords: true, complete: true },
    gmv: { amount: 900, hasRecords: true, complete: true },
  },
  productCosts: { amount: 71620, known_amount_cny: 71620, pending_count: 0 },
  ranking: [
    {
      shop_id: "s1",
      currency: "CNY",
      kind: "ORDER",
      amount: 128420,
      records: 1820,
      known_amounts: 1820,
    },
    {
      shop_id: "s1",
      currency: "CNY",
      kind: "SETTLEMENT",
      amount: 98310,
      settled_gross_amount: 145270,
      revenue: 145270,
      records: 1800,
      known_amounts: 1800,
    },
    {
      shop_id: "s1",
      currency: "CNY",
      kind: "FEE",
      amount: 18640,
      records: 1800,
      known_amounts: 1800,
    },
  ],
  shops,
  groups,
  trend: [
    {
      period: "2026-10-01",
      ORDER: 100,
      SETTLEMENT: 80,
      SETTLEMENT_GROSS: 100,
      FEE: 20,
    },
    {
      period: "2026-10-02",
      ORDER: 120,
      SETTLEMENT: 90,
      SETTLEMENT_GROSS: 120,
      FEE: 30,
    },
  ],
  fees: [],
  categories: [],
  statuses: [],
  sync: [],
};
overview.advertising = {
  ...overview.advertising,
  categories: [],
  groups: [],
  shops: [],
  trend: [],
};
const cost = {
  shopId: "s1",
  groupId: "g1",
  costs: [{ sku: "SKU", warehouse: "菲律宾", prices: { "2026-10": 12 } }],
  manualCosts: [
    {
      id: "m1",
      sku: "MANUAL",
      warehouse: "菲律宾",
      unitPriceCny: 7,
      unit: "包",
      version: 2,
    },
  ],
  imports: [{ id: "f1", source_name: "fixture-cost.csv", row_count: 1 }],
  products: [
    {
      product_id: "p1",
      sku_id: "v1",
      seller_sku: "SKU",
      name: "Fixture product",
    },
  ],
  mappings: {
    "s1:p1:v1:菲律宾": {
      code: "SKU",
      factor: 1,
      warehouse: "菲律宾",
      version: 3,
    },
  },
  settings: {
    warehouse: "菲律宾",
    rules: { currency: "CNY", unit: "包", basis: "order", missing: "pending" },
  },
  settingsVersion: 2,
};
const change = {
  id: "c1",
  type: "IMPORT",
  status: "READY",
  version: 5,
  canConfirm: true,
  canRevise: true,
  canReject: true,
  requiresIndependentReview: false,
  metadata: {
    reason: "fixture reason",
    evidenceRef: "fixture receipt",
    unit: "包",
    identifierType: "SKU",
    sourceKey: "fixture-source",
  },
  impact: { hasHistorical: true },
  validation: { issues: [] },
};
const brush = {
  rows: [
    {
      id: "brush1",
      order_id: "BRUSH-1",
      principal_native: 10,
      service_fee_cny: 3,
      shop_hint: "Fixture Shop",
      match: {
        matchStatus: "MATCHED",
        currency: "PHP",
        matchNote: "fixture exact match",
      },
      marker: { source_count: 1 },
    },
  ],
  total: 101,
  pageSize: 50,
  imports: [{ id: "b1", source_name: "fixture-brush.csv", row_count: 101 }],
  sourceSummary: { principal_rows: 87, service_fee_cny: 432.1 },
  marked: { orders: 80, conflicts: 0, service_fee_cny: 400 },
  periodMarked: { service_fee_cny: 390 },
  totals: { unmatched: 0, records: 101 },
  labelSummary: { processing: false },
};
const qianyiRow = {
  shop_id: "s1",
  return_number: "RETURN-1",
  order_id: "ORDER-1",
  shop_name: "Fixture Shop",
  aftersale_type: "SALES_RETURN",
  return_state: "FINISH",
  return_receipt_label: "SIGNED",
  receipt_event: "fixture return signed",
  source_received_at: "2026-10-12T04:00:00Z",
  refund_amount: 12,
  refund_currency: "PHP",
  cost_status: "READY",
  returnCost: { amountCny: 7 },
};
function createFinanceData() {
  const state = {
    shops: structuredClone(shops),
    groups: structuredClone(groups),
    fields: structuredClone(fields),
    cost: structuredClone(cost),
    jobs: [],
    calls: [],
  };
  const reads = {
    "/overview": overview,
    "/fx/market": { items: [] },
    "/records": {
      filters: overview.filters,
      fieldSettings: fields,
      fx: overview.fx,
      items: [
        {
          id: "r1",
          external_id: "ORDER-1",
          order_id: "ORDER-1",
          record_date: "2026-10-12",
          amount: 123.45,
          currency: "PHP",
          kind: "ORDER",
        },
      ],
      total: 101,
    },
    "/reconciliation": {
      items: [{ id: "rec1", external_id: "BILL-1", amount: 123.45 }],
      total: 1,
    },
    "/records/r1": {
      id: "r1",
      kind: "ORDER",
      external_id: "ORDER-1",
      order_id: "ORDER-1",
      record_date: "2026-10-12",
      amount: 123.45,
      product_sku_details: [
        { name: "Fixture Product", sellerSku: "SKU", quantity: 1 },
      ],
      productCost: {
        note: "fixture historical cost",
        lines: [
          { costCode: "SKU", quantity: 1, unitPriceCny: 12, amountCny: 12 },
        ],
      },
    },
    "/advertising/spend": {
      items: [
        {
          source: "IMPORT",
          source_key: "ad1",
          platform: "TIKTOK_SHOP",
          amount: 25,
          currency: "PHP",
          account_id: "adv1",
          assignment_status: "UNASSIGNED",
        },
      ],
      total: 1,
      totals: [{ currency: "PHP", amount: 25 }],
      shopeeAds: { configured: true, items: [] },
    },
    "/advertising/spend/ad1": {
      source: "IMPORT",
      source_key: "ad1",
      platform: "TIKTOK_SHOP",
      amount: 25,
      cash_amount: 20,
      credit_amount: 5,
      currency: "PHP",
      fingerprint: "fixture-fingerprint",
      allocations: [
        { shop_id: "s1", amount: "25", cash_amount: "20", credit_amount: "5" },
      ],
    },
    "/ads": [
      {
        advertiser_id: "adv1",
        name: "Fixture Advertiser",
        shop_id: "s1",
        currency: "PHP",
        enabled: true,
        status: "SUCCESS",
      },
    ],
    "/ads/application": { configured: true },
    "/shopee-ads": {
      configured: true,
      coverageBasis: "官方 CPC expense；GMS 覆盖关系待核实",
      items: [
        {
          external_shop_id: "9001",
          name: "Fixture CPC",
          shop_id: "s2",
          group_id: "g1",
          currency: "MYR",
          enabled: true,
          status: "SUCCESS",
          records: 0,
        },
      ],
    },
    "/workspace/charges": brush,
    "/workspace/label-costs": {
      rows: [{ id: "l1", order_id: "ORDER-1", amount_cny: 2 }],
      files: [{ id: "lfile", source_name: "fixture-label.xlsx" }],
      total: 1,
    },
    "/workspace/costs/input-changes": { items: [change], total: 1 },
    "/workspace/costs/input-changes/c1": change,
    "/workspace/costs/orders": {
      items: [
        { id: "o1", order_id: "ORDER-1", cost_status: "READY", amount_cny: 12 },
      ],
      total: 1,
    },
    "/qianyi": {
      configured: true,
      hasSecret: true,
      app_id: "fixture-public-id",
      environment: "CN",
      sync_start: "2026-10-01",
      enabled: false,
      status: "PAUSED",
      shop_count: 1,
      bound_count: 1,
    },
    "/qianyi/shops": {
      items: [{ id: "q1", name: "Fixture Qianyi", shop_id: "s1" }],
      total: 1,
    },
    "/qianyi/returns": {
      items: [qianyiRow],
      total: 1,
      summary: {
        matchedRecords: 1,
        records: 1,
        awaitingLogistics: 0,
        refundTotals: [
          { currency: "PHP", amount: 12, records: 1, known_count: 1 },
        ],
        returnCost: { amountCny: 7 },
      },
    },
    "/qianyi/returns/detail": {
      ...qianyiRow,
      sourceFields: {
        _returnReceipt: {
          event: "fixture return signed",
          eventDate: "2026-10-12T04:00:00Z",
        },
        type: "SALES_RETURN",
        returnStatus: "FINISH",
        returnSkuList: [
          { sku: "SKU", quantity: 1, selected: true, isDeleted: false },
        ],
      },
      returnCost: {
        amountCny: 7,
        lines: [
          { costCode: "SKU", quantity: 1, unitPriceCny: 7, amountCny: 7 },
        ],
      },
    },
  };
  function handle(c) {
    state.calls.push(c);
    const p = c.path;
    if (c.method === "GET") {
      if (p === "/shops")
        return {
          items: state.shops,
          groups: state.groups,
          sync: [],
          deletions: [],
        };
      if (p === "/groups") return state.groups;
      if (/^\/fields\/[A-Z_]+$/.test(p)) return state.fields;
      if (p === "/workspace/costs") return state.cost;
      if (["/fee-exports", "/cost-exports"].includes(p)) return state.jobs;
      if (
        [
          "/export",
          "/qianyi/returns/export",
          "/workspace/costs/orders/export",
          "/workspace/label-costs/export",
        ].includes(p) ||
        /^\/(fee|cost)-exports\/j1\/download$/.test(p)
      )
        return {
          binary: Buffer.from("fixture,binary\nonly,synthetic\n"),
          headers: {
            "Content-Disposition": 'attachment; filename="fixture.csv"',
          },
        };
      if (Object.hasOwn(reads, p)) return structuredClone(reads[p]);
    }
    if (c.method === "POST" && p === "/screen") return overview;
    if (c.method === "PUT" && /^\/fields\/[A-Z_]+$/.test(p)) {
      state.fields = { ...state.fields, ...c.body };
      return state.fields;
    }
    if (c.method === "POST" && p === "/imports/preview")
      return {
        columns: ["external_id", "date", "currency", "amount"],
        sample: [
          {
            external_id: "FIXTURE-IMPORT",
            date: "2026-10-01",
            currency: "PHP",
            amount: 12,
          },
        ],
        rows: 1,
      };
    if (c.method === "POST" && p === "/imports")
      return { rows: 1, inserted: 1, duplicateRows: 0 };
    if (c.method === "POST" && p === "/advertising/imports")
      return c.form.preview === "true"
        ? {
            rows: 1,
            sample: [{ amount: 12 }],
            totals: [{ currency: "PHP", amount: 12 }],
          }
        : { rows: 1, inserted: 1 };
    if (c.method === "POST" && ["/fee-exports", "/cost-exports"].includes(p)) {
      const job = { id: "j1", status: "READY", filename: "fixture.csv" };
      state.jobs = [job];
      return job;
    }
    if (c.method === "POST" && p === "/groups") {
      state.groups.push({ id: "g2", name: c.body.name, shop_count: 0 });
      return { id: "g2" };
    }
    if (c.method === "PUT" && /^\/groups\/g[012]$/.test(p)) {
      state.groups = state.groups.map((g) =>
        g.id === p.split("/").at(-1) ? { ...g, ...c.body } : g,
      );
      return { updated: true };
    }
    if (c.method === "DELETE" && /^\/groups\/g[02]$/.test(p)) {
      state.groups = state.groups.filter((g) => g.id !== p.split("/").at(-1));
      return { deleted: true };
    }
    if (c.method === "PUT" && /^\/shops\/s[12]$/.test(p)) {
      state.shops = state.shops.map((s) =>
        s.id === p.split("/").at(-1)
          ? { ...s, ...c.body, display_name: c.body.alias || s.name }
          : s,
      );
      return { updated: true };
    }
    if (c.method === "DELETE" && /^\/shops\/s[12]$/.test(p)) {
      state.shops = state.shops.filter((s) => s.id !== p.split("/").at(-1));
      return { accepted: true };
    }
    if (
      c.method === "POST" &&
      ["/shops/authorize", "/ads/authorize", "/shopee-ads/authorize"].includes(
        p,
      )
    )
      return {
        authorizationUrl:
          "https://fixture.invalid/authorization?state=synthetic",
        expiresAt: "2026-11-12T12:00:00Z",
      };
    if (c.method === "POST" && p === "/workspace/charges/preview")
      return {
        count: 1,
        matched: 1,
        serviceFeeCny: 3,
        rows: [
          {
            orderId: "BRUSH-2",
            shopHint: "Fixture Shop",
            principalNative: "10",
            serviceFeeCny: 3,
            match: { matchStatus: "MATCHED", matchNote: "fixture exact" },
          },
        ],
      };
    const writes = {
      PUT: [
        "/shopee-ads/9001",
        "/ads/adv1",
        "/qianyi",
        "/workspace/costs/s1/mapping",
        "/workspace/costs/s1/settings",
        "/workspace/costs/groups/g1/manual/m1",
        "/advertising/spend/ad1/allocations",
      ],
      POST: [
        "/shops/s1/priority",
        "/shops/s1/sync",
        "/workspace/charges/import",
        "/workspace/charges/rematch",
        "/workspace/label-costs/import",
        "/workspace/label-costs/rematch",
        "/workspace/costs/import",
        "/workspace/costs/s1/auto-match",
        "/workspace/costs/groups/g1/manual",
        "/workspace/costs/s1/manual-mapping",
        "/workspace/costs/input-changes/c1/confirm",
        "/workspace/costs/input-changes/c1/reject",
      ],
      DELETE: [
        "/workspace/charges/imports/b1",
        "/workspace/label-costs/imports/lfile",
        "/workspace/costs/groups/g1/files/f1",
        "/workspace/costs/groups/g1/manual/m1",
      ],
    };
    if (writes[c.method]?.includes(p))
      return { status: "APPLIED", updated: true, rows: 1, inserted: 1 };
  }
  return { state, reads, handle };
}
module.exports = { createFinanceData, overview, brush, fields, shops, groups };
