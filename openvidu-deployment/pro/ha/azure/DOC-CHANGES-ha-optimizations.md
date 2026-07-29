# HA / Azure optimizations — documentation changes

Target template: `pro/ha/azure/cf-openvidu-ha.bicep` (and its recompiled
`cf-openvidu-ha.json`).

> [!NOTE]
> The full HA deployment-time optimization notes live on the `ha-optimizations` branch
> (they were intentionally excluded from the merge into `master`). This copy carries only
> the section below, added on the `elastic-hardening` branch.

## Managed identities for the Key Vault dependency inversion

The four master VMs and the media-node scale set no longer use system-assigned identities.
The template now creates two user-assigned identities up front,
`<stackName>-master-identity` and `<stackName>-media-identity`, and the Key Vault access
policies reference those instead of the machine identities.

- **No public parameters changed.** The ARM / Marketplace parameter set and every
  `createUiDefinition.json` field are identical to the previous version, so parameter
  tables, screenshots and step-by-step instructions in the docs need no edit. The identity
  client IDs travel through internal template variables only.
- **Two extra resources appear in the resource group** (`<stackName>-master-identity` and
  `<stackName>-media-identity`, type *Managed Identity*). This is cosmetic: if the docs show
  a screenshot or a list of the created resources, it will now include these two entries.
  The permission split is preserved — the master identity keeps `get`/`set`/`list` on
  secrets, the media identity only `get`.
- **No doc text change:** the boot scripts now select the identity explicitly at login
  (`az login --identity --client-id <id>`), and the pinned Azure CLI moves from 2.87.0 to
  2.88.0, which is the version that guarantees `--client-id` support.
- **Deployment may complete roughly 1-3 minutes faster.** The Key Vault used to be created
  after all five compute resources because its inline access policies referenced their
  system-assigned identities; it now deploys in parallel with them, so the bounded
  boot-time wait for Key Vault availability in the node scripts is essentially eliminated.
  Do **not** publish a new figure from this estimate: re-measure with ov-cloud-tester before
  touching any deployment-time number already in the documentation.
