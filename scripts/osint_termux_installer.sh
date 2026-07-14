#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# Pen15 — OSINT tool installer for Termux (ARM Android) with fish shell
# =============================================================================
#
# Installs the main OSINT tools that are NOT in the pkg/apt repos by cloning
# them from GitHub and wiring them into your PATH so they work from fish.
#
# Tools installed:
#   Recon / usernames : sherlock, maigret, holehe, socialscan, blackbird
#   Email / domain     : theHarvester, sublist3r, Photon
#   Frameworks         : SpiderFoot, recon-ng
#   Injection          : sqlmap            (the "SQL" tool)
#   Modern Go recon    : subfinder, httpx, katana, dnsx, gau, waybackurls, amass
#
# HOW TO RUN (from your fish shell):
#   bash ~/path/to/osint_termux_installer.sh          # install everything
#   bash ~/path/to/osint_termux_installer.sh --check   # only verify what's installed
#   bash ~/path/to/osint_termux_installer.sh --force    # reinstall/update everything
#   bash ~/path/to/osint_termux_installer.sh --skip-go  # skip the Go-based tools
#
# SELF-HEALING:
#   * Every network step (pkg / pip / git / go) is retried with backoff.
#   * Build prerequisites (rust, clang, libxml2, openssl, ...) are installed
#     up front so Python wheels compile instead of erroring out.
#   * Each tool is installed independently — one failure never aborts the run.
#   * A full log and a copy-pasteable failure report are written to
#     ~/.osint/logs/. If anything fails, the script prints the report path and
#     tells you to send that file back so the problem can be fixed in-script.
# =============================================================================

# NOTE: we deliberately do NOT use `set -e`. We want to keep going when a
# single tool fails and collect the failures into a report at the end.
set -o pipefail

# -----------------------------------------------------------------------------
# Paths and globals
# -----------------------------------------------------------------------------
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME="${HOME:-/data/data/com.termux/files/home}"

OSINT_HOME="$HOME/.osint"          # everything we install lives here
OSINT_BIN="$OSINT_HOME/bin"        # wrapper scripts (on PATH)
OSINT_VENVS="$OSINT_HOME/venvs"    # per-tool isolated venvs
OSINT_REPOS="$OSINT_HOME/repos"    # git clones
OSINT_LOGS="$OSINT_HOME/logs"
GOBIN="$HOME/go/bin"

STAMP="$(date +%Y%m%d_%H%M%S)"
LOG="$OSINT_LOGS/install-$STAMP.log"
REPORT="$OSINT_LOGS/report-$STAMP.txt"

FORCE=0
DO_GO=1
CHECK_ONLY=0

INSTALLED=()
SKIPPED=()
FAILED=()

# -----------------------------------------------------------------------------
# Arg parsing
# -----------------------------------------------------------------------------
for arg in "$@"; do
    case "$arg" in
        --force)    FORCE=1 ;;
        --skip-go)  DO_GO=0 ;;
        --check)    CHECK_ONLY=1 ;;
        -h|--help)
            sed -n '2,40p' "$0"
            exit 0
            ;;
        *) echo "Unknown option: $arg (use --help)"; exit 2 ;;
    esac
done

# -----------------------------------------------------------------------------
# Output helpers (colour only when attached to a terminal)
# -----------------------------------------------------------------------------
if [ -t 1 ]; then
    C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'
    C_BLU=$'\033[34m'; C_CYN=$'\033[36m'; C_RST=$'\033[0m'; C_BLD=$'\033[1m'
else
    C_RED=; C_GRN=; C_YEL=; C_BLU=; C_CYN=; C_RST=; C_BLD=
fi

_ts() { date +%H:%M:%S; }
log()  { printf '%s\n' "$*" >>"$LOG"; }
say()  { printf '%s\n' "$*"; log "$*"; }
step() { printf '\n%s[%s] ==> %s%s\n' "$C_CYN$C_BLD" "$(_ts)" "$*" "$C_RST"; log "==> $*"; }
ok()   { printf '  %s[ OK ]%s %s\n' "$C_GRN" "$C_RST" "$*"; log "[ OK ] $*"; }
warn() { printf '  %s[WARN]%s %s\n' "$C_YEL" "$C_RST" "$*"; log "[WARN] $*"; }
err()  { printf '  %s[FAIL]%s %s\n' "$C_RED" "$C_RST" "$*"; log "[FAIL] $*"; }

