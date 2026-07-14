#!/data/data/com.termux/files/usr/bin/bash
#
# Pen15 Termux OSINT installer for ARM64 Android phones using fish.
#
# Run from fish:
#   bash install_termux_osint_fish.sh
#
# Run a smaller install:
#   bash install_termux_osint_fish.sh --skip-go
#
# Reprint a support report after a failed install:
#   bash install_termux_osint_fish.sh --report-only
#
# Use only on systems, accounts, and networks where you have authorization.

set -uo pipefail

PEN15_HOME="${PEN15_HOME:-$HOME/.pen15}"
TOOLS_DIR="$PEN15_HOME/tools"
BIN_DIR="$PEN15_HOME/bin"
VENV_DIR="$PEN15_HOME/venvs/osint"
LOG_DIR="$PEN15_HOME/logs"
TMP_DIR="$PEN15_HOME/tmp"
FISH_CONF_DIR="$HOME/.config/fish/conf.d"
FISH_CONF="$FISH_CONF_DIR/pen15-osint.fish"
PROFILE_SNIPPET="$PEN15_HOME/pen15-osint-profile.sh"
LATEST_LOG="$LOG_DIR/osint-install-latest.log"
REPORT_FILE="$LOG_DIR/osint-install-report.txt"

INSTALL_GO_TOOLS=1
INSTALL_DARKWEB_TOOLS=0
REPORT_ONLY=0
KEEP_GO_CACHE=0
FAILED_STEPS=()
WARNED_STEPS=()

mkdir -p "$TOOLS_DIR" "$BIN_DIR" "$LOG_DIR" "$TMP_DIR"

timestamp() {
    date +"%Y-%m-%d %H:%M:%S"
}

log() {
    printf '[%s] %s\n' "$(timestamp)" "$*"
}

ok() {
    log "[OK] $*"
}

warn() {
    WARNED_STEPS+=("$*")
    log "[WARN] $*"
}

fail_step() {
    FAILED_STEPS+=("$*")
    log "[FAIL] $*"
}

usage() {
    cat <<'USAGE'
Pen15 Termux OSINT installer

Usage:
  bash install_termux_osint_fish.sh [options]

Options:
  --skip-go          Skip Go-based tools such as subfinder, nuclei, httpx, amass.
  --with-darkweb     Also try optional dark-web OSINT tools that may be slower.
  --keep-go-cache    Do not remove Go build cache after installation.
  --report-only      Print the latest support report and exit.
  -h, --help         Show this help.

What this installs:
  Core Termux deps, fish integration, Python venv, Sherlock, theHarvester,
  sqlmap, SpiderFoot, Recon-ng, Maigret, Holehe, GHunt, Photon, FinalRecon,
  Nikto, SecLists, and optional Go recon tools from GitHub.
USAGE
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --skip-go) INSTALL_GO_TOOLS=0 ;;
        --with-darkweb) INSTALL_DARKWEB_TOOLS=1 ;;
        --keep-go-cache) KEEP_GO_CACHE=1 ;;
        --report-only) REPORT_ONLY=1 ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown option: %s\n\n' "$1" >&2
            usage
            exit 2
            ;;
    esac
    shift
done

exec > >(tee -a "$LATEST_LOG") 2>&1

run_retry() {
    local label="$1"
    shift
    local attempt=1
    local max_attempts=3
    local delay=4

    while [ "$attempt" -le "$max_attempts" ]; do
        log "$label (attempt $attempt/$max_attempts)"
        if "$@"; then
            ok "$label"
            return 0
        fi
        if [ "$attempt" -lt "$max_attempts" ]; then
            warn "$label failed; retrying in ${delay}s"
            sleep "$delay"
            delay=$((delay * 2))
        fi
        attempt=$((attempt + 1))
    done

    fail_step "$label"
    return 1
}

run_optional() {
    local label="$1"
    shift
    log "$label"
    if "$@"; then
        ok "$label"
        return 0
    fi
    fail_step "$label"
    return 1
}

wait_for_apt_lock() {
    local lock
    local termux_prefix="${PREFIX:-/data/data/com.termux/files/usr}"
    for lock in "$termux_prefix/var/lib/dpkg/lock" "$termux_prefix/var/lib/apt/lists/lock"; do
        while command -v fuser >/dev/null 2>&1 && fuser "$lock" >/dev/null 2>&1; do
            warn "Waiting for Termux package lock: $lock"
            sleep 5
        done
    done
}

