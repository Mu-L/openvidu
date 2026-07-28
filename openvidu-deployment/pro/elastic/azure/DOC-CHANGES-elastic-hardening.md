# Elastic / Azure hardening — documentation changes

Target template: `pro/elastic/azure/cf-openvidu-elastic.bicep` (and its recompiled
`cf-openvidu-elastic.json`).

This note lists the changes that the OpenVidu Elastic Azure hardening introduces in the
public documentation. It is written for the **openvidu.io** repository, branch **`next`**.

**No deployment parameters or template outputs changed.** The set of Marketplace / ARM
parameters and the `createUiDefinition.json` fields are identical to the previous version,
so parameter tables, screenshots and step-by-step instructions in the docs do **not** need
any edit. The only user-visible change is the expected **deployment time**.

## 1. Deployment time figure

File: `docs/docs/self-hosting/elastic/azure/install.md`

The template now installs the media node **in parallel** with the master node startup
instead of serially after it:

- Gate 1 (media `install.sh`) releases the media-node installation as soon as the master
  has published its secrets (`ALL-SECRETS-GENERATED == "true"`), so the heavy media-node
  work (apt, Azure CLI, Docker images, OpenVidu install) runs while the master is still
  starting.
- Gate 2 (media user-data) blocks only the final `systemctl start openvidu` until the
  master is fully healthy (`FINISH-MASTER-NODE == "true"`), which is set only after the
  master passes its `/health/caddy` check.

As a result the end-to-end deployment is expected to be roughly **30-40% faster**. The
documentation currently states "7 to 12 minutes" in two places; both must be updated to the
range measured with ov-cloud-tester.

### Occurrence 1 — around line 95 ("Deploying the stack")

Before:

> If correct, click on _"Create"_ to start the deployment process (which will take about **7 to 12 minutes**).

After (fill the placeholder with the ov-cloud-tester measurement):

> If correct, click on _"Create"_ to start the deployment process (which will take about **X to Y minutes**).

### Occurrence 2 — around line 149 ("Configuration and administration")

Before:

> When your Azure stack reaches the **`Succeeded`** status, it means that all resources have been created. You will need to wait about **7 to 12 minutes** for the instances to install OpenVidu.

After (fill the placeholder with the ov-cloud-tester measurement):

> When your Azure stack reaches the **`Succeeded`** status, it means that all resources have been created. You will need to wait about **X to Y minutes** for the instances to install OpenVidu.

> [!NOTE]
> `X to Y minutes` is a placeholder. Replace it with the measured range once the hardened
> template has been validated with ov-cloud-tester. Both occurrences must use the same
> figure.

## 2. Template hardening (reviewer context, no doc text change)

These changes improve reliability and security but do not alter any documented parameter,
output or procedure:

- Bounded waits everywhere (Key Vault availability, application health, media gates) instead
  of unbounded `while true` loops, so a stuck boot fails fast instead of hanging.
- Master health is now verified (`check_app_ready.sh`, capped at 1200 s) **before** the
  `FINISH-MASTER-NODE` signal is published, so media nodes never start against an unhealthy
  master.
- Robust installer download (`curl --retry 8 --retry-all-errors` to a file plus a non-empty
  check) replaces piping a process substitution straight into `sh`, which could silently run
  an empty script on a transient network failure.
- Blob-storage configuration retries `az login` + storage-key fetch for up to 300 s to absorb
  Contributor role-assignment propagation delay.
- Boot scripts no longer run under `bash -x`, so secret values are no longer traced into the
  VM boot logs.

No action is required in the docs for section 2; it is included only so the documentation
reviewer understands why the deployment-time figure changes.