have() { command -v "$1" >/dev/null 2>&1; }

# Run a command, tee its output into the log file.
run() {
    log "\$ $*"
    "$@" >>"$LOG" 2>&1
}

# retry <max> <cmd...> — retry with exponential backoff (4,8,16,...).
retry() {
    local max="$1"; shift
    local n=1 delay=4
    while true; do
        if run "$@"; then
            return 0
        fi
        if [ "$n" -ge "$max" ]; then
            warn "gave up after $n attempts: $*"
            return 1
        fi
        warn "attempt $n/$max failed, retrying in ${delay}s: $*"
        sleep "$delay"
        n=$((n + 1))
        delay=$((delay * 2))
    done
}

# -----------------------------------------------------------------------------
# Package helpers
# -----------------------------------------------------------------------------
pkg_install() {           # required packages — failure is logged as WARN
    if retry 3 pkg install -y "$@"; then
        ok "pkg: $*"
    else
        warn "pkg install failed (will try apt fallback): $*"
        retry 2 apt-get install -y "$@" && ok "apt: $*" || warn "could not install: $*"
    fi
}

# Ensure a shared/base venv exists that can see Termux's compiled site-packages.
new_venv() {              # new_venv <dir>
    local dir="$1"
    [ -d "$dir" ] && return 0
    retry 2 python3 -m venv --system-site-packages "$dir"
}

# make_wrapper <name> <command-body> — create an executable on PATH that any
# shell (including fish) can call by name.
make_wrapper() {
    local name="$1" body="$2"
    local path="$OSINT_BIN/$name"
    {
        echo '#!/data/data/com.termux/files/usr/bin/bash'
        echo "$body"
    } >"$path"
    chmod +x "$path"
    log "wrapper created: $path"
}

# clone_repo <url> <dir> — clone (shallow) or update an existing clone.
clone_repo() {
    local url="$1" dir="$2"
    if [ -d "$dir/.git" ]; then
        ok "repo present: $dir"
        [ "$FORCE" = "1" ] && { retry 3 git -C "$dir" pull --ff-only || warn "update failed: $dir"; }
        return 0
    fi
    retry 3 git clone --depth 1 "$url" "$dir"
}

# skip_if_present <primary-binary> — returns 0 (skip) when already installed and
# --force was not given.
skip_if_present() {
    [ "$FORCE" = "1" ] && return 1
    have "$1"
}

# -----------------------------------------------------------------------------
# Python tool installer (isolated). pipx first, per-tool venv as fallback.
# usage: py_tool <name> <pip-spec> <bin1> [bin2 ...]
# -----------------------------------------------------------------------------
py_tool() {
    local name="$1" spec="$2"; shift 2
    local bins=("$@")
    local primary="${bins[0]}"

    step "Python tool: $name"
    if skip_if_present "$primary"; then
        ok "$name already installed (use --force to reinstall)"
        SKIPPED+=("$name")
        return 0
    fi

    # 1) pipx, sharing Termux's compiled wheels (lxml/cryptography/pillow/...).
    if have pipx; then
        local pipx_args=(install --system-site-packages)
        [ "$FORCE" = "1" ] && pipx_args=(install --force --system-site-packages)
        if retry 3 pipx "${pipx_args[@]}" "$spec"; then
            ok "$name installed via pipx"
            INSTALLED+=("$name")
            return 0
        fi
        warn "$name: pipx failed, falling back to a dedicated venv"
    fi

    # 2) Dedicated venv fallback with wrapper symlinks.
    local vdir="$OSINT_VENVS/$name"
    rm -rf "$vdir"
    if new_venv "$vdir" \
        && retry 2 "$vdir/bin/pip" install --upgrade pip wheel setuptools \
        && retry 3 "$vdir/bin/pip" install "$spec"; then
        local b linked=0
        for b in "${bins[@]}"; do
            if [ -x "$vdir/bin/$b" ]; then
                ln -sf "$vdir/bin/$b" "$OSINT_BIN/$b"
                linked=1
            fi
        done
        if [ "$linked" = "1" ]; then
            ok "$name installed via venv fallback"
            INSTALLED+=("$name")
            return 0
        fi
        warn "$name installed but no expected entrypoint found (${bins[*]})"
    fi

    err "$name could not be installed"
    FAILED+=("$name")
    return 1
}