pkg_install_many() {
    local package
    wait_for_apt_lock
    for package in "$@"; do
        if dpkg -s "$package" >/dev/null 2>&1; then
            ok "Termux package already installed: $package"
            continue
        fi
        run_retry "Installing Termux package: $package" pkg install -y "$package" || true
    done
}

clone_or_update() {
    local name="$1"
    local repo="$2"
    local dest="$TOOLS_DIR/$name"

    if [ -d "$dest/.git" ]; then
        run_optional "Updating $name from $repo" git -C "$dest" pull --ff-only || true
    else
        run_retry "Cloning $name from $repo" git clone --depth 1 "$repo" "$dest" || return 1
    fi
}

venv_pip() {
    "$VENV_DIR/bin/python" -m pip "$@"
}

pip_install_optional() {
    local label="$1"
    shift
    run_retry "$label" venv_pip install "$@" || true
}

install_python_repo() {
    local name="$1"
    local repo="$2"
    local dir="$TOOLS_DIR/$name"

    clone_or_update "$name" "$repo" || return 1

    if [ -f "$dir/requirements.txt" ]; then
        pip_install_optional "Installing Python requirements for $name" -r "$dir/requirements.txt"
    fi
    if [ -f "$dir/requirements/base.txt" ]; then
        pip_install_optional "Installing base Python requirements for $name" -r "$dir/requirements/base.txt"
    fi
    if [ -f "$dir/REQUIREMENTS" ]; then
        pip_install_optional "Installing REQUIREMENTS for $name" -r "$dir/REQUIREMENTS"
    fi

    if [ -f "$dir/pyproject.toml" ] || [ -f "$dir/setup.py" ]; then
        pip_install_optional "Installing editable package for $name" -e "$dir"
    fi
}

write_wrapper() {
    local command_name="$1"
    local command_body="$2"
    local wrapper="$BIN_DIR/$command_name"

    cat > "$wrapper" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
export PEN15_HOME="$PEN15_HOME"
export PATH="$BIN_DIR:$VENV_DIR/bin:\$HOME/go/bin:\$PATH"
$command_body "\$@"
EOF
    chmod +x "$wrapper"
}

write_python_module_wrapper() {
    local command_name="$1"
    local module="$2"
    write_wrapper "$command_name" "exec \"$VENV_DIR/bin/python\" -m $module"
}

write_python_file_wrapper() {
    local command_name="$1"
    local file_path="$2"
    write_wrapper "$command_name" "exec \"$VENV_DIR/bin/python\" \"$file_path\""
}

install_go_tool() {
    local name="$1"
    local module="$2"

    if [ "$INSTALL_GO_TOOLS" -ne 1 ]; then
        warn "Skipping Go tool $name because --skip-go was used"
        return 0
    fi

    if ! command -v go >/dev/null 2>&1; then
        fail_step "Go is missing; cannot install $name"
        return 1
    fi

    export GOPATH="${GOPATH:-$HOME/go}"
    export GOBIN="${GOBIN:-$BIN_DIR}"
    mkdir -p "$GOPATH" "$GOBIN"
    run_retry "Installing Go tool: $name" go install "$module" || true
}

install_seclists_sparse() {
    local dest="$TOOLS_DIR/SecLists"
    local repo="https://github.com/danielmiessler/SecLists.git"

    if [ -d "$dest/.git" ]; then
        run_optional "Updating sparse SecLists checkout" git -C "$dest" pull --ff-only || true
        return 0
    fi

    run_retry "Cloning sparse SecLists checkout" git clone --depth 1 --filter=blob:none --sparse "$repo" "$dest" || return 1
    run_optional "Selecting lightweight SecLists folders" \
        git -C "$dest" sparse-checkout set \
            Discovery/DNS \
            Discovery/Web-Content \
            Usernames \
            Passwords/Common-Credentials || true
}

