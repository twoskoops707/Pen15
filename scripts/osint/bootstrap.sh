#!/data/data/com.termux/files/usr/bin/bash
# Bootstrap: run this from Termux (bash is default shell on first open)
#   curl -fsSL https://raw.githubusercontent.com/twoskoops707/Pen15/main/scripts/osint/bootstrap.sh | bash
#
# Clones Pen15 scripts (or uses local copy), installs fish, runs the OSINT installer.

set -euo pipefail

PEN15_REPO="${PEN15_REPO:-https://github.com/twoskoops707/Pen15.git}"
INSTALL_DIR="${HOME}/Pen15"
OSINT_DIR="${INSTALL_DIR}/scripts/osint"

echo "=== Pen15 OSINT Bootstrap ==="
echo ""

# Ensure fish
if ! command -v fish >/dev/null 2>&1; then
    echo "[1/4] Installing fish shell..."
    pkg update -y
    pkg install -y fish git curl
else
    echo "[1/4] fish already installed"
fi

# Clone or update repo
echo "[2/4] Fetching Pen15 scripts..."
if [ -d "${INSTALL_DIR}/.git" ]; then
    git -C "${INSTALL_DIR}" pull --ff-only 2>/dev/null || true
else
    git clone --depth 1 "${PEN15_REPO}" "${INSTALL_DIR}" 2>/dev/null || {
        echo "[!!] Clone failed — if you have scripts locally, copy to ${OSINT_DIR}"
        mkdir -p "${OSINT_DIR}"
    }
fi

# Fallback: copy from current directory if run from repo checkout
if [ ! -f "${OSINT_DIR}/install_osint_tools.fish" ] && [ -f "./scripts/osint/install_osint_tools.fish" ]; then
    echo "[2b] Using local scripts from current directory"
    mkdir -p "${OSINT_DIR}"
    cp -r ./scripts/osint/* "${OSINT_DIR}/"
fi

if [ ! -f "${OSINT_DIR}/install_osint_tools.fish" ]; then
    echo "[!!] install_osint_tools.fish not found at ${OSINT_DIR}"
    echo "     Manual: git clone ${PEN15_REPO} ${INSTALL_DIR}"
    exit 1
fi

chmod +x "${OSINT_DIR}"/*.fish "${OSINT_DIR}"/*.sh 2>/dev/null || true

# Termux storage + Pen15 external app permission
mkdir -p "${HOME}/.termux" "${HOME}/.pen15"
if ! grep -q 'allow-external-apps=true' "${HOME}/.termux/termux.properties" 2>/dev/null; then
    echo 'allow-external-apps=true' >> "${HOME}/.termux/termux.properties"
    echo "[3/4] Added allow-external-apps=true for Pen15"
else
    echo "[3/4] termux.properties already configured"
fi

echo "[4/4] Launching Fish installer..."
echo ""
exec fish "${OSINT_DIR}/install_osint_tools.fish" "$@"