# =============================================================================
# CHECK MODE
# =============================================================================
check_tools() {
    printf '\n%s=== OSINT TOOL STATUS ===%s\n\n' "$C_BLD" "$C_RST"
    local t
    for t in sherlock maigret holehe socialscan blackbird \
             theHarvester sublist3r photon spiderfoot recon-ng \
             sqlmap subfinder httpx katana dnsx gau waybackurls amass; do
        if have "$t"; then
            printf '  %s[ OK ]%s %s\n' "$C_GRN" "$C_RST" "$t"
        else
            printf '  %s[ -- ]%s %s (missing)\n' "$C_RED" "$C_RST" "$t"
        fi
    done
    echo
    printf 'Wrapper dir : %s\n' "$OSINT_BIN"
    printf 'pipx bin    : %s\n' "$HOME/.local/bin"
    printf 'go bin      : %s\n' "$GOBIN"
    echo
    printf 'If a tool shows [ -- ] but you installed it, restart fish or run:\n'
    printf '  source ~/.config/fish/config.fish\n\n'
}

# =============================================================================
# INSTALL STEPS
# =============================================================================

preflight() {
    step "Preflight checks"
    mkdir -p "$OSINT_BIN" "$OSINT_VENVS" "$OSINT_REPOS" "$OSINT_LOGS"

    say "Log file : $LOG"
    say "Arch     : $(uname -m)"
    say "Prefix   : $PREFIX"

    if ! printf '%s' "$PREFIX" | grep -q 'com.termux'; then
        warn "This does not look like Termux (\$PREFIX=$PREFIX)."
        warn "The script will continue but package installs may behave differently."
    fi

    case "$(uname -m)" in
        aarch64|arm64) ok "ARM64 detected — good." ;;
        arm|armv7l)    warn "32-bit ARM detected — some Go tools may be slow/unavailable." ;;
        *)             warn "Unexpected arch $(uname -m) — proceeding anyway." ;;
    esac
}

install_base() {
    step "Updating package index"
    if ! retry 3 pkg update -y; then
        warn "pkg update failed, trying apt with release-info override"
        retry 2 apt-get update -o Acquire::AllowReleaseInfoChange=true -y || \
            warn "index update failed — installs may still work from cache"
    fi

    step "Installing base + build dependencies"
    # Build prereqs so Python C-extensions (lxml, cryptography, pillow, cffi,
    # aiohttp) compile cleanly on device instead of failing.
    pkg_install git python python-pip rust clang make binutils pkg-config \
        openssl libffi libxml2 libxslt libjpeg-turbo freetype zlib \
        which curl wget

    step "Bootstrapping pip + pipx"
    retry 2 python3 -m pip install --upgrade pip wheel setuptools || warn "pip self-upgrade failed"
    if ! have pipx; then
        retry 3 python3 -m pip install --user pipx || warn "pipx install failed — venv fallback will be used"
    fi
    # Make sure pipx's bin dir will exist and be on PATH.
    mkdir -p "$HOME/.local/bin"
    if have pipx; then
        run python3 -m pipx ensurepath || true
        ok "pipx ready"
    fi
}

install_python_tools() {
    py_tool "sherlock"    "sherlock-project"                                    sherlock
    py_tool "maigret"     "maigret"                                             maigret
    py_tool "holehe"      "holehe"                                              holehe
    py_tool "socialscan"  "socialscan"                                          socialscan
    py_tool "theHarvester" "git+https://github.com/laramies/theHarvester.git"   theHarvester restfulHarvest
}

