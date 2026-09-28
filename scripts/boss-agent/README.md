# Linux CDP connector installation

The production connector is isolated from the Hoyee containers. It uses
`heyi-boss`, `/opt/heyi/boss-agent/accounts/pilot`, Xvfb display `:98`, and
Chrome CDP on `127.0.0.1:9222`. The Python virtual environment pins
`boss-agent-cli==2.0.0`.

Install the startup scripts and `boss-pilot` wrapper under
`/opt/heyi/boss-agent/bin/`, and the service files under
`/etc/systemd/system/`. The connector stays enabled; Chrome and Xvfb are
disabled at boot and start only for login or verification. They stop on logout or
an explicit recruiter role rejection. Inconclusive verification and connector
restarts preserve the browser, including a manually scanned session.
A narrow sudoers rule permits the unprivileged
connector account to start/stop only these BOSS services. VNC and noVNC
services are deliberately disabled at boot; an administrator can start them
temporarily for account enrollment. They listen only on loopback (`5908` and
`6088`), and VNC requires the password stored with mode 0600 in
`/opt/heyi/boss-agent/runtime/vnc-password.txt`.

The CLI and its Linux x86_64 / Python 3.14 dependencies were transferred as
wheel files from the local computer, including the Linux-specific `jeepney`
dependency. The retained wheelhouse under
`/opt/heyi/boss-agent/boss-agent-wheelhouse/` allows offline reinstallation:

```sh
runuser -u heyi-boss -- /opt/heyi/boss-agent/venv/bin/python -m pip install \
  --no-index --find-links=/opt/heyi/boss-agent/boss-agent-wheelhouse \
  boss-agent-cli==2.0.0
```

To view the enrollment desktop from a trusted local computer, forward
`127.0.0.1:6088` over SSH and open `http://127.0.0.1:6088/vnc.html` locally.
Stop `heyi-boss-novnc.service` and `heyi-boss-vnc.service` after enrollment.
Do not publish the CDP, VNC, or noVNC ports through a reverse proxy or
security group.

Run the CLI as `heyi-boss` with the account-specific wrapper:

```sh
runuser -u heyi-boss -- /opt/heyi/boss-agent/bin/boss-pilot doctor
```

`AUTH_REQUIRED` before account enrollment is expected. For login, follow the
upstream `login --cdp` flow only after the actual recruiter authorizes that
account. The connector does not pass `--force`; an explicit logout clears
this isolated account before any switch.
When Chrome already has a login, or the administrator retries verification,
the connector first synchronizes the browser into the CLI's encrypted store
using upstream `login_via_cdp(reuse_existing=True)`, then checks recruiter jobs.
It intentionally bypasses `AuthManager.login` for reuse: upstream can fall back
to a forced QR login after a stale-session probe. Sync failures preserve the
browser and previous credentials; they never silently force another scan.
The bounded sync child returns only a result code, never cookies or fingerprints.
`doctor` also warns that patchright's bundled Chromium is absent; the selected
CDP path uses the separately installed Google Chrome. A direct patchright CDP
connection to this Chrome was verified without an account login.

The platform administrator `13800000000` opens the recruiter workspace and
the QR appears automatically for an initial login. An explicit disconnect
persists across page reloads and service restarts. The separate
“清除会话并重新扫码” action explicitly requests a single cleanup followed by
one QR enrollment; its action lock prevents double-clicked or stale requests
from restarting that scan. The
connector sends only a cropped QR image from the BOSS login tab to that
authenticated administrator. When a code expires, the connector uses the
login page's own refresh control. Reading the QR never reloads the page or
resets a scan awaiting phone confirmation. BOSS removes the QR container after
scanning, so the connector checks the login page's visible confirmation state
before locating the QR. A platform page reload joins the ongoing scan and
resumes status polling; transient read failures keep retrying. The account owner
scans with the BOSS app and confirms on their phone. A successful QR scan is
*not* enough to enable reads: the connector requires the recruiter-only jobs
endpoint to succeed. The monochrome loading indicator distinguishes phone
confirmation from account verification, and the success notice appears only
after verification, alongside the transition to the job list.
A degraded secondary token alone does not prove a role
mismatch. An explicit role mismatch is
rejected and cleared. An inconclusive jobs response keeps credentials but blocks
all recruiter reads; the UI shows the upstream error code and offers a separate
verification retry without making the user scan again. Disconnect deletes
only the local CLI `session.enc`, then stops Chrome and Xvfb before deleting
only `zhipin.com` and subdomain cookie rows from the isolated Chrome profile.
Cleanup failure is returned to the UI; a failed clear cannot silently reuse
the old login. Unrelated domains, profile files and the encryption salt are
preserved. It never invokes a platform logout command. Ordinary disconnect
does not start login; only the explicit reset-and-scan action does so.
The private `runtime/recruiter-disconnected` marker also pauses reads
and resume polling until the administrator explicitly reconnects. This bounds
our own behavior; it does not establish why BOSS invalidated a phone session.
If BOSS reports abnormal server IP behavior, the connector preserves the
session, stops automatic recruiter probes across service restarts, and displays
the platform restriction. The account owner may retry verification after
checking the warning through official BOSS channels.
That retained warning describes the previous response, not a permanent IP ban.
An explicit verification retry imports the latest browser login before making
one recruiter jobs check; successful verification removes the old hold.
Recruiting reads remain gated by `/api/v1/auth/me` on every request. The sole
enabled write is an explicit attachment-resume request for a specified
conversation, after browser confirmation. QR login and background polling
never send messages. Before requesting an attachment, a bounded read-only
preflight opens/reuses `/web/chat/index` in the authenticated Chrome context
and checks the `geek-list` runtime required by the upstream CLI. It never
invokes the exchange action. The write attempt is recorded before dispatch;
explicit pre-dispatch failures remain retryable, while uncertain outcomes
require a separate, clearly warned user confirmation before another attempt.
Request feedback stays visible independently of tracker refreshes.
Confirmed requests persist as “已索取” with their confirmation time, remain
visible after reload/restart, and disable duplicate requests. If the CLI misses
its WebSocket acknowledgement, the connector only reads the same conversation:
an exact outbound resume-request message newer than the saved baseline confirms
delivery. Participant UIDs determine direction because BOSS can mark both
endpoints `source=0`. Background polling also reconciles delayed acknowledgements;
it never resends. Unconfirmed responses are not labelled successful. The nullable
`confirmed_at` tracker column preserves confirmation independently of receipt.
Receiving an attachment retains the original request time alongside receipt time.
A private SQLite tracker under
`runtime/resume-requests.sqlite3` checks pending conversations every two minutes,
including after a connector restart; only an inbound attachment message newer
than the request is marked received. The administrator can download that
specific attachment through the authenticated bridge. The file is held briefly
under the private runtime directory and removed after delivery.

