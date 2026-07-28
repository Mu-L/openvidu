# Doc changes for `elastic-hardening` (OpenVidu Elastic, AWS)

Target docs repo: `openvidu.io`, branch `next`
Docs affected: `docs/docs/self-hosting/elastic/aws/*.md`

## Context

Hardening of `pro/elastic/aws/cf-openvidu-elastic.yaml` (deployment repo, branch
`elastic-hardening`). All changes are internal robustness improvements to the
CloudFormation template's bootstrap scripts: robust installer download, bounded
readiness gates on master and media nodes, an idempotency guard around master
secret generation, and a wider `WaitCondition` timeout (`PT10M` -> `PT20M`).

## Impact on the docs: essentially none

No user-facing surface changes. Specifically:

- **Parameters**: unchanged. No parameter added, removed, or renamed
  (`install.md#cloudformation-parameters` needs no edit).
- **Outputs**: unchanged. `ServicesAndCredentials` and the rest of the
  Outputs section are identical (`install.md`).
- **Times / durations**: no documented time changes. The `WaitCondition`
  timeout is an internal upper bound for failure detection, not a documented
  deployment duration; success still signals as soon as the node is healthy.
  `install.md` does not state a fixed deployment time, so nothing to update.
- **Install / upgrade flow**: unchanged (`install.md`, `upgrade.md`).

No screenshots need to be retaken.

## Optional precision for `admin.md`

Section "Administration and configuration" -> "Changing Configuration through
AWS Secrets". After editing the secret and rebooting, the doc currently states
(around line 237):

> Changes will be applied automatically in all the nodes of your OpenVidu Elastic deployment.

This is imprecise for Elastic. Only the **master node** re-reads the secret on
reboot (its `@reboot` cron runs `update_config_from_secret.sh`). **Media nodes**
read the shared secret only at launch, so they do not pick up secret changes on
reboot. Suggested replacement:

> Rebooting the Master Node re-applies the configuration on the master. Media
> Nodes read the shared configuration only when they start, so to propagate
> changes that affect Media Nodes you must relaunch them (terminate the running
> Media Nodes; the Auto Scaling Group launches replacements that read the
> updated configuration).

This is a pre-existing behavior clarification, not a consequence of the
hardening changes; include it only if a docs pass is being made anyway.
