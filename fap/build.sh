#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# Pen15 Controller FAP — Build Script
# ═══════════════════════════════════════════════════════════════════
#
# Prerequisites:
#   1. Install ufbt (micro Flipper Build Tool):
#      pip install --upgrade ufbt
#
#   2. Setup ufbt for your firmware channel:
#      ufbt update --channel=dev    # for Momentum/Unleashed dev
#      ufbt update --channel=release  # for official release
#
# Usage:
#   cd fap/
#   chmod +x build.sh
#   ./build.sh              # Build the FAP
#   ./build.sh deploy       # Build and deploy to SD card via USB
#   ./build.sh clean        # Clean build artifacts
#
# The compiled .fap file will be placed in:
#   build/pen15_controller.fap
#
# Copy this file to your Flipper Zero SD card under:
#   Apps/GPIO/pen15_controller.fap
#
# ═══════════════════════════════════════════════════════════════════

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FAP_DIR="$SCRIPT_DIR/pen15_controller"
BUILD_DIR="$SCRIPT_DIR/build"

# Check ufbt is installed
if ! command -v ufbt &>/dev/null; then
    echo "ERROR: ufbt not found. Install with: pip install --upgrade ufbt"
    exit 1
fi

case "${1:-build}" in
    build)
        echo "=== Building Pen15 Controller FAP ==="
        cd "$FAP_DIR"
        ufbt
        echo ""
        echo "Build complete!"
        echo "FAP file: $(ufbt reach 2>/dev/null || echo 'check build dir')"
        ;;

    deploy)
        echo "=== Building and deploying Pen15 Controller FAP ==="
        cd "$FAP_DIR"
        ufbt launch
        echo ""
        echo "FAP deployed to Flipper Zero!"
        echo "Launch it from: Apps/GPIO on your Flipper"
        ;;

    clean)
        echo "=== Cleaning build artifacts ==="
        rm -rf "$FAP_DIR/build"
        echo "Done."
        ;;

    *)
        echo "Usage: $0 [build|deploy|clean]"
        echo "  build   - Build the FAP (default)"
        echo "  deploy  - Build and deploy to Flipper via USB"
        echo "  clean   - Remove build artifacts"
        exit 1
        ;;
esac
