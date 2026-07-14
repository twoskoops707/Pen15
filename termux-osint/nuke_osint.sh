#!/data/data/com.termux/files/usr/bin/bash
# Standalone Termux OSINT cleanup — NOT tied to any Android app.
#
# Run from the folder on your phone:
#   cd ~/termux-osint && bash nuke_osint.sh --dry-run
#   cd ~/termux-osint && bash nuke_osint.sh --yes --reinstall
#
# REMOVES: OSINT tool installs, pip packages, wrappers, build caches
# NEVER TOUCHES: ~/.termux-osint API keys, ~/storage, ~/.termux,
#                recon-ng/workspaces, ~/.spiderfoot

set -euo pipefail

DRY_RUN=0
REINSTALL=0
YES=0
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

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
CONFIG_DIR="${HOME}/.termux-osint"

PROTECTED=(
    "${HOME}/.termux-osint"
    "${HOME}/.termux"
    "${HOME}/storage"
    "${HOME}/recon-ng/workspaces"
    "${HOME}/.spiderfoot"
)

is_protected() {
    local target="$1"
    [ -z "$target" ] && return 0
    [ "$target" = "$HOME" ] || [ "$target" = "/" ] && return 0
    for prefix in "${PROTECTED[@]}"; do
        if [ "$target" = "$prefix" ] || [[ "$target" == "$prefix/"* ]]; then
            return 0
        fi
    done
    return 1
}

rm_path() {
    local target="$1"
    if is_protected "$target"; then
        echo "  SKIP protected (your data): $target"
        return 1
    fi
    if [ ! -e "$target" ]; then
        return 1
    fi
    if [ "$DRY_RUN" -eq 1 ]; then
        echo "  [dry-run] rm -rf $target"
    else
        rm -rf "$target" 2>/dev/null && echo "  removed: $target"
    fi
}

rm_install_logs_only() {
    local logs=(
        "${CONFIG_DIR}/osint-install.log"
        "${CONFIG_DIR}/osint-install-report.txt"
        "${CONFIG_DIR}/osint-failed.txt"
    )
    for f in "${logs[@]}"; do
        if [ -f "$f" ]; then
            if [ "$DRY_RUN" -eq 1 ]; then
                echo "  [dry-run] rm -f $f"
            else
                rm -f "$f" 2>/dev/null && echo "  removed install log: $f"
            fi
        fi
    done
}

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║  Termux OSINT NUKE — tools only, NOT your data   ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

if [ "$DRY_RUN" -eq 0 ] && [ "$YES" -eq 0 ]; then
    echo "Removes OSINT tool installs only."
    echo ""
    echo "PROTECTED (never deleted):"
    echo "  ~/.termux-osint/   API keys and personal config"
    echo "  ~/storage/         shared storage"
    echo "  ~/.termux/         Termux settings"
    echo "  recon-ng/workspaces/"
    echo ""
    read -r -p "Type YES to continue: " answer
    if [ "$answer" != "YES" ]; then
        echo "Aborted."
        exit 1
    fi
fi

mkdir -p "$CONFIG_DIR"
echo "[1/5] Removing OSINT git clones..."
REPO_DIRS=(
    "${HOME}/osint-tools"
    "${HOME}/spiderfoot"
    "${HOME}/theHarvester"
    "${HOME}/nikto"
    "${HOME}/sherlock"
    "${HOME}/maigret"
    "${HOME}/holehe"
    "${HOME}/Sublist3r"
    "${HOME}/sublist3r"
    "${HOME}/sqlmap"
    "${HOME}/blackbird"
    "${HOME}/cloud_enum"
    "${HOME}/GHunt"
    "${HOME}/Photon"
    "${HOME}/h8mail"
    "${HOME}/john-src"
    "${HOME}/hydra-src"
)
for d in "${REPO_DIRS[@]}"; do
    rm_path "$d"
done

if [ -d "${HOME}/recon-ng" ] && ! is_protected "${HOME}/recon-ng"; then
    if [ "$DRY_RUN" -eq 1 ]; then
        echo "  [dry-run] would remove recon-ng tool files (keep workspaces/)"
    else
        find "${HOME}/recon-ng" -mindepth 1 -maxdepth 1 ! -name workspaces -exec rm -rf {} + 2>/dev/null
        echo "  removed recon-ng tool files; kept workspaces/"
    fi
fi

echo ""
echo "[2/5] Removing OSINT bin wrappers..."
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
echo "[3/5] Uninstalling OSINT pip packages..."
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
echo "[4/5] Clearing build caches..."
rm_path /tmp/sf-req-lite.txt
rm_path /tmp/hashcat.7z
rm_path /tmp/hashcat_extracted
rm_path "${HOME}/.cache/pip"
rm_path "${HOME}/.cache/maigret"
rm_path "${HOME}/.cache/sherlock"
rm_path "${HOME}/.config/fish/conf.d/termux-osint.fish"
for p in "${PREFIX}"/tmp/pip-*; do
    [ -e "$p" ] && rm_path "$p"
done
if [ "$DRY_RUN" -eq 0 ]; then
    pip3 cache purge 2>/dev/null || true
    pkg clean -y 2>/dev/null || true
fi
rm_install_logs_only

echo ""
echo "[5/5] Clearing __pycache__ in tool dirs only..."
PYCACHE_DIRS=(
    "${HOME}/osint-tools" "${HOME}/spiderfoot" "${HOME}/recon-ng"
    "${HOME}/theHarvester" "${HOME}/nikto" "${HOME}/sherlock"
)
for dir in "${PYCACHE_DIRS[@]}"; do
    if [ -d "$dir" ] && ! is_protected "$dir"; then
        find "$dir" -maxdepth 6 -type d -name '__pycache__' 2>/dev/null | while read -r c; do
            rm_path "$c"
        done
    fi
done

echo ""
echo "=============================================="
echo "  Done — OSINT tools cleared"
echo "  Your ~/.termux-osint/ and ~/storage/ untouched"
echo "=============================================="
echo ""

if [ "$REINSTALL" -eq 1 ] && [ "$DRY_RUN" -eq 0 ]; then
    echo "Starting fresh install..."
    bash "${SCRIPT_DIR}/bootstrap.sh" --skip-heavy --yes
else
    echo "Reinstall: cd ~/termux-osint && bash nuke_osint.sh --yes --reinstall"
fi
echo ""
