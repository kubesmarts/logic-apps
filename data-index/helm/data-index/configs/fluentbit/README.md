# FluentBit Configuration Files

> **DEPRECATED (ADR-0001).** MODE 1 now uses Vector
> (`data-index/helm/data-index/configs/vector/vector-mode1-postgresql.yaml`,
> `data-index/collectors/vector/mode1-postgresql/vector.yaml`). These files are
> retained for one release so existing deployments can opt back in with
> `--set fluentbit.enabled=true --set vector.enabled=false`. Removal target: next release.

These files are **copied from** `data-index/scripts/fluentbit/postgresql/` for MODE 1 deployment.

## Files

- `fluent-bit.conf` - Main FluentBit configuration
- `parsers.conf` - CRI parser for Kubernetes logs
- `flatten-event.lua` - Lua script to route workflow vs task events

## Source

**Authoritative source:** `data-index/scripts/fluentbit/postgresql/`

These files are embedded into the Helm chart ConfigMap for self-contained deployment.
