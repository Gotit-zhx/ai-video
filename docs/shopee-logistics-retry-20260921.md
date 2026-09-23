# Shopee logistics read recovery

Observed for CurveLuxe PH (1807508681): official shop status NORMAL, PH local shop, is_cb=false, is_upgraded_cbsc=false. Official logistics responses varied between an empty successful list, three enabled channels (40029, 4000, 40066), and logistics.inner_error. This confirms upstream inconsistency; no evidence establishes why Shopee returned the empty list.

Bulk preparation now shares up to five attempts per shop across the batch. Read-only publish-options HTTP 500 is treated as transient, alongside existing timeout/rate-limit/gateway failures. Missing/empty Shopee channel lists are retried. Nested retries are disabled to keep the budget at five. Explicit authorization errors do not retry. Workflow POST behavior and idempotency are unchanged. Nonempty lists with no enabled channels retain a distinct actionable error. No channels are invented or enabled automatically.

Validation: 71 local integration/retry tests; 38 tests on the production-derived overlay. Release deployed at /opt/heyi/deployments/shopee-logistics-20260921, frontend only. Asset hashes and homepage HTTP 200 verified. Backend, OSS and credentials unchanged. Existing open tabs must refresh to load the new app version. No live product was submitted by this diagnostic run.
