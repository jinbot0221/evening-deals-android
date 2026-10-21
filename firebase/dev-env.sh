#!/usr/bin/env bash
# Source from the repository root before Firebase commands.
export npm_config_cache="${npm_config_cache:-$PWD/.firebase-cache/npm}"
export XDG_CONFIG_HOME="${XDG_CONFIG_HOME:-$PWD/.firebase-cache/config}"
export FIREBASE_EMULATORS_PATH="${FIREBASE_EMULATORS_PATH:-$PWD/.firebase-cache/emulators}"
# Verified Google Cloud SDK fallback retained by cloud onboarding.
if [[ -f /workspace/tools/firestore-launcher.jar && -z "${FIRESTORE_EMULATOR_BINARY_PATH:-}" ]]; then
  export FIRESTORE_EMULATOR_BINARY_PATH=/workspace/tools/firestore-launcher.jar
fi