install_blackbird() {
    step "blackbird (username OSINT, GitHub)"
    if skip_if_present blackbird; then ok "blackbird already installed"; SKIPPED+=("blackbird"); return; fi
    local dir="$OSINT_REPOS/blackbird"
    if clone_repo "https://github.com/p1ngul1n0/blackbird.git" "$dir"; then
        local vdir="$OSINT_VENVS/blackbird"
        rm -rf "$vdir"
        if new_venv "$vdir" && retry 3 "$vdir/bin/pip" install -r "$dir/requirements.txt"; then
            make_wrapper "blackbird" "cd \"$dir\" && exec \"$vdir/bin/python\" blackbird.py \"\$@\""
            ok "blackbird installed"; INSTALLED+=("blackbird")
        else
            err "blackbird deps failed"; FAILED+=("blackbird")
        fi
    else
        err "blackbird clone failed"; FAILED+=("blackbird")
    fi
}

install_sqlmap() {
    step "sqlmap (SQL injection, GitHub)"
    if skip_if_present sqlmap; then ok "sqlmap already installed"; SKIPPED+=("sqlmap"); return; fi
    local dir="$OSINT_REPOS/sqlmap"
    if clone_repo "https://github.com/sqlmapproject/sqlmap.git" "$dir"; then
        make_wrapper "sqlmap" "exec python3 \"$dir/sqlmap.py\" \"\$@\""
        ok "sqlmap installed (pure-python, no extra deps)"; INSTALLED+=("sqlmap")
    else
        err "sqlmap clone failed"; FAILED+=("sqlmap")
    fi
}

install_sublist3r() {
    step "Sublist3r (subdomains, GitHub)"
    if skip_if_present sublist3r; then ok "sublist3r already installed"; SKIPPED+=("sublist3r"); return; fi
    local dir="$OSINT_REPOS/Sublist3r"
    if clone_repo "https://github.com/aboul3la/Sublist3r.git" "$dir"; then
        local vdir="$OSINT_VENVS/sublist3r"
        rm -rf "$vdir"
        if new_venv "$vdir" && retry 3 "$vdir/bin/pip" install -r "$dir/requirements.txt"; then
            make_wrapper "sublist3r" "exec \"$vdir/bin/python\" \"$dir/sublist3r.py\" \"\$@\""
            ok "sublist3r installed"; INSTALLED+=("sublist3r")
        else
            err "sublist3r deps failed"; FAILED+=("sublist3r")
        fi
    else
        err "sublist3r clone failed"; FAILED+=("sublist3r")
    fi
}

install_photon() {
    step "Photon (web crawler/OSINT, GitHub)"
    if skip_if_present photon; then ok "photon already installed"; SKIPPED+=("photon"); return; fi
    local dir="$OSINT_REPOS/Photon"
    if clone_repo "https://github.com/s0md3v/Photon.git" "$dir"; then
        local vdir="$OSINT_VENVS/photon"
        rm -rf "$vdir"
        if new_venv "$vdir" && retry 3 "$vdir/bin/pip" install -r "$dir/requirements.txt"; then
            make_wrapper "photon" "exec \"$vdir/bin/python\" \"$dir/photon.py\" \"\$@\""
            ok "photon installed"; INSTALLED+=("photon")
        else
            err "photon deps failed"; FAILED+=("photon")
        fi
    else
        err "photon clone failed"; FAILED+=("photon")
    fi
}

install_spiderfoot() {
    step "SpiderFoot (OSINT automation framework, GitHub)"
    if skip_if_present spiderfoot; then ok "spiderfoot already installed"; SKIPPED+=("spiderfoot"); return; fi
    local dir="$OSINT_REPOS/spiderfoot"
    if clone_repo "https://github.com/smicallef/spiderfoot.git" "$dir"; then
        local vdir="$OSINT_VENVS/spiderfoot"
        rm -rf "$vdir"
        if new_venv "$vdir" \
            && retry 2 "$vdir/bin/pip" install --upgrade pip wheel \
            && retry 3 "$vdir/bin/pip" install -r "$dir/requirements.txt"; then
            # CLI scanner wrapper
            make_wrapper "spiderfoot" "cd \"$dir\" && exec \"$vdir/bin/python\" sf.py \"\$@\""
            # Web UI helper (http://127.0.0.1:5001)
            make_wrapper "spiderfoot-web" "cd \"$dir\" && exec \"$vdir/bin/python\" sf.py -l 127.0.0.1:5001"
            ok "spiderfoot installed (CLI: 'spiderfoot', Web UI: 'spiderfoot-web')"; INSTALLED+=("spiderfoot")
        else
            err "spiderfoot deps failed"; FAILED+=("spiderfoot")
        fi
    else
        err "spiderfoot clone failed"; FAILED+=("spiderfoot")
    fi
}

