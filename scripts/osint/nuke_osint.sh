#!/data/data/com.termux/files/usr/bin/bash
# Pen15 OSINT NUKE — wipe all OSINT garbage from Termux before reinstall
# Works from default Termux bash (no fish required).
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/twoskoops707/Pen15/cursor/osint-termux-fish-installer-cff4/scripts/osint/nuke_osint.sh | bash
#   curl -fsSL .../nuke_osint.sh | bash -s -- --reinstall
#   bash nuke_osint.sh --dry-run
#
# Keeps: ~/.pen15 API keys (hibp_key.txt etc), Termux base packages (python, git, nmap)
# Removes: all OSINT clones, pip packages, wrappers, caches, build leftovers

set -euo pipefail

DRY_RUN=0
REINSTALL=0
YES=0

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        --reinstall) REINSTALL=1 ;;
        -y|--yes) YES=1 ;;
        -h|--help)
            echo "Usage: bash nuke_osint.sh [--dry-run] [--reinstall] [--yes]"
            exit 0
            ;;
    esac
done

PREFIX="${PREFIX:-/data/data/com.termux/files/usr/bin}"
HOME="${HOME:-/data/data/com.termux/files/home}"
PEN15_DIR="${HOME}/.pen15"

run() {
    if [ "$DRY_RUN" -eq 1 ]; then
        echo "  [dry-run] $*"
    else
        "$@" 2>/dev/null || true
    fi
}

rm_path() {
    if [ -e "$1" ]; then
        if [ "$DRY_RUN" -eq 1 ]; then
            echo "  [dry-run] rm -rf $1"
        else
            rm -rf "$1" 2>/dev/null && echo "  removed: $1"
        fi
    fi
}

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║  Pen15 OSINT NUKE — clearing Termux garbage      ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

if [ "$DRY_RUN" -eq 0 ] && [ "$YES" -eq 0 ]; then
    echo "This removes ALL OSINT tool installs, clones, pip packages, and caches."
    echo "API keys in ~/.pen15/ are KEPT."
    echo ""
    read -r -p "Type YES to continue: " answer
    if [ "$answer" != "YES" ]; then
        echo "Aborted."
        exit 1
    fi
fi

mkdir -p "$PEN15_DIR"
echo "[1/5] Removing git clones and tool directories..."
REPO_DIRS=(
    "$HOME/osint-tools"
    "$HOME/spiderfoot"
    "$HOME/recon-ng"
    "$HOME/theHarvester"
    "$HOME/nikto"
    "$HOME/sherlock"
    "$HOME/maigret"
    "$HOME/holehe"
    "$HOME/Sublist3r"
    "$HOME/sublist3r"
    "$HOME/sqlmap"
    "$HOME/blackbird"
    "$HOME/cloud_enum"
    "$HOME/GHunt"
    "$HOME/Photon"
    "$HOME/h8mail"
    "$HOME/john-src"
    "$HOME/hydra-src"
)
for d in "${REPO_DIRS[@]}"; do
    rm_path "$d"
done

echo ""
echo "[2/5] Removing bin wrappers..."
BIN_WRAPPERS=(
    sherlock maigret holehe theHarvester sublist3r
    spiderfoot sf recon-ng sqlmap photon h8mail
    blackbird cloud_enum phoneinfoga amass nikto
    shodan ghunt bbot socialscan hashcat john hydra
)
for name in "${BIN_WRAPPERS[@]}"; do
    rm_path "${PREFIX}/bin/${name}"
done

echo ""
echo "[3/5] Uninstalling pip packages..."
PIP_PKGS=(
    sherlock-project maigret holehe theHarvester theharvester
    sublist3r h8mail socialscan sqlmap shodan bbot ghunt
    mitmproxy hashid
)
PIP_FLAGS=""
pip3 uninstall --help 2>/dev/null | grep -q break-system-packages && PIP_FLAGS="--break-system-packages"
for pkg in "${PIP_PKGS[@]}"; do
    if pip3 show "$pkg" >/dev/null 2>&1; then
        if [ "$DRY_RUN" -eq 1 ]; then
            echo "  [dry-run] pip3 uninstall $pkg"
        else
            pip3 uninstall -y $PIP_FLAGS "$pkg" 2>/dev/null && echo "  uninstalled pip: $pkg"
        fi
    fi
done

echo ""
echo "[4/5] Clearing caches and temp build files..."
rm_path /tmp/sf-req-lite.txt
rm_path /tmp/hashcat.7z
rm_path /tmp/hashcat_extracted
rm_path "$HOME/.cache/pip"
rm_path "$HOME/.cache/maigret"
rm_path "$HOME/.cache/sherlock"
rm_path "$HOME/.local/share/pipx"
rm_path "$HOME/.config/fish/conf.d/pen15-osint.fish"
for p in "${PREFIX}"/tmp/pip-*; do
    [ -e "$p" ] && rm_path "$p"
done
run pip3 cache purge
run pkg clean -y

# Clear stale install logs (fresh start)
if [ "$DRY_RUN" -eq 0 ]; then
    rm -f "$PEN15_DIR/osint-install.log" \
          "$PEN15_DIR/osint-install-report.txt" \
          "$PEN15_DIR/osint-failed.txt" 2>/dev/null
fi

echo ""
echo "[5/5] Scanning for leftover __pycache__..."
if [ "$DRY_RUN" -eq 1 ]; then
    find "$HOME" -maxdepth 4 -type d -name '__pycache__' 2>/dev/null | head -20 | while read -r c; do
        echo "  [dry-run] would remove: $c"
    done
else
    find "$HOME" -maxdepth 4 -type d -name '__pycache__' 2>/dev/null | while read -r c; do
        rm -rf "$c" 2>/dev/null
    done
    echo "  pycache cleared"
fi

echo ""
echo "=============================================="
echo "  NUKE complete — Termux OSINT garbage cleared"
echo "=============================================="
echo "  Kept:  ~/.pen15/ API keys"
echo "  Kept:  base Termux packages (python, git, nmap, etc.)"
echo ""

if [ "$REINSTALL" -eq 1 ] && [ "$DRY_RUN" -eq 0 ]; then
    echo "Starting fresh install..."
    SCRIPT_URL="https://raw.githubusercontent.com/twoskoops707/Pen15/cursor/osint-termux-fish-installer-cff4/scripts/osint/bootstrap.sh"
    curl -fsSL "$SCRIPT_URL" | bash -s -- --skip-heavy --yes
else
    echo "Reinstall now:"
    echo "  curl -fsSL https://raw.githubusercontent.com/twoskoops707/Pen15/cursor/osint-termux-fish-installer-cff4/scripts/osint/bootstrap.sh | bash -s -- --skip-heavy --yes"
    echo ""
    echo "Or in Fish:"
    echo "  fish ~/Pen15/scripts/osint/install_osint_tools.fish --skip-heavy"
fi
echo ""
