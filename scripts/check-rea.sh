#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TOOLS_DIR="$PROJECT_ROOT/.tools"

echo "=== REA Toolchain Verification (POSIX) ==="
node --version
"$TOOLS_DIR/rea/node_modules/rea-agents/scripts/rea.mjs" --version
export REA_JADX_MCP_JAR="$TOOLS_DIR/jadx/jadx-headless-mcp-0.7.1-all.jar"
"$TOOLS_DIR/rea/node_modules/rea-agents/scripts/rea.mjs" mcp doctor
