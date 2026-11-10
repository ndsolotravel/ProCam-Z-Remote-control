# REA Toolchain Setup & Integration Guide

## Overview

This repository (`CamPro Remote rea-main`) has been configured with an isolated, persistent installation of **REA (Reverse Engineer Anything)**, official npm package `rea-agents@6.8.0`, along with the specialized reverse engineering toolchain required for the detected project stack.

---

## 1. Environment & Capability Inventory

| Capability | Component | Version | Installation Mode | Verified Source / Upstream |
| :--- | :--- | :--- | :--- | :--- |
| **REA Engine** | `rea-agents` CLI & MCP | `6.8.0` | Isolated npm in `.tools/rea/` | [npmjs.com/package/rea-agents](https://www.npmjs.com/package/rea-agents) |
| **Node.js Runtime** | Node.js | `v26.13.0 LTS` | Portable in `.tools/node/` | [nodejs.org/dist/v26.13.0](https://nodejs.org/dist/v26.13.0/) |
| **Android Decompilation** | JADX Headless MCP | `0.7.1` | Verified JAR in `.tools/jadx/` | [github.com/1013503897/jadx-headless-mcp](https://github.com/1013503897/jadx-headless-mcp) |
| **Android Resources** | Apktool | `2.11.1` | Portable wrapper in `.tools/apktool/` | [github.com/iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool) |
| **Android Device Bridge**| ADB | `1.0.41` | Android SDK platform-tools | Android Studio SDK (`platform-tools`) |
| **Media Inspection** | FFmpeg / FFprobe | `9.0.2` | Gyan build in `.tools/ffmpeg/bin/` | [gyan.dev/ffmpeg/builds](https://www.gyan.dev/ffmpeg/builds/) |
| **Dynamic Instrumentation**| Frida & Frida Tools | `17.26.2` | Isolated Python venv `.tools/venv/` | [pypi.org/project/frida](https://pypi.org/project/frida/) |
| **Java Platform** | Temurin OpenJDK | `17.0.20.1` | Host installation | Eclipse Adoptium OpenJDK 17 |

---

## 2. Directory Layout

All downloaded binaries, runtime packages, and dependencies reside entirely inside `.tools/`, which is ignored by `.gitignore` to prevent repository bloat or accidental commits:

```
CamPro Remote rea-main/
├── .gitignore                     # Ignores .tools/, *.tmp, *.log
├── .tools/
│   ├── node/                      # Node.js 26.13.0 LTS portable binaries (node.exe, npm.cmd)
│   ├── rea/                       # Isolated package.json, package-lock.json, node_modules/rea-agents
│   ├── jadx/                      # jadx-headless-mcp-0.7.1-all.jar (SHA-256 verified)
│   ├── apktool/                   # apktool.jar (2.11.1) and apktool.bat wrapper
│   ├── ffmpeg/bin/                # ffmpeg.exe and ffprobe.exe
│   └── venv/                      # Python 3.14 virtual environment with frida & frida-tools
├── scripts/
│   ├── setup-rea.ps1              # Idempotent PowerShell setup script
│   ├── check-rea.ps1              # Toolchain diagnostics & verification script
│   ├── setup-rea.sh               # POSIX setup script
│   └── check-rea.sh               # POSIX verification script
└── docs/
    ├── REA_SETUP.md               # This setup & operations guide
    └── REA_TOOLCHAIN_STATUS.md    # Detailed status report & verification ledger
```

---

## 3. Agent Integration (Antigravity MCP)

Antigravity uses the user-scoped MCP configuration at `C:\Users\Hussain\.gemini\config\mcp_config.json`.

REA was registered automatically via REA's official setup engine (`rea setup --client antigravity`), preserving all existing MCP servers (`StitchMCP`, `dart-mcp-server`, `gopls-mcp-server`, `parallel-search`, `supabase`).

### Configuration Entry:

```json
{
  "mcpServers": {
    "rea": {
      "command": "C:\\Users\\Hussain\\Documents\\CamPro Remote rea-main\\.tools\\node\\node.exe",
      "args": [
        "C:\\Users\\Hussain\\Documents\\CamPro Remote rea-main\\.tools\\rea\\node_modules\\rea-agents\\scripts\\rea.mjs",
        "mcp"
      ],
      "env": {
        "JAVA_HOME": "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20.101-hotspot\\",
        "REA_JADX_MCP_JAR": "C:\\Users\\Hussain\\Documents\\CamPro Remote rea-main\\.tools\\jadx\\jadx-headless-mcp-0.7.1-all.jar"
      }
    }
  }
}
```

---

## 4. Verification & Diagnostics

To check the complete toolchain and run REA MCP diagnostics at any time, run:

```powershell
.\scripts\check-rea.ps1
```

Or run manual checks:

```powershell
# Check isolated REA CLI version
& ".tools\node\node.exe" ".tools\rea\node_modules\rea-agents\scripts\rea.mjs" --version

# Run REA MCP Doctor (initialization, tool discovery, request flow)
$env:REA_JADX_MCP_JAR = (Resolve-Path ".tools\jadx\jadx-headless-mcp-0.7.1-all.jar").Path
& ".tools\node\node.exe" ".tools\rea\node_modules\rea-agents\scripts\rea.mjs" mcp doctor
```

---

## 5. Rollback and Removal Procedures

### To Restore Original Configuration:
REA creates an automatic cryptographic backup before applying modifications.
To restore the pre-setup configuration:
```powershell
Copy-Item "C:\Users\Hussain\.gemini\config\mcp_config.json.rea.backup.c5e2cb2d893805f73273cbb66a2ce43fd0a765419d390b74c9f4a88ab1ea3a1d.recovery" -Destination "C:\Users\Hussain\.gemini\config\mcp_config.json" -Force
```

### To Remove Only Introduced Tools:
Delete the project-local `.tools/` directory and custom scripts:
```powershell
Remove-Item -Recurse -Force ".tools"
Remove-Item -Recurse -Force "scripts\setup-rea.*", "scripts\check-rea.*"
Remove-Item -Recurse -Force "docs\REA_SETUP.md", "docs\REA_TOOLCHAIN_STATUS.md"
```
No host environment or system registries are modified.
