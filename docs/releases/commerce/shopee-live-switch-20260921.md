# Shopee production access — 2026-09-21

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

Production backend switched from sandbox partner 1243570 to live partner 2044280. Authorization host: https://open.shopee.com. API host: https://partner.shopeemobile.com. Sandbox-only guard disabled for the approved live application. Official publishing was already enabled and was preserved.

Only six Shopee environment variables changed. Application image, unrelated environment (including TikTok and OSS), and mount mappings verified unchanged. Mount array order changed during container recreation; sorted mappings matched. Production home returned HTTP 200; unauthenticated auth endpoint returned expected 401. Signed read-only API probe reached Shopee but rejected invalid test shop ID; it is not evidence of successful shop access.

Logged-in UI displayed official publishing enabled. Copy authorization link succeeded and verified live partner ID, official authorization host, callback https://hoyee.org.cn/api/v1/shop-authorizations/callback/shopee and state. No authorized shops currently exist in the company. Full token exchange and product publishing remain unverified until a real shop is authorized in its corresponding network environment.

Control panel: live app Online, callback domain correct, partner key expires 2027-03-20 23:59 UTC+8. Sensitive buyer fields remain masked in order detail, returns list/detail, and buyer invoice APIs. Account supporting documents: Pending Upload. Required: signed declaration and live product screenshots (one file each, max 10 MB, PDF/JPG/JPEG/PNG), live product URL and review username/password. Security reports section currently empty; no conclusion that a particular certification is mandatory.

Server release configuration and private pre-switch backup: /opt/heyi/deployments/shopee-live-20260921. No secret values stored in this document.
