# Status and adapter contract

## Task states

| Internal status | Workbook text | Meaning |
|---|---|---|
| `planned` | `待执行` | Folder and task record created; no live action taken |
| `running` | `执行中` | One platform executor is currently active |
| `completed` | `完成` | All expected files are confirmed locally |
| `manual_required` | `待人工` | Login, verification, or preserved live session needs a person |
| `manual_required` + `SHOPEE_INVALID_PASSWORD` | `密码错误，已退出` | Shopee explicitly rejected the autofilled login or in-site password and the exact store connection was stopped |
| `manual_required` + `SHOPEE_MISSING_PASSWORD` | `站内密码为空，已退出` | Shopee required in-site password verification but Feikua did not autofill a password |
| `manual_required` + `SHOPEE_PASSWORD_VERIFICATION_NOT_COMPLETED` | `站内密码验证未完成，已退出` | Shopee kept the in-site password dialog open without explicitly rejecting the password, so the exact store connection was stopped without mislabeling it as a wrong password |
| `manual_required` + `SHOPEE_SELLER_HOME_NOT_REACHED` | `未进首页，请检查登录` | Shopee did not reach the authenticated Seller Center home and the exact store connection was stopped |
| `manual_required` + `TIKTOK_SELLER_HOME_NOT_REACHED` | `未进首页，请检查登录` | TikTok did not reach the authenticated Seller Center home and the exact store connection was stopped |
| `manual_required` + `LAZADA_SELLER_HOME_NOT_REACHED` | `未进首页，请检查登录` | Lazada did not reach the authenticated Seller Center home and the exact store connection was stopped |
| `manual_required` + `LAZADA_INVALID_CREDENTIALS` | `账号或密码错误，已退出` | Lazada rejected the single autofilled login attempt and the exact store connection was stopped |
| `manual_required` + `LAZADA_ACCOUNT_DEACTIVATED` | `店铺已停用，已退出` | Lazada redirected finance navigation to account health and explicitly showed that the account/store is deactivated or offline |
| `no_data` + `SHOPEE_NO_FILES_FOR_PERIOD` | `无数据` | Shopee confirmed terminal no-file results for both income and balance |
| `completed` + `SHOPEE_INCOME_FILE_CONFIRMED_BALANCE_NO_DATA` | `完成` | Income file confirmed; balance confirmed no file |
| `completed` + `SHOPEE_BALANCE_FILE_CONFIRMED_INCOME_NO_DATA` | `完成` | Balance file confirmed; income confirmed no file |
| `manual_required` + `LAZADA_INVALID_CREDENTIALS` | `账号密码错误需要手动导出` | Lazada explicitly rejected the stored credentials and the exact store connection was stopped |
| `manual_required` + `LAZADA_POST_LOGIN_VERIFICATION_PROBLEM` | `出现验证码、短信、OTP 或其他二次验证` | Lazada required CAPTCHA, OTP, SMS, or another verification after login and the exact store connection was stopped |
| `failed` | `未完成` | No confirmed bill file exists |
| `unsupported` | `暂不支持` | Platform executor is not installed |

Never set `completed` from a zero exit code alone.
For a live bill task, `CONNECTED` or a Seller Center home marker is not proof
that execution started. Require the platform `*_EXPORT_EXECUTION_STARTED`
marker immediately after authenticated-home detection and before finance
navigation. A task that reaches home without this marker is a script defect,
not an executing task, and must never be reported as running successfully.
For Shopee password rejection, require all four signals: process exit code `22`,
`SHOPEE_INVALID_PASSWORD`, `SHOPEE_INVALID_PASSWORD_CONNECTION_STOPPED`, and
`SHOPEE_MANUAL_EXPORT_REQUIRED`. Write `结果未导出；备注：密码错误` to Excel,
leave both bill file lists empty, and continue to the next store. Do not classify
timeouts, CAPTCHA, OTP, or network failures as password rejection.
For Lazada credential rejection, require all three signals: process exit code
`20`, `LAZADA_INVALID_CREDENTIALS_CONNECTION_STOPPED`, and
`LAZADA_MANUAL_EXPORT_REQUIRED`. Do not classify timeouts, CAPTCHA, OTP, or
network failures as credential rejection.
For Lazada post-login verification, require exit code `21` plus
`LAZADA_POST_LOGIN_VERIFICATION_PROBLEM` and
`LAZADA_VERIFICATION_PROBLEM_CONNECTION_STOPPED`. Write
`出现验证码、短信、OTP 或其他二次验证` to Excel and continue to the next store.

## Executor input

Every platform adapter receives:

```json
{
  "executorName": "exact platform shop name",
  "startDate": "YYYY-MM-DD",
  "endDate": "YYYY-MM-DD",
  "outputDir": "absolute store folder"
}
```

## Executor result

Every platform adapter must return:

```json
{
  "status": "completed",
  "terminalCode": "PLATFORM_FILE_CONFIRMED",
  "files": ["absolute confirmed file path"],
  "message": ""
}
```

Require every returned file to exist, have a size greater than zero, and be located under one of the task's declared board directories. Reject partial-download files. Shopee and Lazada complete only after both the income file under `收入板块` and the balance-detail file under `余额明细板块` are confirmed. For Lazada, also require exit code `0` and `LAZADA_BOTH_DOWNLOADS_COMPLETE` from the combined executor result.
For each Shopee section, a recovered or newly confirmed valid exact-date file
takes precedence over a later history row that exposes no new file. Never
return both a non-null file path and `*_no_data=true`; no-data is legal only
while that section's file path is empty.
After confirmation, move each bill to its matching board/store folder and normalize the filename to `<店铺名称><结束日期>.<原扩展名>`. Persist status only in `_run/tasks.json`; never create per-store task-status JSON files.
Delivery is idempotent: if the exact normalized target already exists, reuse it
only after confirming it is non-empty and, for XLSX, contains
`xl/workbook.xml`. Do not downgrade a confirmed export merely because a prior
attempt already delivered the same valid target.

## Excel finalizer behavior

1. Load the original workbook with the Codex-provided `@oai/artifact-tool` runtime.
2. Insert the new date-status column at column C on every relevant sheet.
3. Use a full-year label such as `2026.07.01-2026.07.03`.
4. Preserve current workbook styles, merged headers, formulas, row heights, column widths, and historical status columns.
   Shift every merged range at or after column C together with its data.
5. Fill rows from `tasks.json` by worksheet and original Excel row number.
6. Save a new workbook under the run package; never overwrite the source.
7. Inspect status ranges and scan formula errors before delivery. Render every sheet at least once; on later finalizations, reuse a prior sheet render only when the source-workbook fingerprint and that sheet's status signature are unchanged.
8. Dry runs generate the workbook only with `-GeneratePreviewWorkbook`; live execution generates it automatically after all selected tasks finish.
9. Reuse the verified output workbook without reopening the spreadsheet runtime when the source workbook and `tasks.json` are both older than the existing workbook and finalization record.
