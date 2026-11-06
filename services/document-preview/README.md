# Private document preview converter

This runtime converts `.ppt` and `.pptx` bytes into PDF. It has no database, object-storage credentials, persistent document storage or public viewer dependency. The application handles tenant authorization and storage before calling this private service.

Protocol at `http://heyi-document-preview:8092`:

- `GET /health` returns `{"status":"UP"}` without disclosing configuration.
- `POST /convert?format=ppt` or `format=pptx` accepts raw bytes and a single `Content-Length`. The header is `Authorization: Bearer <DOCUMENT_PREVIEW_TOKEN>`.
- A successful response contains raw `application/pdf` bytes with `Cache-Control: no-store`. Input and output are each limited to 50 MiB.
- Errors contain only a fixed JSON `code`: 400 invalid request, 401 unauthorized, 408 upload timeout, 413 size limit, 422 malformed/failed conversion, 429 busy, 500 unavailable, or 504 conversion timeout. Busy replies include `Retry-After: 3`.

The App configuration names are **APP_KNOWLEDGE_PREVIEW_URL** (the base URL above) and **APP_KNOWLEDGE_PREVIEW_TOKEN**. Inject the same private token into the converter as **DOCUMENT_PREVIEW_TOKEN**; do not save it in the repository, image, command arguments or public evidence. The service refuses a missing token or fewer than 32 ASCII characters. The application should allow slightly more than the 90-second conversion limit for its complete HTTP exchange.

Only one upload/conversion runs at a time. Each job uses a fresh private temporary directory and LibreOffice profile. The worker runs through `/usr/bin/python3` with Debian's `python3-uno` bridge. It loads the presentation using the official UNO `MacroExecMode.NEVER_EXECUTE` and `UpdateDocMode.NO_UPDATE` constants, hidden/read-only/silent mode, and an interaction handler that aborts prompts. The profile additionally sets `MacroSecurityLevel=3`. No fixed macro enum numbers are assumed. Password prompts and failed conversion never produce a success response. A 90-second timeout kills the entire worker/LibreOffice process group and cleans the temporary directory. The worker environment excludes the internal bearer token. Input package validation rejects macro-bearing PPTX and unsafe/encrypted archive entries.

Production Dockerfile defaults to the already verified official **amd64** Python image digest `python@sha256:2c941e860699f878900b0edc2403613c234d4b32eda3cc9fa7036991a2a63c4a`. It installs LibreOffice Impress, `python3-uno`, Noto CJK and Liberation fonts. No Python packages or App JAR dependencies are added. To make an ARM functional test image locally, override `PYTHON_BASE=python:3.12-slim` and set `--platform linux/arm64`; this test image is not the production artifact.

APT downloads use the Aliyun Debian and Debian security mirrors; the base image's official Debian signing keys and signature verification remain unchanged.

Required deployment controls, to be applied by the release owner:

- UID/GID `10001:10001`, read-only root filesystem, and a private `/tmp` tmpfs of at most 512 MiB (`rw,nosuid,nodev,noexec,mode=1777`). No host filesystem or application-storage mounts.
- Memory limit at most 1 GiB, one CPU, at most 128 PIDs, `cap_drop: ALL`, and `no-new-privileges:true`.
- Join only a newly created Docker **internal** network shared with the App. No host port publication, public ingress, external network, Docker socket or privileged mode. Network isolation is part of the security boundary because office documents may contain remote references.
- Preserve every existing App environment value/network/mount/port/process setting. Only add its two named preview variables and this internal network.

Run standard-library tests with `python3 -m unittest discover -s document-preview -p 'test_*.py' -v`. Real fixture conversion must also pass in the built candidate under the deployment constraints before activation.

## Reusable Compose overlay

`compose.document-preview.yaml` is an opt-in overlay for future deployments. Supply `DOCUMENT_PREVIEW_TOKEN` through a private environment file, then include the overlay after the normal Compose file. It adds only the App preview endpoint/token and internal network, plus the isolated converter. Do not use a public port or public online Office viewer. The pinned Dockerfile targets amd64; ARM development builds can use the documented `PYTHON_BASE` override.

PDF previews use an authenticated same-origin request and an in-memory blob iframe. Keep `frame-src 'self' blob:` in the application Nginx CSP; keep `object-src 'none'` and `frame-ancestors 'none'`. Original files and successful converted PDFs stay in Dayan OSS.
