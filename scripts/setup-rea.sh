#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
TOOLS_DIR="$PROJECT_ROOT/.tools"

echo "=== REA Toolchain Setup (POSIX) ==="
mkdir -p "$TOOLS_DIR/rea" "$TOOLS_DIR/jadx" "$TOOLS_DIR/apktool"

# 1. Isolated rea-agents
echo "Setting up isolated rea-agents..."
cd "$TOOLS_DIR/rea"
if [ ! -f "package.json" ]; then
    npm init -y >/dev/null
fi
npm install rea-agents@6.8.0 --save-exact

# 2. JADX Headless MCP
JADX_JAR="$TOOLS_DIR/jadx/jadx-headless-mcp-0.7.1-all.jar"
if [ ! -f "$JADX_JAR" ]; then
    echo "Downloading JADX headless MCP 0.7.1..."
    curl -sSL "https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar" -o "$JADX_JAR"
fi

# 3. Apktool
APKTOOL_JAR="$TOOLS_DIR/apktool/apktool.jar"
if [ ! -f "$APKTOOL_JAR" ]; then
    echo "Downloading Apktool 2.11.1..."
    curl -sSL "https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar" -o "$APKTOOL_JAR"
fi

cat << 'EOF' > "$TOOLS_DIR/apktool/apktool"
#!/usr/bin/env bash
exec java -jar "$(dirname "$0")/apktool.jar" "$@"
EOF
chmod +x "$TOOLS_DIR/apktool/apktool"

echo "Setup complete. Run check-rea.sh to verify."