### Chinese text on the remote desktop

Install the official distribution CJK fonts before starting Chrome:

```sh
apt-get install -y --no-install-recommends fonts-noto-cjk
fc-cache -f
runuser -u heyi-boss -- fc-match 'sans-serif:lang=zh-cn'
```

The match should be Noto Sans CJK SC. If Chrome was already running without
these fonts, a page reload may retain the old fallback cache. Restart Chrome
with the same dedicated profile and restore its tabs; never remove the profile
or clear cookies to repair font rendering. Confirm Chinese text visually.

### Production release

`release-resume-flow.sh` deploys the resume identity fallback and request
preflight together. It preserves Chrome and business workers, defers during
scan/write operations, and changes only HR assets, the connector, and the
HR proxy read timeout (240 seconds covers preparation plus the bounded CLI
request). Test the live preparation and component readiness without invoking
`handleExChange`; a real outbound request requires the user's confirmation.
An all-masked resume name never overrides the selected candidate's visible
list identity. If BOSS has not exposed a complete name, the UI states that
instead of guessing missing characters.

The September 28 disconnect repair uses `release-disconnect.sh`, staging
`connector.py`, `hr-workspace.js`, `test_connector.py`, and
`compose.release.yaml` with their manifest. It defers if a login/sync child
is active and preserves the Chrome process. Validate disconnect/reconnect
using synthetic fixtures and the isolated test suite; do not click logout
on a live account just to smoke-test a deployment.

The September 28 resume/theme repair uses `release-resume-theme.sh`. Stage
`connector.py`, `hr-workspace.css`, `test_connector.py`, and
`compose.release.yaml` with their SHA-256 manifest. The script checks the
previous visual release, keeps its live HTML and other assets, and changes
only the HR stylesheet and its cache versions along with the connector.
The stylesheet uses `resend-theme.css` tokens for both light and dark modes.
Release scripts contain version-specific baseline hashes; do not reuse them
unchanged for later deployments.

Resume access credentials are opaque strings, not short entity IDs. Observed
recommendations contain 744–748-character `securityId` values and `~` in some
encrypted IDs. Preserve these exactly as single CLI arguments. The connector
allows up to 4096 characters for the credential, keeps entity IDs at 180,
and bounds HTTP queries at 8192 characters. Tests cover the real length/format,
HTTP query round trips, and rejection of malformed or option-like arguments.
For live verification, read a current recommended candidate's online resume
and record only success and section counts, never the credential or resume
content. Viewing an online resume must not send a greeting or request.

For the September 28 login-sync fix, use `release-login-sync.sh` with its staged
manifest and documented baseline checks. It snapshots the running frontend,
updates the connector and HR script together, and verifies that the running
Chrome session and business containers survive the release.

Use the **running frontend** as the source of truth. Capture its live
`app.js`, `index.html`, and `default.conf`, patch just the HR entries with
`patch-live-frontend.py`, then stage these three files along with
`hr-workspace.js`, `hr-workspace.css`, `connector.py`, the systemd unit,
`Dockerfile.release`, `compose.release.yaml`, and `release.sh`. Write a
`manifest.sha256` for all files used by the image and connector, including
`heyi-boss-services.sudoers`. Run
`release.sh` on the host from the release directory. It checks for drift,
builds a frontend image from the exact production base image, tests Nginx,
restarts the connector, and replaces only the frontend via a single Compose
release. If a step fails, it restores the prior connector and captured frontend
image. It verifies that the business API and image processor containers were
not recreated. Do not build the production Java package from a local tree with
unrelated uncommitted changes.
