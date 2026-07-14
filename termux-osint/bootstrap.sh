#!/data/data/com.termux/files/usr/bin/bash
# Standalone Termux OSINT installer — NOT tied to any Android app.
#
# 1. Copy this entire folder to your phone: ~/termux-osint
# 2. Run:
#      cd ~/termux-osint && bash bootstrap.sh --skip-heavy
#
# Options passed to install_osint_tools.fish:
#   --clean-first  --skip-heavy  --retry-failed  --yes

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Termux OSINT Bootstrap (standalone) ==="
echo "Scripts: $SCRIPT_DIR"
echo ""

if ! command -v fish >/dev/null 2>&1; then
    echo "[1/3] Installing fish shell..."
    pkg update -y
    pkg install -y fish git curl
else
    echo "[1/3] fish already installed"
fi

if [ ! -f "${SCRIPT_DIR}/install_osint_tools.fish" ]; then
    echo "[!!] install_osint_tools.fish not found in ${SCRIPT_DIR}"
    echo "     Copy the termux-osint folder to ~/termux-osint and run from there."
    exit 1
fi

chmod +x "${SCRIPT_DIR}"/*.fish "${SCRIPT_DIR}"/*.sh 2>/dev/null || true

mkdir -p "${HOME}/.termux-osint"
echo "[2/3] Config dir: ~/.termux-osint"
echo "[3/3] Launching Fish installer..."
echo ""
exec fish "${SCRIPT_DIR}/install_osint_tools.fish" "$@"
