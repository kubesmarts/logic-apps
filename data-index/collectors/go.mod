module github.com/kubesmarts/logic-apps/data-index/collectors

go 1.22

// This module contains reference configurations for Data Index log collectors.
// It contains only YAML configuration files - no Go code.
//
// Consumed by: logic-operator (Kubernetes operator)
//
// Usage in Go operator:
//   1. Add to go.mod: require github.com/kubesmarts/logic-apps/data-index/collectors v1.0.0
//   2. Sync configs: make generate (copies from module cache to internal/configs/)
//   3. Embed in binary: //go:embed internal/configs/vector/mode2-elasticsearch/vector.yaml
