# Reference Examples

This directory contains **reference examples** of Kubernetes manifests for manual/development deployments.

## NOT For Embedding

⚠️ **These files are NOT meant to be embedded in the operator!**

The operator builds Kubernetes resources **programmatically** based on the DataIndex CR spec.

## Purpose

These examples show:
- ✅ What a working DaemonSet looks like
- ✅ Required RBAC permissions
- ✅ Resource limits and health checks
- ✅ Volume mounts and security context

## For Operator Developers

Use these as **reference** when building DaemonSet objects programmatically in the operator:

```go
// Don't do this:
//go:embed examples/mode2-elasticsearch/daemonset.yaml  ❌

// Do this instead:
func (r *DataIndexReconciler) buildVectorDaemonSet(cr *v1alpha1.DataIndex) *appsv1.DaemonSet {
    // Reference: examples/mode2-elasticsearch/daemonset.yaml
    return &appsv1.DaemonSet{
        ObjectMeta: metav1.ObjectMeta{
            Name:      "data-index-vector",
            Namespace: cr.Namespace,
        },
        Spec: appsv1.DaemonSetSpec{
            // Build spec based on CR, using examples as reference
        },
    }
}
```

## Manual Deployment

For testing without the operator:

```bash
# Deploy Vector for MODE 2 (Elasticsearch)
kubectl apply -f mode2-elasticsearch/daemonset.yaml
```
