#!/usr/bin/env bash
# Builds the Go engine into an Android AAR and wires it into the app.
#
# Requirements: Go 1.24+, Android SDK + NDK, gomobile.
#   go install golang.org/x/mobile/cmd/gomobile@latest
#   gomobile init
#
# Usage: ./scripts/build-engine.sh
set -euo pipefail
cd "$(dirname "$0")/../engine"

echo "▶ Running engine tests first…"
go test ./... -count=1

echo "▶ Building engine.aar (arm64 + x86_64)…"
gomobile bind -target=android -androidapi 26 -o build/engine.aar .

echo "✅ Done: engine/build/engine.aar"
echo "   The app's build.gradle.kts picks it up automatically on next sync."
echo "   Without the AAR the app still runs on the pure-Kotlin (NanoHTTPD) engine."
