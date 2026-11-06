# Recruitment session investigation — 2026-09-28

The account owner reported repeated sign-outs. Automatic connector requests were
stopped immediately, retaining the server Chrome process, profile and credentials.
No real candidate messages or new login were used to reproduce this incident.

## Evidence and limits

Reviewed the current upstream `docs/platform-risk.md` and the installed
`boss-agent-cli==2.0.0` source separately. The repository's current master is not
assumed to be identical to the installed wheel.

Upstream reference:
`https://github.com/can4hou6joeng4/boss-agent-cli/blob/master/docs/platform-risk.md`

Also reviewed `docs/research/platforms/README.md`, `zhipin.md` and the platform
abstraction SOP. `zhipin.md` identifies itself as a historical research baseline;
its older blocked-capability descriptions must not override the installed
schema. A credential-free schema read confirmed the installed recruiter
commands and current `open_capabilities` policy. That does not remove request
budgets or stopping requirements. The connector uses the upstream recruiter
commands, not candidate-side commands or copied third-party scrapers.

The installed recruiter client sends jobs, friend lists, friend details and
chat history through `_BaseHttpClient._request` using stored credentials.
Selecting a CDP endpoint does not move those methods into browser transport.
Explicit QR enrollment and recruiter chat actions use the dedicated CDP browser.

The installed `_BaseHttpClient._request` handles HTTP 403 and a body containing
“安全验证” by calling `AuthManager.force_refresh` and retrying. With the configured
`stored-cookie` policy, refresh can call `refresh_stoken_via_cdp`, which opens a
temporary page in the existing context. The wrapper excludes headless fallback
but did not exclude this refresh path.

The SDK's default per-client delay is 1.5–3 seconds. Repeated short-lived CLI
processes do not share that client's throttle, so per-process pacing is not
enough for a continuously running web connector. The added budget is shared
across those processes.

The connector had a 120-second background resume scan, up to 20 conversations
per sweep. Most risk/auth failures did not pause the whole worker. Only the IP
restriction had a durable hold. Idle polling/status could also start recruiter
verification, and the frontend could start login on an auth error.

These are verified implementation gaps. Existing diagnostic logs did not retain
the failed response codes/timestamps needed to establish that any one request
revoked the phone session. Local status was `degraded`; this is not proof of an
IP ban or proof of the reason for a phone sign-out. The account owner reported
the symptom; no platform-side session revocation event was available.

The local disconnect implementation was reviewed: it removes only the dedicated
server's saved session and BOSS cookies, after stopping its browser. It does not
call the platform's logout/session-revocation endpoint.

## Corrected behavior

* `guarded_cli.py` wraps the pinned SDK without editing site-packages. All
  SDK HTTP requests make one attempt, do not follow login/security redirects,
  and cannot implicitly refresh credentials. Browser requests keep upstream
  source selection and risk handling. Numeric risk/rate-limit codes also stop.
* HTTP and SDK browser reads share a private SQLite budget across processes:
  six-second spacing, at most 120 requests in the trailing hour, and a bounded
  queue. This is our conservative operational limit, **not** a platform promise
  that a particular request frequency is safe.
* Risk, auth, security-check, rate-limit and exhausted-budget failures persist a
  hold. Remaining conversations, queued reads and subsequent polls are blocked.
  Restarting the connector or viewing status cannot release it.
* Only an explicit connect/synchronize action can run a bounded verification.
  One successful recruiter jobs check releases the hold. Failed checks do not
  clear credentials or repeat verification automatically.
* Resume receipt checks run every five minutes, up to three conversations per
  sweep, with a per-conversation cooldown and a seven-day window. Three transport
  failures pause the workflow. Existing requests and uncertain delivery
  checkpoints remain intact; no resume request is automatically resent.
* Logs record only operation type, fixed error code, duration and time. They do
  not contain candidate IDs, names, phone numbers, message text, tokens or URLs.
* Passive page loads do not start a new QR login. Existing explicit login,
  synchronization, disconnect and reset controls remain available.

The pending applicant-profile feature was not deployed during this incident.
It must use the same guard and stop propagation before release; it is not an
exception to the account request budget.

## Verification and recovery

Hermetic tests exercise the installed SDK with synthetic HTTP responses for
success, 403, security HTML, redirects, auth expiry, risk and rate limiting.
Connector tests verify durable holds, batch termination, checkpoint preservation,
bounded polling and explicit recovery. No live platform access is needed.

`release-session-safety.sh` deploys the connector/wrapper/frontend together,
validates the previous production file hashes, and retains a manual-review hold.
It preserves Chrome's PID and the commerce worker container IDs. Failure rolls
back the files/frontend and leaves the connector stopped.

The account owner should inspect the account and safety prompts through the
official BOSS app/page before explicitly synchronizing the retained browser.
This fix bounds our requests; it cannot guarantee the platform will never revoke
a session. Future SDK upgrades require source review and these same offline
tests before changing the pinned version.
