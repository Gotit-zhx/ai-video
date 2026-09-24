# Linux CDP connector installation

The production connector is isolated from the Hoyee containers. It uses
`heyi-boss`, `/opt/heyi/boss-agent/accounts/pilot`, Xvfb display `:98`, and
Chrome CDP on `127.0.0.1:9222`. The Python virtual environment pins
`boss-agent-cli==2.0.0`.

Install the startup scripts and `boss-pilot` wrapper under
`/opt/heyi/boss-agent/bin/`, and the service files under
`/etc/systemd/system/`. The connector stays enabled; Chrome and Xvfb are
disabled at boot and start only for QR enrollment. They stop on logout or
failed recruiter verification. A narrow sudoers rule permits the unprivileged
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
account; never use `--force` to switch another person's session.
`doctor` also warns that patchright's bundled Chromium is absent; the selected
CDP path uses the separately installed Google Chrome. A direct patchright CDP
connection to this Chrome was verified without an account login.

The platform administrator `13800000000` opens the recruiter workspace and
the QR appears automatically when no verified recruiter session exists. The
connector sends only a cropped QR image from the BOSS login tab to that
authenticated administrator. When a code expires, the connector uses the
login page's own refresh control. Reading the QR never reloads the page or
resets a scan awaiting phone confirmation. The account owner
scans with the BOSS app and confirms on their phone. A successful QR scan is
*not* enough to enable reads: the connector verifies healthy credentials and
that the recruiter-only jobs endpoint succeeds. An explicit role mismatch is
rejected and cleared. An inconclusive jobs response keeps credentials but blocks
all recruiter reads; the UI shows the upstream error code and offers a separate
verification retry without making the user scan again. Logout clears
both CLI storage and dedicated Chrome cookies, then stops Chrome and Xvfb.
Recruiting reads remain gated by `/api/v1/auth/me` on every request. The sole
enabled write is an explicit attachment-resume request for a specified
conversation, after browser confirmation. QR login and background polling
never send messages. A private SQLite tracker under
`runtime/resume-requests.sqlite3` checks pending conversations every two minutes,
including after a connector restart; only an inbound attachment message newer
than the request is marked received. The administrator can download that
specific attachment through the authenticated bridge. The file is held briefly
under the private runtime directory and removed after delivery.

### Production release

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
