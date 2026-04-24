# FluentBit Parser Configuration for Kubernetes

**Date:** 2026-04-23  
**Critical:** Container runtime detection required

## Overview

FluentBit must use the **correct parser** based on the Kubernetes cluster's **container runtime**. Using the wrong parser will cause FluentBit to fail silently - it will tail the log files but won't parse any events.

## Container Runtime Detection

### Check Your Cluster's Runtime

```bash
# For KIND clusters
docker exec <cluster-name>-control-plane crictl version

# For real Kubernetes clusters
kubectl get nodes -o wide
# Look at CONTAINER-RUNTIME column

# Or SSH to node and check
crictl version  # If using containerd/CRI-O
docker version  # If using Docker
```

## Parser Configuration

### CRI Runtime (containerd, CRI-O) - **MOST COMMON**

**Used by:** KIND, GKE, EKS (recent versions), AKS, most modern Kubernetes

**Log Format:**
```
2026-04-23T23:07:15.123456789Z stdout F {"eventType":"io.serverlessworkflow.workflow.started.v1",...}
└────────timestamp──────────┘  └stream┘ └logtag┘ └──────────────message──────────────────────────┘
```

**FluentBit Configuration:**

`fluent-bit.conf`:
```conf
[INPUT]
    Name              tail
    Path              /var/log/containers/*_workflows_*.log
    Parser            cri     ← Use CRI parser
    Tag               kube.*
```

`parsers.conf`:
```conf
[PARSER]
    Name   cri
    Format regex
    Regex  ^(?<time>[^ ]+) (?<stream>stdout|stderr) (?<logtag>[^ ]*) (?<log>.*)$
    Time_Key time
    Time_Format %Y-%m-%dT%H:%M:%S.%LZ
    Time_Keep On
```

### Docker Runtime - **LEGACY**

**Used by:** Older Kubernetes clusters, Docker Desktop Kubernetes

**Log Format:**
```json
{"log":"{\"eventType\":\"io.serverlessworkflow.workflow.started.v1\",...}\n","stream":"stdout","time":"2026-04-23T23:07:15.123456789Z"}
```

**FluentBit Configuration:**

`fluent-bit.conf`:
```conf
[INPUT]
    Name              tail
    Path              /var/log/containers/*_workflows_*.log
    Parser            docker  ← Use Docker parser
    Tag               kube.*
```

`parsers.conf`:
```conf
[PARSER]
    Name   docker
    Format json
    Time_Key time
    Time_Format %Y-%m-%dT%H:%M:%S.%LZ
    Time_Keep On
    Decode_Field_As escaped log
```

## Common Issues

### Issue 1: Events Not Reaching PostgreSQL

**Symptoms:**
- FluentBit logs show no errors
- FluentBit is tailing log files (inotify_fs_add messages)
- No events appear in raw PostgreSQL tables
- No processing activity in FluentBit logs

**Diagnosis:**
```bash
# Check FluentBit logs for parser errors
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep -i "parser\|error"

# Expected error if using wrong parser:
# [error] [input:tail:tail.0] parser 'docker' is not registered
# (when using docker parser on CRI runtime)
```

**Solution:**
1. Detect container runtime (see above)
2. Update `fluent-bit.conf` INPUT section with correct parser
3. Ensure matching parser exists in `parsers.conf`
4. Regenerate ConfigMap and restart FluentBit pods

### Issue 2: Parser Not Registered

**Error:**
```
[error] [input:tail:tail.0] parser 'cri' is not registered
```

**Solution:**
Add the parser definition to `parsers.conf`:

```conf
[PARSER]
    Name   cri
    Format regex
    Regex  ^(?<time>[^ ]+) (?<stream>stdout|stderr) (?<logtag>[^ ]*) (?<log>.*)$
    Time_Key time
    Time_Format %Y-%m-%dT%H:%M:%S.%LZ
    Time_Keep On
```

### Issue 3: Nested JSON Not Extracted

After CRI/Docker parser extracts the `log` field, you need a second parser to extract the JSON event:

```conf
# After parsing CRI/Docker format, parse the nested JSON
[FILTER]
    Name              parser
    Match             kube.*
    Key_Name          log        ← Parse the 'log' field
    Parser            json       ← Use JSON parser
    Reserve_Data      On
    Preserve_Key      Off
```

## Verification

### Verify Parser is Working

```bash
# Enable debug logging
# In fluent-bit.conf:
# [SERVICE]
#     Log_Level    debug

# Restart FluentBit and trigger a workflow
kubectl delete pod -n logging -l app=workflows-fluent-bit-mode1

# Trigger workflow
curl -X POST http://localhost:8082/test-workflows/simple-set \
  -H "Content-Type: application/json" \
  -d '{}'

# Check FluentBit is processing events
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 --tail=100 | grep -E "stdout|eventType"

# Check PostgreSQL received events
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "SELECT COUNT(*) FROM workflow_events_raw;"
```

Expected output: Count should increase after triggering workflows.

## Multi-Runtime Support (Advanced)

If your cluster has mixed runtimes (unlikely), you can use both parsers:

```conf
[PARSER]
    Name        cri
    Format      regex
    Regex       ^(?<time>[^ ]+) (?<stream>stdout|stderr) (?<logtag>[^ ]*) (?<log>.*)$
    Time_Key    time
    Time_Format %Y-%m-%dT%H:%M:%S.%LZ

[PARSER]
    Name        docker
    Format      json
    Time_Key    time
    Time_Format %Y-%m-%dT%H:%M:%S.%LZ
    Decode_Field_As escaped log

[INPUT]
    Name        tail
    Path        /var/log/containers/*_workflows_*.log
    Multiline   On
    Parser_Firstline cri
    Parser_1    docker
```

**Warning:** This is rarely needed and adds complexity. Detect your runtime and use the correct single parser.

## Production Checklist

- [ ] Identified container runtime (CRI vs Docker)
- [ ] Configured correct parser in fluent-bit.conf INPUT section
- [ ] Added matching parser definition to parsers.conf
- [ ] Regenerated FluentBit ConfigMap
- [ ] Tested with real workflow execution
- [ ] Verified events reaching PostgreSQL raw tables
- [ ] Documented runtime in deployment notes

## KIND-Specific Notes

**KIND always uses containerd (CRI runtime)**

For KIND clusters, always use the **CRI parser**:

```conf
[INPUT]
    Parser    cri
```

This is because KIND runs containers using containerd, not Docker, even though KIND itself runs in Docker.

## References

- FluentBit Tail Input: https://docs.fluentbit.io/manual/pipeline/inputs/tail
- FluentBit Parsers: https://docs.fluentbit.io/manual/pipeline/parsers
- Kubernetes Logging Architecture: https://kubernetes.io/docs/concepts/cluster-administration/logging/
- CRI Logging Format: https://github.com/kubernetes/design-proposals-archive/blob/main/node/kubelet-cri-logging.md

## Summary

**Rule of Thumb:**
- **Modern Kubernetes** (2021+): Use **CRI parser** (containerd/CRI-O)
- **Legacy/Docker Desktop**: Use **Docker parser**
- **KIND clusters**: Always use **CRI parser**

**Test Command:**
```bash
# Quick test to identify runtime
kubectl get nodes -o jsonpath='{.items[0].status.nodeInfo.containerRuntimeVersion}'
```

Output examples:
- `containerd://1.7.2` → Use **CRI parser**
- `cri-o://1.25.0` → Use **CRI parser**
- `docker://20.10.21` → Use **Docker parser**
