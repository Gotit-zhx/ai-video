# UI localization for marketplace operations

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

Scope: interface labels, menus, buttons, forms, instructions, operational status, validation and errors. Product content, editable values, scripts, prompts and generated output retain their original language.

Supported locales: `zh`, `en`, `ms`, `id`, `th`, `vi`, `fil`. English covers Singapore; Malay covers Malaysia. URL `lang` takes priority over saved preference, then browser preference; unrecognized browser languages fall back to English. Three selectors share the same preference (public portal, login, workspace).

`frontend/i18n/catalog.json` contains six translations per source: English, Indonesian, Thai, Vietnamese, Malay, Filipino. `overrides.json` holds reviewed terminology. Run `node scripts/build/build-ui-i18n.cjs` after editing either file; it validates every locale and placeholder and builds `catalog.js`. The bundled catalog loads before application scripts, avoiding a separate asynchronous translation fetch during normal startup.

The DOM renderer preserves original strings for switching back, handles dynamic nodes without relying on animation frames, translates tooltips and accessibility labels, and freezes implicit option values before changing their display labels. It does not translate input values or `translate="no"` content. Native confirmation messages and form validation are localized. Template substitution preserves dollar signs and arbitrary user names. Known interface labels nested in operational errors or navigation headings are translated separately.

QA: `node --test frontend/test/i18n.test.cjs`. Browser checks use an isolated local test company; do not initiate real shop publishing or generation just to verify localization. `HoyeeLanguage.missing()` is a local-only diagnostic list, not telemetry; it may include user content, and must not be sent to translation services.

Deployment is a frontend overlay on the running production image. It preserves production Nginx configuration, environment variables, mounts, backend, model settings and OSS configuration. Do not deploy the complete local frontend because it also contains unrelated work.

Verification (2026-09-21): 4,729 UI catalog keys validated across six locales with placeholder parity and no Chinese translation residues. Runtime tests: 8 passed; task-history and video-terminal regressions: 26 passed; production-overlay navigation: 2 passed. Browser inspected portal/login and eight core views in all six languages. Company and user names deliberately remain original. The isolated fixture lacks permissions for some shop/product reads, so this is interface validation rather than a live publishing or generation acceptance test. Native file input presentation follows platform language while preserving actual file inputs and filenames.

Production deployed to `https://hoyee.org.cn/` using image `heyi-ai-frontend:languages-full-20260921`. Every deployed overlay file matched its validated SHA-256 after rollout. Public Malay login rendered correctly; all six locale catalogs were available; unauthenticated auth endpoint returned expected HTTP 401. Production environment and mounts remained unchanged. Local running frontend also received the full catalog.
