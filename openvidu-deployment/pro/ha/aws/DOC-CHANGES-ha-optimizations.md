# Documentation changes for `ha-optimizations` (AWS HA deployment)

Instructions for the Claude working on the docs repo
`/home/sergio/Escritorio/openvidu/openvidu.io` (branch `next`, already up to date).

These instructions derive from the changes applied in
`openvidu-deployment/pro/ha/aws/cf-openvidu-ha.yaml` on the `ha-optimizations` branch.
Functional summary of what changed in the deployment:

- The 4 master EC2 instances are now created **in parallel** (the `DependsOn` chain
  MasterNode2→WaitCondition1, 3→2, 4→3 was removed; coordination stays data-driven).
- Each master now publishes its private IP to its **own** SSM Parameter Store parameter
  (`/openvidu/<stack-name>/master-node-N-private-ip`), an atomic per-key write, instead
  of a racy read-modify-write into the single shared Secrets Manager JSON. The 4
  `MASTER_NODE_*_PRIVATE_IP` keys were removed from that JSON.
- Media Nodes now install (Docker + image pulls) **in parallel** with the masters. They
  are no longer gated behind `MasterNodesWaitCondition4`; instead they poll for the
  shared secrets and the master IPs, and wait for at least one healthy master before
  starting. The Network Load Balancer is likewise no longer gated behind
  `MasterNodesWaitCondition4`.
- Hardening: the previously unbounded "wait for the 4 master IPs" loop is now bounded
  (30 min), and the installer download is retried and validated
  (`curl --retry 8 ... -o file` + non-empty check) instead of the fragile
  `sh <(curl ...)`.

SUMMARY: **no doc content change is strictly required.** The AWS HA docs publish no
deployment-time figure and describe no internals that changed. The only *optional*
edits are (a) adding a deployment-time figure and (c.1) regenerating one screenshot.
Everything else (public parameters, the single Output, screenshots, other clouds, other
deployment types, on-node config files) stays the same. Read this file in full before
touching anything.

---

## (a) Deployment-time figure — OPTIONAL (none exists today)

