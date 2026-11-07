#!/usr/bin/env bash
# Regenerates theme/ColorTokens.kt from design/src/tokens.json.
# Uses the Node toolchain and node_modules of the design pipeline (design/src, `npm ci` there first).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
DESIGN="$ROOT/design/src"
BUNDLE="$(mktemp -d)/gen-color-tokens.bundle.mjs"
# material-color-utilities' ESM build lacks file extensions -> bundle with esbuild first.
NODE_PATH="$DESIGN/node_modules" "$DESIGN/node_modules/.bin/esbuild" \
  "$ROOT/core/designsystem/scripts/gen-color-tokens.mjs" \
  --bundle --platform=node --format=esm --outfile="$BUNDLE" --log-level=warning
node "$BUNDLE" "$ROOT"