install_reconng() {
    step "recon-ng (reconnaissance framework, GitHub)"
    if skip_if_present recon-ng; then ok "recon-ng already installed"; SKIPPED+=("recon-ng"); return; fi
    local dir="$OSINT_REPOS/recon-ng"
    if clone_repo "https://github.com/lanmaster53/recon-ng.git" "$dir"; then
        local vdir="$OSINT_VENVS/recon-ng"
        rm -rf "$vdir"
        if new_venv "$vdir" \
            && retry 2 "$vdir/bin/pip" install --upgrade pip wheel \
            && retry 3 "$vdir/bin/pip" install -r "$dir/REQUIREMENTS"; then
            make_wrapper "recon-ng" "cd \"$dir\" && exec \"$vdir/bin/python\" recon-ng \"\$@\""
            make_wrapper "recon-cli" "cd \"$dir\" && exec \"$vdir/bin/python\" recon-cli \"\$@\""
            ok "recon-ng installed"; INSTALLED+=("recon-ng")
        else
            err "recon-ng deps failed"; FAILED+=("recon-ng")
        fi
    else
        err "recon-ng clone failed"; FAILED+=("recon-ng")
    fi
}

install_go_tools() {
    if [ "$DO_GO" != "1" ]; then
        step "Go tools skipped (--skip-go)"
        return
    fi
    step "Installing Go toolchain (for modern ProjectDiscovery recon tools)"
    if ! have go; then
        pkg_install golang
    fi
    if ! have go; then
        warn "Go unavailable — skipping subfinder/httpx/katana/dnsx/gau/waybackurls/amass"
        FAILED+=("go-toolchain")
        return
    fi
    mkdir -p "$GOBIN"
    export GOBIN GOFLAGS="-buildvcs=false"

    # name:module pairs
    local specs=(
        "subfinder:github.com/projectdiscovery/subfinder/v2/cmd/subfinder@latest"
        "httpx:github.com/projectdiscovery/httpx/cmd/httpx@latest"
        "katana:github.com/projectdiscovery/katana/cmd/katana@latest"
        "dnsx:github.com/projectdiscovery/dnsx/cmd/dnsx@latest"
        "gau:github.com/lc/gau/v2/cmd/gau@latest"
        "waybackurls:github.com/tomnomnom/waybackurls@latest"
        "amass:github.com/owasp-amass/amass/v4/...@master"
    )
    local pair name mod
    for pair in "${specs[@]}"; do
        name="${pair%%:*}"
        mod="${pair#*:}"
        step "go install $name"
        if skip_if_present "$name"; then ok "$name already installed"; SKIPPED+=("$name"); continue; fi
        if retry 2 go install "$mod"; then
            ok "$name installed"; INSTALLED+=("$name")
        else
            err "$name build failed (Go tools can be memory-heavy on device)"; FAILED+=("$name")
        fi
    done
}

# -----------------------------------------------------------------------------
# Shell integration — make everything callable from fish (and bash).
# -----------------------------------------------------------------------------
configure_shells() {
    step "Wiring tools into fish and bash PATH"

    # Install an `osint` convenience command that re-runs this installer
    # (e.g. `osint --check`, `osint --force`).
    local self
    self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
    make_wrapper "osint" "exec bash \"$self\" \"\$@\""
    ok "convenience command installed: osint [--check|--force|--skip-go]"

    # ---- fish ----
    local fish_dir="$HOME/.config/fish"
    local fish_cfg="$fish_dir/config.fish"
    mkdir -p "$fish_dir"
    touch "$fish_cfg"
    if ! grep -q 'pen15 osint installer' "$fish_cfg" 2>/dev/null; then
        {
            echo ''
            echo '# >>> pen15 osint installer >>>'
            echo "fish_add_path -g $OSINT_BIN $HOME/.local/bin $GOBIN"
            echo '# <<< pen15 osint installer <<<'
        } >>"$fish_cfg"
        ok "fish config updated ($fish_cfg)"
    else
        ok "fish config already wired"
    fi

    # ---- bash (in case tools are launched from bash too) ----
    local bashrc="$HOME/.bashrc"
    touch "$bashrc"
    if ! grep -q 'pen15 osint installer' "$bashrc" 2>/dev/null; then
        {
            echo ''
            echo '# >>> pen15 osint installer >>>'
            echo "export PATH=\"$OSINT_BIN:\$HOME/.local/bin:$GOBIN:\$PATH\""
            echo '# <<< pen15 osint installer <<<'
        } >>"$bashrc"
        ok "bash config updated ($bashrc)"
    else
        ok "bash config already wired"
    fi
}

