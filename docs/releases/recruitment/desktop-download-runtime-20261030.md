# Recruitment assistant download recovery — 2026-10-30

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

Both existing installer routes returned HTTP 502. The systemd download service
was repeatedly exiting with status 203 because its executable,
`/opt/heyi/boss-agent/venv/bin/python`, no longer existed. The installer manifest
and the two private object-storage artifacts were still present.

The download service now has its own small Python environment at
`/opt/heyi/desktop-installers/venv`, with `oss2==2.19.1`, and its own module at
`/opt/heyi/desktop-installers/service/desktop_installers.py`. It no longer imports
the old collector's module. The existing HR permission policy was preserved.
The retired collector remains stopped; no browser/collector environment was
restored.

The service retains the existing credential mount, verified manifest, private
bucket, expiring download signatures, no-store headers and download routes.
Service stdout/stderr are disabled. The manifest and frontend download links
were not modified.

Validation:

- Seven download tests passed, including an isolated import with no recruitment
  modules, existing authorization rules, rejection of unauthorized requests,
  immutable artifact selection, HTTP HEAD and GET redirects.
- The Mac artifact (281,197,240 bytes) and Windows artifact (273,265,774 bytes)
  both returned HEAD 200 with their expected sizes.
- Signed range requests returned 206; the DMG footer and Windows executable
  header matched their formats. These were range checks, not new full-file
  SHA-256 downloads.
- The service runs as `heyi-boss`, with its directory explicitly set to 0750
  and module to 0640. A readability/import check under that user is performed
  before activation.
- Both public routes now reach the service and correctly return 401 for absent
  or invalid sessions. Private storage signing was verified in an isolated
  probe; no user's session was extracted or impersonated.

Repair tooling: `scripts/maintenance/repair-desktop-download-runtime.py`. The active runtime
is operational state and must be kept during release-artifact cleanup.
The duplicate staged module and bytecode cache were removed after activation;
the active runtime, manifest, service definition and rollback service file were
retained.
