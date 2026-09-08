# FluentBit Configuration Files

These files are **copied from** `data-index/scripts/fluentbit/postgresql/` for MODE 1 deployment.

**Note:** FluentBit is temporary - MODE 1 will migrate to Vector (see issue #63).

## Files

- `fluent-bit.conf` - Main FluentBit configuration
- `parsers.conf` - CRI parser for Kubernetes logs
- `flatten-event.lua` - Lua script to route workflow vs task events

## Source

**Authoritative source:** `data-index/scripts/fluentbit/postgresql/`

These files are embedded into the Helm chart ConfigMap for self-contained deployment.
