#!/usr/bin/env bash
# ============================================================================
# FluentBit ConfigMap Generator
# ============================================================================
#
# Purpose: Generate Kubernetes ConfigMap YAML from FluentBit configuration files
#
# Usage:
#   ./generate-configmap.sh <mode-directory> [output-file]
#
# Example:
#   ./generate-configmap.sh mode1-postgresql-polling
#   ./generate-configmap.sh mode1-postgresql-polling kubernetes/configmap.yaml
#
# ============================================================================

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper functions
info() { echo -e "${GREEN}[INFO]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }
step() { echo -e "${BLUE}[STEP]${NC} $*"; }

# Validate arguments
if [ $# -lt 1 ]; then
    error "Usage: $0 <mode-directory> [output-file]"
    error ""
    error "Examples:"
    error "  $0 mode1-postgresql-polling"
    error "  $0 mode1-postgresql-polling kubernetes/configmap.yaml"
    exit 1
fi

MODE_DIR="$1"
OUTPUT_FILE="${2:-}"

# Validate mode directory exists
if [ ! -d "$MODE_DIR" ]; then
    error "Mode directory not found: $MODE_DIR"
    exit 1
fi

# Extract mode name from directory
MODE_NAME=$(basename "$MODE_DIR")
info "Generating ConfigMap for mode: $MODE_NAME"

# Function to indent and escape content for YAML
# Usage: yaml_encode <file> <indent-spaces>
yaml_encode() {
    local file="$1"
    local indent="$2"
    local spaces=""

    # Create indent string
    for ((i=0; i<indent; i++)); do
        spaces+=" "
    done

    # Read file, indent each line
    while IFS= read -r line || [ -n "$line" ]; do
        echo "${spaces}${line}"
    done < "$file"
}

# Generate ConfigMap YAML
generate_configmap() {
    local mode_dir="$1"
    local mode_name=$(basename "$mode_dir")
    local to_file="${2:-false}"

    cat <<EOF
# ============================================================================
# FluentBit ConfigMap - ${mode_name}
# ============================================================================
#
# AUTO-GENERATED - DO NOT EDIT MANUALLY
# Generated from: ${mode_dir}/*.conf, *.lua
#
# To regenerate:
#   cd scripts/fluentbit
#   ./generate-configmap.sh ${mode_name}
#
# ============================================================================
apiVersion: v1
kind: ConfigMap
metadata:
  name: fluent-bit-config
  namespace: logging
  labels:
    app: fluent-bit
    mode: ${mode_name}
data:
EOF

    # Add fluent-bit.conf
    if [ -f "$mode_dir/fluent-bit.conf" ]; then
        [ "$to_file" = "false" ] && step "Adding fluent-bit.conf"
        echo "  fluent-bit.conf: |"
        yaml_encode "$mode_dir/fluent-bit.conf" 4
    else
        [ "$to_file" = "false" ] && warn "fluent-bit.conf not found in $mode_dir"
    fi

    echo ""

    # Add parsers.conf
    if [ -f "$mode_dir/parsers.conf" ]; then
        [ "$to_file" = "false" ] && step "Adding parsers.conf"
        echo "  parsers.conf: |"
        yaml_encode "$mode_dir/parsers.conf" 4
    else
        [ "$to_file" = "false" ] && warn "parsers.conf not found in $mode_dir"
    fi

    echo ""

    # Add any .lua files
    for lua_file in "$mode_dir"/*.lua; do
        if [ -f "$lua_file" ]; then
            local lua_name=$(basename "$lua_file")
            [ "$to_file" = "false" ] && step "Adding $lua_name"
            echo "  ${lua_name}: |"
            yaml_encode "$lua_file" 4
            echo ""
        fi
    done
}

# Generate and output
if [ -n "$OUTPUT_FILE" ]; then
    # Output to file (no color codes in YAML)
    mkdir -p "$(dirname "$OUTPUT_FILE")"
    generate_configmap "$MODE_DIR" "true" > "$OUTPUT_FILE"
    info "ConfigMap generated: $OUTPUT_FILE"
    info "Lines: $(wc -l < "$OUTPUT_FILE")"
else
    # Output to stdout (with color codes for terminal)
    generate_configmap "$MODE_DIR" "false"
fi

info "✓ ConfigMap generation complete"