# -----------------------------------------------------------------------------
# Summary + failure report (the "send it back to you" artefact)
# -----------------------------------------------------------------------------
write_report() {
    {
        echo "==================== Pen15 OSINT install report ===================="
        echo "Date        : $(date)"
        echo "Arch        : $(uname -m)"
        echo "Termux PREFIX: $PREFIX"
        echo "Python      : $(python3 --version 2>&1)"
        echo "pipx        : $(pipx --version 2>/dev/null || echo 'not installed')"
        echo "Go          : $(go version 2>/dev/null || echo 'not installed')"
        echo
        echo "INSTALLED (${#INSTALLED[@]}): ${INSTALLED[*]:-none}"
        echo "SKIPPED   (${#SKIPPED[@]}): ${SKIPPED[*]:-none}"
        echo "FAILED    (${#FAILED[@]}): ${FAILED[*]:-none}"
        echo
        if [ "${#FAILED[@]}" -gt 0 ]; then
            echo "-------------------- last 200 log lines --------------------"
            tail -n 200 "$LOG"
        fi
        echo "===================================================================="
    } >"$REPORT"
}

summary() {
    write_report
    printf '\n%s================ SUMMARY ================%s\n' "$C_BLD" "$C_RST"
    printf '%sInstalled%s : %s\n' "$C_GRN" "$C_RST" "${INSTALLED[*]:-none}"
    printf '%sSkipped%s   : %s\n'   "$C_YEL" "$C_RST" "${SKIPPED[*]:-none}"
    printf '%sFailed%s    : %s\n'    "$C_RED" "$C_RST" "${FAILED[*]:-none}"
    echo
    printf 'Full log : %s\n' "$LOG"
    printf 'Report   : %s\n' "$REPORT"
    echo
    if [ "${#FAILED[@]}" -gt 0 ]; then
        printf '%sSome tools failed.%s To get them fixed, copy the report and send it back:\n' "$C_YEL" "$C_RST"
        printf '  cat %s\n' "$REPORT"
        printf '(or in the app: share this file). It contains the exact errors so\n'
        printf 'the failing steps can be patched directly into this script.\n\n'
    else
        printf '%sAll requested tools installed successfully.%s\n\n' "$C_GRN" "$C_RST"
    fi
    printf 'Start a new fish session (or run: source ~/.config/fish/config.fish) then try:\n'
    printf '  sherlock <username>\n'
    printf '  theHarvester -d example.com -b all\n'
    printf '  sqlmap -u "http://target/?id=1" --batch\n'
    printf '  spiderfoot-web        # then open http://127.0.0.1:5001\n'
    printf '  osint --check         # re-run this script with --check anytime\n\n'
}

# =============================================================================
# MAIN
# =============================================================================
main() {
    mkdir -p "$OSINT_LOGS"
    : >"$LOG"

    if [ "$CHECK_ONLY" = "1" ]; then
        check_tools
        exit 0
    fi

    printf '%s========================================%s\n' "$C_BLU$C_BLD" "$C_RST"
    printf '%s  Pen15 OSINT installer for Termux/fish %s\n' "$C_BLU$C_BLD" "$C_RST"
    printf '%s========================================%s\n' "$C_BLU$C_BLD" "$C_RST"

    preflight
    install_base

    # Python PyPI tools (isolated).
    install_python_tools

    # GitHub-cloned tools (not in pkg/apt).
    install_blackbird
    install_sqlmap
    install_sublist3r
    install_photon
    install_spiderfoot
    install_reconng

    # Modern Go recon suite.
    install_go_tools

    configure_shells
    summary
}

main "$@"
