# Doc changes for `elastic-hardening` (OpenVidu Elastic, GCP)

Target docs repo: `openvidu.io`, branch `next`
Docs affected: `docs/docs/self-hosting/elastic/gcp/*.md`

## Context

Hardening of `pro/elastic/gcp/tf-gpc-openvidu-elastic.tf` (deployment repo, branch
`elastic-hardening`). Almost all changes are internal robustness improvements to
the Terraform bootstrap scripts and resource graph: explicit `depends_on` on the
enabled Google APIs, robust installer download (download-to-file with retries
instead of `sh <(curl ...)`), bounded readiness/secret gates on master and media
nodes, a fixed and de-duplicated install-completion marker so reboots take the
restart path, a reboot guard on media nodes, and `set +x` around secret-handling
blocks so credentials are not written to the serial console.

One change adds new infrastructure behavior (see "Optional note for `admin.md`").

## Impact on the docs: essentially none

No user-facing surface changes. Specifically:

- **Parameters**: unchanged. No input variable added, removed, or renamed. The
  parameters table in `install.md` needs no structural edit (but see the stale
  default-value fix below).
- **Outputs**: unchanged. `output.tf` is untouched; the Outputs / connection
  section in `install.md` is identical.
- **Install / upgrade flow**: unchanged (`install.md`, `upgrade.md`). No
  screenshots need to be retaken.

### Deployment time ("7 to 12 minutes") — REMAINS VALID, no edit

`install.md` states around lines 236 and 295:

> Wait around 7 to 12 minutes for the nodes to install OpenVidu.

The hardening changes are robustness-only and do not lengthen the happy-path
install. The bounded waits are failure upper bounds (master readiness 240x5s =
20 min; media secret wait 180x10s = 30 min), not expected durations — on a
healthy deploy the gates pass as soon as the node is ready, exactly as before.
Leave both sentences unchanged.

## Required fix: stale default instance types

Independent of the hardening, `install.md` documents default instance types that
no longer match `variables.tf`. The Terraform defaults are `e2-standard-4` for
both node types (`variables.tf`: `masterNodeInstanceType`, `mediaNodeInstanceType`),
but the parameters table still lists `e2-standard-2`.

- `install.md` ~line 179 (row `masterNodeInstanceType`):
  `<td>"e2-standard-2"</td>`  ->  `<td>"e2-standard-4"</td>`
- `install.md` ~line 185 (row `mediaNodeInstanceType`):
  `<td>"e2-standard-2"</td>`  ->  `<td>"e2-standard-4"</td>`

## Optional note for `admin.md` (media node auto-healing)

The hardening adds a Managed Instance Group **auto-healing policy** for Media
Nodes: a regional TCP health check on port `7880` (check interval 30s, timeout
10s, healthy threshold 2, unhealthy threshold 5) with `initial_delay_sec = 600`.
The MIG now recreates a Media Node whose LiveKit HTTP port stops accepting TCP
connections. This is new observable behavior, so it is worth a short note.

Suggested placement: the "Media Nodes Autoscaling Configuration" section (around
line 118) or a new subsection just after it, e.g.:

> ### Media Node auto-healing
>
> Media Nodes are additionally protected by an auto-healing policy on the Managed
> Instance Group. A TCP health check probes each Media Node on port 7880; if a
> node stops accepting connections it is recreated automatically. The check is
> intentionally conservative (TCP-only, with a 10-minute initial delay so a node
> can finish installing before it becomes eligible for health checks) because
> recreating a Media Node terminates the live WebRTC sessions running on it.

Include this only if change 7 (auto-healing) ships in the release the docs
describe. It is a behavior addition, not a parameter or output change.