create_report() {
    {
        printf 'Pen15 Termux OSINT installer report\n'
        printf 'Generated: %s\n\n' "$(timestamp)"
        printf 'System:\n'
        printf '  uname: %s\n' "$(uname -a 2>/dev/null || true)"
        printf '  arch: %s\n' "$(uname -m 2>/dev/null || true)"
        printf '  PREFIX: %s\n' "${PREFIX:-unset}"
        printf '  SHELL: %s\n' "${SHELL:-unset}"
        printf '  HOME: %s\n' "$HOME"
        printf '  storage: %s\n' "$(df -h "$HOME" 2>/dev/null | awk 'END {print}' || true)"
        printf '\nInstalled command checks:\n'
        for cmd in fish git python pip sqlmap sherlock theHarvester spiderfoot recon-ng maigret holehe ghunt photon finalrecon nikto subfinder nuclei httpx amass go-dork; do
            if command -v "$cmd" >/dev/null 2>&1; then
                printf '  [OK] %s -> %s\n' "$cmd" "$(command -v "$cmd")"
            else
                printf '  [--] %s missing\n' "$cmd"
            fi
        done
        printf '\nFailed steps:\n'
        if [ "${#FAILED_STEPS[@]}" -eq 0 ]; then
            printf '  none\n'
        else
            printf '  - %s\n' "${FAILED_STEPS[@]}"
        fi
        printf '\nWarnings:\n'
        if [ "${#WARNED_STEPS[@]}" -eq 0 ]; then
            printf '  none\n'
        else
            printf '  - %s\n' "${WARNED_STEPS[@]}"
        fi
        printf '\nLatest log path: %s\n' "$LATEST_LOG"
        printf 'Fish config path: %s\n' "$FISH_CONF"
    } > "$REPORT_FILE"
}

print_report() {
    if [ -f "$REPORT_FILE" ]; then
        cat "$REPORT_FILE"
    else
        printf 'No report found at %s\n' "$REPORT_FILE"
    fi
}

if [ "$REPORT_ONLY" -eq 1 ]; then
    print_report
    exit 0
fi

log "=== Pen15 Termux OSINT installer started ==="
log "Log file: $LATEST_LOG"
log "Tools directory: $TOOLS_DIR"

if ! command -v pkg >/dev/null 2>&1; then
    fail_step "This script must run inside Termux; pkg command was not found"
    create_report
    print_report
    exit 1
fi

if [ "$(uname -m 2>/dev/null || true)" != "aarch64" ]; then
    warn "This was designed for ARM64/aarch64 Android; detected $(uname -m 2>/dev/null || echo unknown)"
fi

if [ -n "${PREFIX:-}" ] && [ ! -w "$PREFIX" ]; then
    warn "PREFIX is not writable: $PREFIX"
fi

pkg update -y || warn "pkg update failed once; continuing with package installs"
dpkg --configure -a 2>/dev/null || true

pkg_install_many \
    termux-tools fish git curl wget ca-certificates jq unzip tar nano \
    python python-pip clang make cmake pkg-config binutils patchelf \
    libffi libxml2 libxslt openssl openssl-tool rust golang nodejs-lts \
    perl dnsutils whois nmap termux-api

if ! command -v python >/dev/null 2>&1; then
    fail_step "Python did not install; Python OSINT tools cannot be installed"
    create_report
    print_report
    exit 1
fi

if [ ! -x "$VENV_DIR/bin/python" ]; then
    run_retry "Creating Python virtual environment" python -m venv "$VENV_DIR" || {
        fail_step "Could not create Python venv at $VENV_DIR"
        create_report
        print_report
        exit 1
    }
fi

export PATH="$BIN_DIR:$VENV_DIR/bin:$HOME/go/bin:$PATH"
pip_install_optional "Upgrading pip tooling in venv" --upgrade pip setuptools wheel

pip_install_optional "Installing common Python support libraries" \
    requests beautifulsoup4 lxml dnspython aiohttp aiodns censys shodan \
    rich click colorama pyyaml python-whois tldextract urllib3

install_python_repo sherlock "https://github.com/sherlock-project/sherlock.git"
write_python_module_wrapper sherlock sherlock

install_python_repo theHarvester "https://github.com/laramies/theHarvester.git"
write_python_file_wrapper theHarvester "$TOOLS_DIR/theHarvester/theHarvester.py"

clone_or_update sqlmap "https://github.com/sqlmapproject/sqlmap.git"
write_python_file_wrapper sqlmap "$TOOLS_DIR/sqlmap/sqlmap.py"

install_python_repo spiderfoot "https://github.com/smicallef/spiderfoot.git"
write_python_file_wrapper spiderfoot "$TOOLS_DIR/spiderfoot/sf.py"

install_python_repo recon-ng "https://github.com/lanmaster53/recon-ng.git"
write_python_file_wrapper recon-ng "$TOOLS_DIR/recon-ng/recon-ng"

install_python_repo maigret "https://github.com/soxoj/maigret.git"
write_python_module_wrapper maigret maigret

