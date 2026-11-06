# Integrated architecture

`run.ps1` owns workbook planning, resumable task execution, file delivery, and
Excel finalization. It routes only to bundled executors:

| Task executor | Bundled entry point | Output |
|---|---|---|
| `tk-bill` | `executors/tk-bill/run.ps1` | Income |
| `tk-bill-cn` | `executors/tk-bill-cn/run.ps1` | Income |
| `shopp-bill` | `executors/shopp-bill/run.ps1 --export-both` | Income + balance detail |
| `lazada-bill` | `executors/lazada-bill/run.ps1 --export-both` | Income + balance detail |

The Shopee, Lazada, and cross-border TikTok executors reuse
`executors/tk-bill` for exact Feikua shop lookup, session reuse, dynamic CDP
ports, ChromeDriver selection, verification handling, and connection stopping.

The shared connection kernel treats Feikua lifecycle events as authoritative.
After a confirmed TikTok export it requests an asynchronous close so release
latency cannot turn a valid bill into a failed task. Before the next store
starts, the handoff confirms both the previous store's `idle` state and closure
of its CDP port. HTTP 409/500 stop responses mean the exact close is already in
transition. If a start request returns HTTP 500 after already launching Chrome,
the kernel recovers that exact live CDP session instead of opening the store
again.

Keep these boundaries:

1. Change workbook planning and status behavior only in the root workflow.
2. Change platform UI behavior only in the matching bundled executor.
3. Change shared Feikua/CDP behavior only in bundled `tk-bill`.
4. Do not reference sibling skills under `%USERPROFILE%\.codex\skills`.
5. Do not edit or delete the original source skills when changing `yue-bill`.