Unlike the GCP and Azure HA `install.md` files, the **AWS HA `install.md` currently
publishes NO deployment-time figure at all** (no "wait about N minutes" sentence). So
there is nothing to update here. This is only a suggestion in case you want to add one
now that the deployment is faster (masters in parallel + media install overlapping the
masters' install + no IP handshake through Secrets Manager).

File: `docs/docs/self-hosting/ha/aws/install.md`

Two natural, OPTIONAL insertion points (locate by literal text; line numbers approximate):

### Option 1 — end of "## Deploying the stack" (~line 155)

Current literal text (the paragraph that ends the section):

```
When you are ready with your CloudFormation parameters, just click on _"Next"_, specify in _"Stack failure options"_ the option _"Preserve successfully provisioned resources"_ to be able to troubleshoot the deployment in case of error, click on _"Next"_ again, and finally _"Submit"_.
```

Proposed (append one sentence; fill in `X`/`Y` from `ov-cloud-tester`):

```
When you are ready with your CloudFormation parameters, just click on _"Next"_, specify in _"Stack failure options"_ the option _"Preserve successfully provisioned resources"_ to be able to troubleshoot the deployment in case of error, click on _"Next"_ again, and finally _"Submit"_. The stack will take about X to Y minutes to create all resources.
```

### Option 2 — "## Configuration and administration" (~line 190)

Current literal text:

```
When your CloudFormation stack reaches the **`CREATE_COMPLETE`** status, your OpenVidu High Availability deployment is ready to use. You can check the [Administration](./admin.md) section to learn how to manage your deployment.
```

Proposed (fill in `X`/`Y` from `ov-cloud-tester`):

```
When your CloudFormation stack reaches the **`CREATE_COMPLETE`** status (about X to Y minutes), your OpenVidu High Availability deployment is ready to use. You can check the [Administration](./admin.md) section to learn how to manage your deployment.
```

> Placeholder to fill in: `X to Y minutes — fill with the ov-cloud-tester measurement`.
> If you add a figure in BOTH options, keep them consistent. Do NOT invent a number —
> leave the `X to Y` placeholder until the real measurement is available.
> This is entirely optional; skipping section (a) leaves the docs correct.

---

## (b) Public template parameters / outputs: NO CHANGES — confirmation

Do not touch parameter tables or screenshots in the parameters section.

Reason: **no CloudFormation `Parameters` were added, removed or renamed, and the single
`Outputs` entry (`ServicesAndCredentials`) is unchanged.** The new per-master IPs live in
SSM Parameter Store parameters that are created internally by the template; they are not
template parameters and require no user input. Therefore:

- The parameter sections of `docs/docs/self-hosting/ha/aws/install.md`
  ("## CloudFormation Parameters" and its subsections, ~lines 52–137) **stay the same**.
- There are no new fields the user must fill in the CloudFormation form.
- The parameter form screenshots **do not change**.
- The `ServicesAndCredentials` output and the "Configure your application to use the
  deployment" section (~lines 163–180) are unaffected.

---

## (c) Other statements in the AWS HA docs that may become outdated

The AWS HA documentation (`docs/docs/self-hosting/ha/aws/*.md`) and the shared AWS
includes (`shared/self-hosting/aws/*.md`) were reviewed against the internal changes.
Conclusions:

### c.1 — Secrets Manager contents: `MASTER_NODE_{1..4}_PRIVATE_IP` removed from the JSON

The 4 keys `MASTER_NODE_1_PRIVATE_IP` … `MASTER_NODE_4_PRIVATE_IP` were removed from the
`OpenViduSharedInfo` Secrets Manager JSON (the masters' IPs now live in SSM Parameter
Store). The AWS HA docs **do not mention these keys by name in any text**, so there is no
prose to fix.

- `install.md` (~line 165–177) and `admin.md` (~line 226–238) describe the
  `ServicesAndCredentials` secret only generically ("contains all URLs and credentials",
  "get the JSON with all the information"). The "most relevant" values are listed via the
  shared includes `shared/self-hosting/aws/credentials-general.md` and
  `credentials-v2compatibility.md`, neither of which lists the IP keys. No change.
- Only possible impact: the secret screenshot
  `assets/images/platform/self-hosting/ha/aws/2-secrets.png` (shown in `install.md`)
  COULD still display the 4 removed IP keys. This is purely cosmetic. OPTIONAL action:
  if/when the screenshot is regenerated, do it against an `ha-optimizations` deployment.
  This is NOT blocking.

### c.2 — `upgrade.md`: DO NOT TOUCH

`docs/docs/self-hosting/ha/aws/upgrade.md` (~lines 82–92) documents
`/usr/local/bin/store_secret.sh save OPENVIDU_VERSION "<VERSION>"` and the
`OPENVIDU_VERSION` Secrets Manager secret. **Neither `store_secret.sh` nor the
`OPENVIDU_VERSION` secret was changed** by this work. No changes required.

### c.3 — `backup-and-restore.md`: DO NOT TOUCH

`docs/docs/self-hosting/how-to-guides/backup-and-restore.md` (~lines 676–692) documents
`MASTER_NODE_1_PRIVATE_IP` … `MASTER_NODE_4_PRIVATE_IP` inside the **on-node**
configuration file `/opt/openvidu/config/node/master-node.env`.

IMPORTANT: **these are NOT the removed Secrets Manager keys.** They are config-file
variables that the installer still writes from the `--master-node-private-ip-list` flag
(which is still passed — now built from the SSM parameters instead of the shared JSON).
That on-node flow is unchanged. **Do not modify `backup-and-restore.md`.**

### c.4 — Master boot order / parallelization: not documented

The AWS HA docs never state that master nodes are created sequentially / "one by one",
nor do they describe the `DependsOn` chain, `MasterNodesWaitCondition*`, or the fact that
the Load Balancer / Media Nodes used to wait for `MasterNodesWaitCondition4`. Removing
that chain and un-gating the NLB and Media Nodes changes nothing user-facing. No text to
update.

### c.5 — Internal coordination (`ALL_SECRETS_GENERATED`, SSM IP handshake, health gates): not documented

The master-1-as-leader secret generation, the `ALL_SECRETS_GENERATED` flag, the new SSM
per-master IP handshake, the bounded wait loops, and the media "wait for a healthy
master" gate are all internal and not publicly documented. Nothing user-facing changes.
No text to update. `admin.md` and `index.md` were also reviewed: no references to the
changed internals. No changes required.

---

## (d) New resources created by the stack: 4 SSM parameters

The stack now creates **4 SSM Parameter Store parameters** as CloudFormation resources:

```
/openvidu/<stack-name>/master-node-1-private-ip
/openvidu/<stack-name>/master-node-2-private-ip
/openvidu/<stack-name>/master-node-3-private-ip
/openvidu/<stack-name>/master-node-4-private-ip
```

They are visible in the AWS Systems Manager → Parameter Store console and are deleted
with the stack. They are harmless and require no user interaction.

The AWS HA docs do **not** enumerate the resources the stack creates anywhere, so there
is nothing to update. This note exists only so that, if such an enumeration is ever added
(or if a reader asks about unfamiliar parameters in the console), the 4 parameters are
accounted for and expected.

---

## Implementation checklist

- [ ] (OPTIONAL, section a) In `docs/docs/self-hosting/ha/aws/install.md`, add a
      deployment-time sentence with the `X to Y minutes` placeholder at one of the two
      insertion points; measure with `ov-cloud-tester` on `ha-optimizations` and fill in
      `X`/`Y`. Skipping this leaves the docs correct.
- [ ] Confirm NO parameter tables or screenshots were touched (section b).
- [ ] Confirm `upgrade.md` (`store_secret.sh` / `OPENVIDU_VERSION`) was NOT touched (c.2).
- [ ] Confirm `backup-and-restore.md` (on-node `master-node.env` IP vars) was NOT touched
      (c.3).
- [ ] (OPTIONAL, section c.1) Regenerate `2-secrets.png` against an `ha-optimizations`
      deployment if you want it to stop showing the 4 removed `MASTER_NODE_*_PRIVATE_IP`
      keys. Not blocking.
- [ ] No action needed for the 4 new SSM parameters unless a resource enumeration is
      added to the docs (section d).