install_python_repo holehe "https://github.com/megadose/holehe.git"
write_python_module_wrapper holehe holehe

install_python_repo GHunt "https://github.com/mxrch/GHunt.git"
write_python_module_wrapper ghunt ghunt

install_python_repo Photon "https://github.com/s0md3v/Photon.git"
write_python_file_wrapper photon "$TOOLS_DIR/Photon/photon.py"

install_python_repo FinalRecon "https://github.com/thewhiteh4t/FinalRecon.git"
write_python_file_wrapper finalrecon "$TOOLS_DIR/FinalRecon/finalrecon.py"

clone_or_update nikto "https://github.com/sullo/nikto.git"
if [ -f "$TOOLS_DIR/nikto/program/nikto.pl" ]; then
    write_wrapper nikto "exec perl \"$TOOLS_DIR/nikto/program/nikto.pl\""
else
    fail_step "Nikto clone did not contain program/nikto.pl"
fi

install_seclists_sparse || true

install_go_tool subfinder "github.com/projectdiscovery/subfinder/v2/cmd/subfinder@latest"
install_go_tool nuclei "github.com/projectdiscovery/nuclei/v3/cmd/nuclei@latest"
install_go_tool httpx "github.com/projectdiscovery/httpx/cmd/httpx@latest"
install_go_tool naabu "github.com/projectdiscovery/naabu/v2/cmd/naabu@latest"
install_go_tool dnsx "github.com/projectdiscovery/dnsx/cmd/dnsx@latest"
install_go_tool amass "github.com/owasp-amass/amass/v4/.../amass@master"
install_go_tool go-dork "github.com/dwisiswant0/go-dork@latest"

if [ "$INSTALL_DARKWEB_TOOLS" -eq 1 ]; then
    install_python_repo TorBot "https://github.com/DedSecInside/TorBot.git"
    write_python_file_wrapper torbot "$TOOLS_DIR/TorBot/torbot.py"
else
    warn "Optional dark-web tools skipped. Re-run with --with-darkweb if you need them."
fi

cat > "$PROFILE_SNIPPET" <<EOF
export PEN15_HOME="$PEN15_HOME"
export PEN15_TOOLS="$TOOLS_DIR"
export PEN15_OSINT_VENV="$VENV_DIR"
export PATH="$BIN_DIR:$VENV_DIR/bin:\$HOME/go/bin:\$PATH"
EOF

mkdir -p "$FISH_CONF_DIR"
cat > "$FISH_CONF" <<EOF
# Pen15 OSINT tools for Termux fish shell.
# Generated by scripts/install_termux_osint_fish.sh
set -gx PEN15_HOME "$PEN15_HOME"
set -gx PEN15_TOOLS "$TOOLS_DIR"
set -gx PEN15_OSINT_VENV "$VENV_DIR"
fish_add_path "$BIN_DIR"
fish_add_path "$VENV_DIR/bin"
fish_add_path "\$HOME/go/bin"

function pen15-report
    if test -f "$PEN15_HOME/logs/osint-install-report.txt"
        cat "$PEN15_HOME/logs/osint-install-report.txt"
    else
        echo "No Pen15 report found at $PEN15_HOME/logs/osint-install-report.txt"
    end
end
function spiderfoot-web
    spiderfoot -l 127.0.0.1:5001 \$argv
end
function pen15-tools
    printf "Pen15 tools are in %s\n" "\$PEN15_TOOLS"
    command ls "$BIN_DIR"
end
EOF

cp "$0" "$PEN15_HOME/install_termux_osint_fish.sh" 2>/dev/null || true

if [ "$KEEP_GO_CACHE" -ne 1 ] && command -v go >/dev/null 2>&1; then
    go clean -cache -modcache 2>/dev/null || true
fi

create_report

if command -v termux-clipboard-set >/dev/null 2>&1; then
    termux-clipboard-set < "$REPORT_FILE" || true
fi

log "=== Pen15 Termux OSINT installer finished ==="
print_report

if [ "${#FAILED_STEPS[@]}" -gt 0 ]; then
    cat <<EOF

Some tools failed, but the installer kept going.
Paste this report back into Cursor if you want me to diagnose the failures:
  $REPORT_FILE

The full log is here:
  $LATEST_LOG
EOF
    exit 1
fi

cat <<EOF

Install completed.

Restart fish or run:
  source "$FISH_CONF"

Try:
  sherlock username
  theHarvester -d example.com -b bing
  sqlmap -h
  spiderfoot -h
  pen15-report
EOF
