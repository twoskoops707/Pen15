#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# install_osint_tools.sh — OSINT toolkit installer for Termux on ARM Android
# ------------------------------------------------------------------------------
# Target:  Samsung / any aarch64 Android running Termux (from F-Droid)
# Shell:   Runs under bash (Termux default). Fish integration via osint.fish.
#
# What this does:
#   1. Bootstraps Termux packages (python, go, rust, git, build deps, …)
#   2. Clones + installs the major OSINT tools from GitHub into
#        $HOME/osint-tools/<tool>
#   3. Creates per-tool launcher shims in $PREFIX/bin so `sherlock`, `sqlmap`,
#        `spiderfoot`, `maigret`, `theharvester`, etc. just work in fish.
#   4. Retries flaky network operations, records per-tool status, and writes
#        an install report to $HOME/osint-tools/install-report.txt.
#   5. On failure, prints a copy-pasteable error block so you can hand it back
#        to an AI (or to me) for a fix.
#
# Usage:
#   bash install_osint_tools.sh                     # install everything
#   bash install_osint_tools.sh --only sherlock,sqlmap
#   bash install_osint_tools.sh --skip spiderfoot,ghunt
#   bash install_osint_tools.sh --category social   # social|email|phone|domain|web|vuln|framework|secrets|all
#   bash install_osint_tools.sh --list              # list tool ids and exit
#   bash install_osint_tools.sh --update            # git pull each installed tool
#   bash install_osint_tools.sh --uninstall <id>    # remove one tool
#   bash install_osint_tools.sh --report            # print last install report
#
# Legal:  For AUTHORIZED testing / OSINT on systems you own or have written
#         permission to assess. You are responsible for how you use these tools.
# ==============================================================================

set -u
set -o pipefail

# ---- Environment sanity ------------------------------------------------------

if [ -z "${PREFIX:-}" ]; then
    # Not running inside Termux? Try to detect anyway.
    if [ -d "/data/data/com.termux/files/usr" ]; then
        export PREFIX="/data/data/com.termux/files/usr"
        export HOME="${HOME:-/data/data/com.termux/files/home}"
    else
        echo "ERROR: PREFIX is not set and /data/data/com.termux/files/usr is missing."
        echo "This script must run inside Termux (install from F-Droid)."
        exit 1
    fi
fi

OSINT_ROOT="${OSINT_ROOT:-$HOME/osint-tools}"
OSINT_BIN="$PREFIX/bin"
OSINT_LOG_DIR="$OSINT_ROOT/logs"
OSINT_REPORT="$OSINT_ROOT/install-report.txt"
OSINT_STATE="$OSINT_ROOT/.state"

mkdir -p "$OSINT_ROOT" "$OSINT_LOG_DIR" "$OSINT_STATE"

# ---- Colors (respect NO_COLOR) ----------------------------------------------

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    C_RESET="$(printf '\033[0m')"
    C_BOLD="$(printf '\033[1m')"
    C_RED="$(printf '\033[31m')"
    C_GREEN="$(printf '\033[32m')"
    C_YELLOW="$(printf '\033[33m')"
    C_BLUE="$(printf '\033[34m')"
    C_CYAN="$(printf '\033[36m')"
else
    C_RESET=""; C_BOLD=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_CYAN=""
fi

log()   { printf '%s[+]%s %s\n' "$C_GREEN"  "$C_RESET" "$*"; }
info()  { printf '%s[i]%s %s\n' "$C_CYAN"   "$C_RESET" "$*"; }
warn()  { printf '%s[!]%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
err()   { printf '%s[x]%s %s\n' "$C_RED"    "$C_RESET" "$*" >&2; }
step()  { printf '\n%s==>%s %s%s%s\n' "$C_BLUE" "$C_RESET" "$C_BOLD" "$*" "$C_RESET"; }

# ---- Global status tracking --------------------------------------------------

declare -a INSTALLED=()
declare -a SKIPPED=()
declare -a FAILED=()
declare -A FAIL_LOG=()
declare -A FAIL_CMD=()

record_ok()   { INSTALLED+=("$1"); : > "$OSINT_STATE/$1.ok"; rm -f "$OSINT_STATE/$1.fail"; }
record_skip() { SKIPPED+=("$1"); }
record_fail() {
    local id="$1" cmd="${2:-}" logfile="${3:-}"
    FAILED+=("$id")
    FAIL_CMD["$id"]="$cmd"
    FAIL_LOG["$id"]="$logfile"
    date -u +'%Y-%m-%dT%H:%M:%SZ' > "$OSINT_STATE/$id.fail"
}

# ---- retry: run a command up to N times with exponential backoff ------------

retry() {
    # retry <tries> <sleep_base_seconds> -- <cmd...>
    local tries="$1" base="$2"; shift 2
    [ "$1" = "--" ] && shift
    local attempt=1 rc=0
    while [ "$attempt" -le "$tries" ]; do
        "$@"
        rc=$?
        if [ "$rc" -eq 0 ]; then return 0; fi
        if [ "$attempt" -lt "$tries" ]; then
            local delay=$(( base * (2 ** (attempt - 1)) ))
            warn "attempt $attempt/$tries failed (exit $rc): $* — retrying in ${delay}s"
            sleep "$delay"
        fi
        attempt=$(( attempt + 1 ))
    done
    return "$rc"
}

# ---- run_logged: run a command, tee to per-tool log, return exit code -------

run_logged() {
    # run_logged <tool_id> <logline_label> -- <cmd...>
    local id="$1" label="$2"; shift 2
    [ "$1" = "--" ] && shift
    local logfile="$OSINT_LOG_DIR/${id}.log"
    {
        printf '\n===== %s | %s =====\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$label"
        printf 'CMD: %s\n' "$*"
    } >> "$logfile"
    "$@" >> "$logfile" 2>&1
    return $?
}

# ---- print copy-pasteable failure block --------------------------------------

print_failure_block() {
    local id="$1"
    local cmd="${FAIL_CMD[$id]:-<unknown>}"
    local logfile="${FAIL_LOG[$id]:-$OSINT_LOG_DIR/${id}.log}"
    printf '\n%s────────── FAILURE REPORT: %s ──────────%s\n' "$C_RED" "$id" "$C_RESET" >&2
    printf 'tool:     %s\n' "$id" >&2
    printf 'command:  %s\n' "$cmd" >&2
    printf 'logfile:  %s\n' "$logfile" >&2
    printf 'host:     %s / %s\n' "$(uname -m)" "$(uname -sr)" >&2
    printf 'termux:   PREFIX=%s\n' "$PREFIX" >&2
    printf '\n----- last 60 log lines -----\n' >&2
    if [ -f "$logfile" ]; then
        tail -n 60 "$logfile" >&2
    else
        printf '(no log captured)\n' >&2
    fi
    printf '\n----- copy the block above and paste it into your AI chat to get a fix -----\n\n' >&2
}

# ---- pkg_install: install a Termux pkg (idempotent, retry) ------------------

pkg_install() {
    local p
    for p in "$@"; do
        if pkg list-installed 2>/dev/null | grep -q "^${p}/"; then
            info "pkg already installed: $p"
            continue
        fi
        log "installing pkg: $p"
        if ! retry 3 5 -- pkg install -y "$p" >/dev/null 2>>"$OSINT_LOG_DIR/pkg.log"; then
            warn "pkg install failed for $p — see $OSINT_LOG_DIR/pkg.log"
            return 1
        fi
    done
}

# ---- pip_install: pip install with build env for Termux aarch64 -------------

pip_install() {
    # Termux-specific env so lxml / cryptography / pillow build cleanly on aarch64
    LDFLAGS="${LDFLAGS:-} -L$PREFIX/lib" \
    CFLAGS="${CFLAGS:-} -I$PREFIX/include" \
    CARGO_BUILD_TARGET="${CARGO_BUILD_TARGET:-aarch64-linux-android}" \
    pip install --upgrade --no-cache-dir --prefer-binary "$@"
}

# ---- git_clone_or_update: clone if missing, pull if present, retry ----------

git_clone_or_update() {
    # git_clone_or_update <repo_url> <dest_dir>
    local url="$1" dest="$2"
    if [ -d "$dest/.git" ]; then
        info "updating $(basename "$dest")"
        ( cd "$dest" && retry 3 5 -- git pull --ff-only ) || return $?
    else
        log "cloning $(basename "$dest")"
        retry 3 5 -- git clone --depth 1 "$url" "$dest" || return $?
    fi
}

# ---- make_shim: create a launcher shim in $PREFIX/bin -----------------------

make_shim() {
    # make_shim <shim_name> <shim_body>
    local name="$1"; shift
    local body="$*"
    local path="$OSINT_BIN/$name"
    cat > "$path" <<EOF
#!$PREFIX/bin/bash
# Generated by install_osint_tools.sh — do not edit; regenerate via the script.
$body
EOF
    chmod +x "$path"
}

# ==============================================================================
# BOOTSTRAP — Termux packages we need across most tools
# ==============================================================================

bootstrap_termux() {
    step "Bootstrapping Termux base packages"

    if ! command -v pkg >/dev/null 2>&1; then
        err "'pkg' not found — are you running inside Termux?"
        exit 1
    fi

    log "updating pkg metadata"
    retry 3 5 -- pkg update -y >>"$OSINT_LOG_DIR/pkg.log" 2>&1 || warn "pkg update had issues (continuing)"
    retry 2 5 -- pkg upgrade -y >>"$OSINT_LOG_DIR/pkg.log" 2>&1 || warn "pkg upgrade had issues (continuing)"

    # Core build + runtime deps.  Ordered by importance so partial installs
    # still leave the most-used tools working.
    local core_pkgs=(
        git curl wget openssh
        python python-pip
        rust
        clang make cmake pkg-config
        libxml2 libxslt libjpeg-turbo zlib
        libffi openssl openssl-tool
        binutils ldns
        nmap
        nodejs
        ruby
        perl
        tor
        termux-api
    )
    for p in "${core_pkgs[@]}"; do
        pkg_install "$p" || warn "skipping missing package: $p"
    done

    # golang is huge (~500MB) so install it separately with its own retry
    log "installing golang (may take a while — needed for amass/subfinder/httpx/nuclei/phoneinfoga/trufflehog)"
    if ! pkg_install golang; then
        warn "golang install failed — Go-based tools will be skipped"
    fi

    # pipx makes it easier to install python tools with their own venvs
    log "ensuring pipx is available"
    if ! command -v pipx >/dev/null 2>&1; then
        pip_install pipx >>"$OSINT_LOG_DIR/pkg.log" 2>&1 || warn "pipx install failed"
        # add pipx path
        if command -v pipx >/dev/null 2>&1; then
            pipx ensurepath >/dev/null 2>&1 || true
        fi
    fi

    # Make sure ~/.local/bin exists and is on PATH for shims
    mkdir -p "$HOME/.local/bin"

    # GOPATH bin
    export GOPATH="${GOPATH:-$HOME/go}"
    export GOBIN="${GOBIN:-$GOPATH/bin}"
    mkdir -p "$GOBIN"

    log "bootstrap complete"
}

# ==============================================================================
# TOOL DEFINITIONS
# Each installer function is named install_<id> and records status.
# Categories: social | email | phone | domain | web | vuln | framework | secrets
# ==============================================================================

# ---------- SOCIAL / USERNAME OSINT ------------------------------------------

install_sherlock() {
    local id="sherlock" dest="$OSINT_ROOT/sherlock"
    step "sherlock — hunt usernames across social networks"
    if ! git_clone_or_update "https://github.com/sherlock-project/sherlock.git" "$dest"; then
        record_fail "$id" "git clone sherlock" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    if ! run_logged "$id" "pip install -r requirements" -- pip_install -r "$dest/requirements.txt"; then
        record_fail "$id" "pip install sherlock requirements" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    make_shim "sherlock" "exec $PREFIX/bin/python \"$dest/sherlock_project/sherlock.py\" \"\$@\""
    # older layout fallback
    if [ ! -f "$dest/sherlock_project/sherlock.py" ] && [ -f "$dest/sherlock/sherlock.py" ]; then
        make_shim "sherlock" "exec $PREFIX/bin/python \"$dest/sherlock/sherlock.py\" \"\$@\""
    fi
    record_ok "$id"
}

install_maigret() {
    local id="maigret"
    step "maigret — modern username OSINT (2500+ sites)"
    if ! run_logged "$id" "pipx install maigret" -- pipx install --force maigret; then
        # fallback: pip user install
        if ! run_logged "$id" "pip install --user maigret" -- pip_install --user maigret; then
            record_fail "$id" "pipx install maigret" "$OSINT_LOG_DIR/${id}.log"; return
        fi
    fi
    record_ok "$id"
}

install_blackbird() {
    local id="blackbird" dest="$OSINT_ROOT/blackbird"
    step "blackbird — search accounts by username / email"
    if ! git_clone_or_update "https://github.com/p1ngul1n0/blackbird.git" "$dest"; then
        record_fail "$id" "git clone blackbird" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    if [ -f "$dest/requirements.txt" ]; then
        if ! run_logged "$id" "pip install blackbird deps" -- pip_install -r "$dest/requirements.txt"; then
            record_fail "$id" "pip install blackbird" "$OSINT_LOG_DIR/${id}.log"; return
        fi
    fi
    make_shim "blackbird" "cd \"$dest\" && exec $PREFIX/bin/python blackbird.py \"\$@\""
    record_ok "$id"
}

install_social_analyzer() {
    local id="social-analyzer"
    step "social-analyzer — find profiles by name across 1000+ sites"
    # Prefer npm install if node is present (official recommendation)
    if command -v npm >/dev/null 2>&1; then
        if run_logged "$id" "npm i -g social-analyzer" -- npm install -g social-analyzer; then
            record_ok "$id"; return
        fi
        warn "npm install failed; trying pip fallback"
    fi
    if ! run_logged "$id" "pip install social-analyzer" -- pip_install social-analyzer; then
        record_fail "$id" "install social-analyzer" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    record_ok "$id"
}

# ---------- EMAIL / BREACH OSINT ---------------------------------------------

install_theharvester() {
    local id="theharvester" dest="$OSINT_ROOT/theHarvester"
    step "theHarvester — emails, subdomains, hosts, employee names"
    if ! git_clone_or_update "https://github.com/laramies/theHarvester.git" "$dest"; then
        record_fail "$id" "git clone theHarvester" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    if ! run_logged "$id" "pip install theHarvester deps" -- pip_install -r "$dest/requirements/base.txt"; then
        # try old-layout requirements.txt
        if [ -f "$dest/requirements.txt" ]; then
            run_logged "$id" "pip install requirements.txt" -- pip_install -r "$dest/requirements.txt" || {
                record_fail "$id" "pip install theHarvester requirements" "$OSINT_LOG_DIR/${id}.log"; return
            }
        else
            record_fail "$id" "pip install theHarvester requirements" "$OSINT_LOG_DIR/${id}.log"; return
        fi
    fi
    make_shim "theharvester" "cd \"$dest\" && exec $PREFIX/bin/python theHarvester.py \"\$@\""
    record_ok "$id"
}

install_holehe() {
    local id="holehe"
    step "holehe — check if an email is used on 100+ sites"
    if ! run_logged "$id" "pipx install holehe" -- pipx install --force holehe; then
        if ! run_logged "$id" "pip install --user holehe" -- pip_install --user holehe; then
            record_fail "$id" "install holehe" "$OSINT_LOG_DIR/${id}.log"; return
        fi
    fi
    record_ok "$id"
}

install_h8mail() {
    local id="h8mail"
    step "h8mail — email OSINT + breach hunting"
    if ! run_logged "$id" "pipx install h8mail" -- pipx install --force h8mail; then
        if ! run_logged "$id" "pip install --user h8mail" -- pip_install --user h8mail; then
            record_fail "$id" "install h8mail" "$OSINT_LOG_DIR/${id}.log"; return
        fi
    fi
    record_ok "$id"
}

install_mosint() {
    local id="mosint"
    step "mosint — automated email OSINT (Go)"
    if ! command -v go >/dev/null 2>&1; then
        warn "go not installed — skipping mosint"; record_skip "$id"; return
    fi
    if ! run_logged "$id" "go install mosint" -- go install github.com/alpkeskin/mosint/v3/cmd/mosint@latest; then
        record_fail "$id" "go install mosint" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    # Symlink into $PREFIX/bin so it's on PATH without GOBIN
    ln -sf "$GOBIN/mosint" "$OSINT_BIN/mosint"
    record_ok "$id"
}

# ---------- PHONE OSINT -------------------------------------------------------

install_phoneinfoga() {
    local id="phoneinfoga"
    step "phoneinfoga — phone number OSINT (Go)"
    if ! command -v go >/dev/null 2>&1; then
        warn "go not installed — skipping phoneinfoga"; record_skip "$id"; return
    fi
    if ! run_logged "$id" "go install phoneinfoga" -- go install github.com/sundowndev/phoneinfoga/v2@latest; then
        record_fail "$id" "go install phoneinfoga" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    ln -sf "$GOBIN/phoneinfoga" "$OSINT_BIN/phoneinfoga"
    record_ok "$id"
}

install_ignorant() {
    local id="ignorant"
    step "ignorant — check if a phone number is used on sites"
    if ! run_logged "$id" "pipx install ignorant" -- pipx install --force ignorant; then
        if ! run_logged "$id" "pip install --user ignorant" -- pip_install --user ignorant; then
            record_fail "$id" "install ignorant" "$OSINT_LOG_DIR/${id}.log"; return
        fi
    fi
    record_ok "$id"
}

# ---------- DOMAIN / SUBDOMAIN / DNS -----------------------------------------

install_sublist3r() {
    local id="sublist3r" dest="$OSINT_ROOT/Sublist3r"
    step "Sublist3r — subdomain enumeration"
    if ! git_clone_or_update "https://github.com/aboul3la/Sublist3r.git" "$dest"; then
        record_fail "$id" "git clone Sublist3r" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    if ! run_logged "$id" "pip install sublist3r deps" -- pip_install -r "$dest/requirements.txt"; then
        record_fail "$id" "pip install Sublist3r deps" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    make_shim "sublist3r" "cd \"$dest\" && exec $PREFIX/bin/python sublist3r.py \"\$@\""
    record_ok "$id"
}

install_dnsrecon() {
    local id="dnsrecon" dest="$OSINT_ROOT/dnsrecon"
    step "dnsrecon — DNS enumeration"
    if ! git_clone_or_update "https://github.com/darkoperator/dnsrecon.git" "$dest"; then
        record_fail "$id" "git clone dnsrecon" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    if [ -f "$dest/requirements.txt" ]; then
        run_logged "$id" "pip install dnsrecon deps" -- pip_install -r "$dest/requirements.txt" || \
            warn "dnsrecon deps had issues (continuing)"
    fi
    make_shim "dnsrecon" "cd \"$dest\" && exec $PREFIX/bin/python dnsrecon.py \"\$@\""
    record_ok "$id"
}

install_amass() {
    local id="amass"
    step "amass — attack-surface mapping (Go)"
    if ! command -v go >/dev/null 2>&1; then
        warn "go not installed — skipping amass"; record_skip "$id"; return
    fi
    if ! run_logged "$id" "go install amass" -- go install github.com/owasp-amass/amass/v4/...@master; then
        record_fail "$id" "go install amass" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    ln -sf "$GOBIN/amass" "$OSINT_BIN/amass"
    record_ok "$id"
}

install_subfinder() {
    local id="subfinder"
    step "subfinder — passive subdomain discovery (ProjectDiscovery)"
    if ! command -v go >/dev/null 2>&1; then
        warn "go not installed — skipping subfinder"; record_skip "$id"; return
    fi
    if ! run_logged "$id" "go install subfinder" -- go install -v github.com/projectdiscovery/subfinder/v2/cmd/subfinder@latest; then
        record_fail "$id" "go install subfinder" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    ln -sf "$GOBIN/subfinder" "$OSINT_BIN/subfinder"
    record_ok "$id"
}

install_assetfinder() {
    local id="assetfinder"
    step "assetfinder — find related domains/subdomains (Go)"
    if ! command -v go >/dev/null 2>&1; then
        warn "go not installed — skipping assetfinder"; record_skip "$id"; return
    fi
    if ! run_logged "$id" "go install assetfinder" -- go install github.com/tomnomnom/assetfinder@latest; then
        record_fail "$id" "go install assetfinder" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    ln -sf "$GOBIN/assetfinder" "$OSINT_BIN/assetfinder"
    record_ok "$id"
}

# ---------- WEB / CRAWL / HTTP -----------------------------------------------

install_photon() {
    local id="photon" dest="$OSINT_ROOT/Photon"
    step "Photon — fast web crawler for OSINT"
    if ! git_clone_or_update "https://github.com/s0md3v/Photon.git" "$dest"; then
        record_fail "$id" "git clone Photon" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    if [ -f "$dest/requirements.txt" ]; then
        run_logged "$id" "pip install photon deps" -- pip_install -r "$dest/requirements.txt" || \
            warn "photon deps had issues (continuing)"
    fi
    make_shim "photon" "exec $PREFIX/bin/python \"$dest/photon.py\" \"\$@\""
    record_ok "$id"
}

install_httpx() {
    local id="httpx"
    step "httpx — fast HTTP probing (ProjectDiscovery)"
    if ! command -v go >/dev/null 2>&1; then
        warn "go not installed — skipping httpx"; record_skip "$id"; return
    fi
    if ! run_logged "$id" "go install httpx" -- go install -v github.com/projectdiscovery/httpx/cmd/httpx@latest; then
        record_fail "$id" "go install httpx" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    # Named `pdhttpx` to avoid clashing with python httpx and pkg httpie
    ln -sf "$GOBIN/httpx" "$OSINT_BIN/pdhttpx"
    record_ok "$id"
}

install_waybackurls() {
    local id="waybackurls"
    step "waybackurls — pull URLs from the Wayback Machine (Go)"
    if ! command -v go >/dev/null 2>&1; then
        warn "go not installed — skipping waybackurls"; record_skip "$id"; return
    fi
    if ! run_logged "$id" "go install waybackurls" -- go install github.com/tomnomnom/waybackurls@latest; then
        record_fail "$id" "go install waybackurls" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    ln -sf "$GOBIN/waybackurls" "$OSINT_BIN/waybackurls"
    record_ok "$id"
}

install_gau() {
    local id="gau"
    step "gau — getallurls (AlienVault OTX / Wayback / URLScan / CommonCrawl)"
    if ! command -v go >/dev/null 2>&1; then
        warn "go not installed — skipping gau"; record_skip "$id"; return
    fi
    if ! run_logged "$id" "go install gau" -- go install github.com/lc/gau/v2/cmd/gau@latest; then
        record_fail "$id" "go install gau" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    ln -sf "$GOBIN/gau" "$OSINT_BIN/gau"
    record_ok "$id"
}

install_katana() {
    local id="katana"
    step "katana — next-gen crawler (ProjectDiscovery)"
    if ! command -v go >/dev/null 2>&1; then
        warn "go not installed — skipping katana"; record_skip "$id"; return
    fi
    if ! run_logged "$id" "go install katana" -- go install github.com/projectdiscovery/katana/cmd/katana@latest; then
        record_fail "$id" "go install katana" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    ln -sf "$GOBIN/katana" "$OSINT_BIN/katana"
    record_ok "$id"
}

# ---------- VULN / INJECTION --------------------------------------------------

install_sqlmap() {
    local id="sqlmap" dest="$OSINT_ROOT/sqlmap"
    step "sqlmap — automated SQL injection"
    if ! git_clone_or_update "https://github.com/sqlmapproject/sqlmap.git" "$dest"; then
        record_fail "$id" "git clone sqlmap" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    make_shim "sqlmap" "exec $PREFIX/bin/python \"$dest/sqlmap.py\" \"\$@\""
    record_ok "$id"
}

install_nuclei() {
    local id="nuclei"
    step "nuclei — template-based vulnerability scanner"
    if ! command -v go >/dev/null 2>&1; then
        warn "go not installed — skipping nuclei"; record_skip "$id"; return
    fi
    if ! run_logged "$id" "go install nuclei" -- go install -v github.com/projectdiscovery/nuclei/v3/cmd/nuclei@latest; then
        record_fail "$id" "go install nuclei" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    ln -sf "$GOBIN/nuclei" "$OSINT_BIN/nuclei"
    # Pre-fetch templates (best-effort)
    run_logged "$id" "nuclei -update-templates" -- "$OSINT_BIN/nuclei" -update-templates || \
        warn "nuclei template fetch failed (run 'nuclei -update-templates' later)"
    record_ok "$id"
}

install_nikto() {
    local id="nikto" dest="$OSINT_ROOT/nikto"
    step "nikto — web server scanner (Perl)"
    if ! git_clone_or_update "https://github.com/sullo/nikto.git" "$dest"; then
        record_fail "$id" "git clone nikto" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    if [ ! -f "$dest/program/nikto.pl" ]; then
        record_fail "$id" "nikto layout unexpected — no program/nikto.pl" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    make_shim "nikto" "exec $PREFIX/bin/perl \"$dest/program/nikto.pl\" \"\$@\""
    record_ok "$id"
}

# ---------- FRAMEWORKS --------------------------------------------------------

install_spiderfoot() {
    local id="spiderfoot" dest="$OSINT_ROOT/spiderfoot"
    step "SpiderFoot — 200+ modules, web UI + CLI"
    if ! git_clone_or_update "https://github.com/smicallef/spiderfoot.git" "$dest"; then
        record_fail "$id" "git clone spiderfoot" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    if ! run_logged "$id" "pip install spiderfoot deps" -- pip_install -r "$dest/requirements.txt"; then
        # Try relaxed install (many deps have hard version pins that break on aarch64)
        warn "strict spiderfoot install failed; retrying with --no-deps + core libs"
        run_logged "$id" "pip install spiderfoot core deps" -- \
            pip_install lxml requests netaddr dnspython cherrypy mako pyyaml adblockparser openpyxl secure phonenumbers publicsuffixlist ipwhois || \
            warn "spiderfoot core deps had issues (some modules may not work)"
    fi
    make_shim "spiderfoot" "cd \"$dest\" && exec $PREFIX/bin/python sf.py \"\$@\""
    make_shim "sfcli" "cd \"$dest\" && exec $PREFIX/bin/python sfcli.py \"\$@\""
    record_ok "$id"
}

install_reconng() {
    local id="recon-ng" dest="$OSINT_ROOT/recon-ng"
    step "recon-ng — full-featured reconnaissance framework"
    if ! git_clone_or_update "https://github.com/lanmaster53/recon-ng.git" "$dest"; then
        record_fail "$id" "git clone recon-ng" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    if [ -f "$dest/REQUIREMENTS" ]; then
        run_logged "$id" "pip install recon-ng deps" -- pip_install -r "$dest/REQUIREMENTS" || \
            warn "recon-ng deps had issues (continuing)"
    fi
    make_shim "recon-ng" "cd \"$dest\" && exec $PREFIX/bin/python recon-ng \"\$@\""
    record_ok "$id"
}

# ---------- GOOGLE / SEARCH ---------------------------------------------------

install_ghunt() {
    local id="ghunt"
    step "GHunt — OSINT on Google accounts"
    if ! run_logged "$id" "pipx install ghunt" -- pipx install --force ghunt; then
        if ! run_logged "$id" "pip install --user ghunt" -- pip_install --user ghunt; then
            record_fail "$id" "install ghunt" "$OSINT_LOG_DIR/${id}.log"; return
        fi
    fi
    record_ok "$id"
}

# ---------- INSTAGRAM / SOCIAL -----------------------------------------------

install_osintgram() {
    local id="osintgram" dest="$OSINT_ROOT/Osintgram"
    step "Osintgram — Instagram OSINT"
    if ! git_clone_or_update "https://github.com/Datalux/Osintgram.git" "$dest"; then
        record_fail "$id" "git clone Osintgram" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    if [ -f "$dest/requirements.txt" ]; then
        run_logged "$id" "pip install osintgram deps" -- pip_install -r "$dest/requirements.txt" || \
            warn "osintgram deps had issues (continuing)"
    fi
    make_shim "osintgram" "cd \"$dest\" && exec $PREFIX/bin/python main.py \"\$@\""
    record_ok "$id"
}

install_toutatis() {
    local id="toutatis"
    step "toutatis — extract info from Instagram accounts"
    if ! run_logged "$id" "pipx install toutatis" -- pipx install --force toutatis; then
        if ! run_logged "$id" "pip install --user toutatis" -- pip_install --user toutatis; then
            record_fail "$id" "install toutatis" "$OSINT_LOG_DIR/${id}.log"; return
        fi
    fi
    record_ok "$id"
}

install_snscrape() {
    local id="snscrape"
    step "snscrape — scraper for social networks (twitter, reddit, telegram …)"
    if ! run_logged "$id" "pipx install snscrape" -- pipx install --force snscrape; then
        if ! run_logged "$id" "pip install --user snscrape" -- pip_install --user snscrape; then
            record_fail "$id" "install snscrape" "$OSINT_LOG_DIR/${id}.log"; return
        fi
    fi
    record_ok "$id"
}

# ---------- SECRETS / GIT -----------------------------------------------------

install_trufflehog() {
    local id="trufflehog"
    step "trufflehog — find leaked secrets in git repos"
    if ! command -v go >/dev/null 2>&1; then
        warn "go not installed — skipping trufflehog"; record_skip "$id"; return
    fi
    if ! run_logged "$id" "go install trufflehog" -- go install github.com/trufflesecurity/trufflehog/v3@latest; then
        record_fail "$id" "go install trufflehog" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    ln -sf "$GOBIN/trufflehog" "$OSINT_BIN/trufflehog"
    record_ok "$id"
}

install_gitleaks() {
    local id="gitleaks"
    step "gitleaks — detect secrets in git repos"
    if ! command -v go >/dev/null 2>&1; then
        warn "go not installed — skipping gitleaks"; record_skip "$id"; return
    fi
    if ! run_logged "$id" "go install gitleaks" -- go install github.com/gitleaks/gitleaks/v8@latest; then
        record_fail "$id" "go install gitleaks" "$OSINT_LOG_DIR/${id}.log"; return
    fi
    ln -sf "$GOBIN/gitleaks" "$OSINT_BIN/gitleaks"
    record_ok "$id"
}

# ==============================================================================
# TOOL REGISTRY
# id | category | installer function
# ==============================================================================

# NOTE: the order matters for --category "all" — python-heavy tools first while
# battery is fresh, Go-based tools grouped so Go isn't invoked repeatedly cold.

TOOL_IDS=(
    # social
    sherlock maigret blackbird social-analyzer
    # email
    theharvester holehe h8mail mosint
    # phone
    phoneinfoga ignorant
    # domain
    sublist3r dnsrecon amass subfinder assetfinder
    # web
    photon httpx waybackurls gau katana
    # vuln
    sqlmap nuclei nikto
    # framework
    spiderfoot recon-ng
    # google
    ghunt
    # instagram / social scraping
    osintgram toutatis snscrape
    # secrets
    trufflehog gitleaks
)

declare -A TOOL_CATEGORY=(
    [sherlock]=social [maigret]=social [blackbird]=social [social-analyzer]=social
    [theharvester]=email [holehe]=email [h8mail]=email [mosint]=email
    [phoneinfoga]=phone [ignorant]=phone
    [sublist3r]=domain [dnsrecon]=domain [amass]=domain [subfinder]=domain [assetfinder]=domain
    [photon]=web [httpx]=web [waybackurls]=web [gau]=web [katana]=web
    [sqlmap]=vuln [nuclei]=vuln [nikto]=vuln
    [spiderfoot]=framework [recon-ng]=framework
    [ghunt]=google
    [osintgram]=social [toutatis]=social [snscrape]=social
    [trufflehog]=secrets [gitleaks]=secrets
)

declare -A TOOL_INSTALLER=(
    [sherlock]=install_sherlock
    [maigret]=install_maigret
    [blackbird]=install_blackbird
    [social-analyzer]=install_social_analyzer
    [theharvester]=install_theharvester
    [holehe]=install_holehe
    [h8mail]=install_h8mail
    [mosint]=install_mosint
    [phoneinfoga]=install_phoneinfoga
    [ignorant]=install_ignorant
    [sublist3r]=install_sublist3r
    [dnsrecon]=install_dnsrecon
    [amass]=install_amass
    [subfinder]=install_subfinder
    [assetfinder]=install_assetfinder
    [photon]=install_photon
    [httpx]=install_httpx
    [waybackurls]=install_waybackurls
    [gau]=install_gau
    [katana]=install_katana
    [sqlmap]=install_sqlmap
    [nuclei]=install_nuclei
    [nikto]=install_nikto
    [spiderfoot]=install_spiderfoot
    [recon-ng]=install_reconng
    [ghunt]=install_ghunt
    [osintgram]=install_osintgram
    [toutatis]=install_toutatis
    [snscrape]=install_snscrape
    [trufflehog]=install_trufflehog
    [gitleaks]=install_gitleaks
)

# ==============================================================================
# CLI parsing
# ==============================================================================

usage() {
    sed -n '1,40p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

list_tools() {
    printf '%-18s %-10s\n' "ID" "CATEGORY"
    printf '%-18s %-10s\n' "──" "────────"
    local id
    for id in "${TOOL_IDS[@]}"; do
        printf '%-18s %-10s\n' "$id" "${TOOL_CATEGORY[$id]}"
    done
    exit 0
}

update_installed() {
    step "Updating installed tools (git pull + reinstall python deps)"
    local id
    for id in "${TOOL_IDS[@]}"; do
        if [ -f "$OSINT_STATE/$id.ok" ]; then
            info "updating $id"
            "${TOOL_INSTALLER[$id]}" || true
        fi
    done
    write_report
    exit 0
}

uninstall_tool() {
    local id="$1"
    if [ -z "$id" ] || [ -z "${TOOL_INSTALLER[$id]:-}" ]; then
        err "unknown tool id: $id"; exit 1
    fi
    step "Uninstalling $id"
    # Guard against $id or $OSINT_ROOT being empty — never rm -rf / by accident.
    : "${OSINT_ROOT:?OSINT_ROOT must be set}"
    : "${id:?tool id must be non-empty}"
    local id_alt
    id_alt="$(printf '%s' "$id" | sed 's/-/_/g')"
    rm -rf -- "${OSINT_ROOT:?}/${id:?}" "${OSINT_ROOT:?}/${id_alt:?}"
    case "$id" in
        theharvester)  rm -rf -- "${OSINT_ROOT:?}/theHarvester" ;;
        sublist3r)     rm -rf -- "${OSINT_ROOT:?}/Sublist3r" ;;
        photon)        rm -rf -- "${OSINT_ROOT:?}/Photon" ;;
        osintgram)     rm -rf -- "${OSINT_ROOT:?}/Osintgram" ;;
    esac
    rm -f "$OSINT_STATE/$id.ok" "$OSINT_STATE/$id.fail"
    rm -f "$OSINT_BIN/$id"
    log "removed $id"
    exit 0
}

show_report() {
    if [ -f "$OSINT_REPORT" ]; then
        cat "$OSINT_REPORT"
    else
        warn "no report found at $OSINT_REPORT"
    fi
    exit 0
}

MODE_ONLY=""
MODE_SKIP=""
MODE_CATEGORY=""

while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help)      usage ;;
        --list)         list_tools ;;
        --update)       update_installed ;;
        --report)       show_report ;;
        --uninstall)    shift; uninstall_tool "${1:-}" ;;
        --only)         shift; MODE_ONLY="${1:-}"; shift ;;
        --skip)         shift; MODE_SKIP="${1:-}"; shift ;;
        --category)     shift; MODE_CATEGORY="${1:-all}"; shift ;;
        *)              err "unknown flag: $1"; usage ;;
    esac
done

# Build the list of tools to install based on flags
build_targets() {
    local -a targets=()
    local id

    if [ -n "$MODE_ONLY" ]; then
        IFS=',' read -r -a targets <<< "$MODE_ONLY"
    else
        for id in "${TOOL_IDS[@]}"; do
            if [ -n "$MODE_CATEGORY" ] && [ "$MODE_CATEGORY" != "all" ]; then
                if [ "${TOOL_CATEGORY[$id]:-}" != "$MODE_CATEGORY" ]; then
                    continue
                fi
            fi
            targets+=("$id")
        done
    fi

    if [ -n "$MODE_SKIP" ]; then
        local -a skiplist=()
        IFS=',' read -r -a skiplist <<< "$MODE_SKIP"
        local out=() t s keep
        for t in "${targets[@]}"; do
            keep=1
            for s in "${skiplist[@]}"; do
                [ "$t" = "$s" ] && keep=0
            done
            [ "$keep" = 1 ] && out+=("$t")
        done
        targets=("${out[@]}")
    fi

    printf '%s\n' "${targets[@]}"
}

# ==============================================================================
# REPORT
# ==============================================================================

write_report() {
    {
        printf '════════════════════════════════════════════════════════════════════\n'
        printf 'OSINT Toolkit Install Report — %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
        printf 'host:     %s / %s\n' "$(uname -m)" "$(uname -sr)"
        printf 'termux:   PREFIX=%s\n' "$PREFIX"
        printf 'root:     %s\n' "$OSINT_ROOT"
        printf '════════════════════════════════════════════════════════════════════\n\n'

        printf 'INSTALLED (%d):\n' "${#INSTALLED[@]}"
        if [ "${#INSTALLED[@]}" -gt 0 ]; then
            printf '  ✓ %s\n' "${INSTALLED[@]}"
        else
            printf '  (none)\n'
        fi

        printf '\nSKIPPED (%d):\n' "${#SKIPPED[@]}"
        if [ "${#SKIPPED[@]}" -gt 0 ]; then
            printf '  - %s\n' "${SKIPPED[@]}"
        else
            printf '  (none)\n'
        fi

        printf '\nFAILED (%d):\n' "${#FAILED[@]}"
        if [ "${#FAILED[@]}" -gt 0 ]; then
            local id
            for id in "${FAILED[@]}"; do
                printf '  ✗ %s\n' "$id"
                printf '      command: %s\n' "${FAIL_CMD[$id]:-<unknown>}"
                printf '      logfile: %s\n' "${FAIL_LOG[$id]:-$OSINT_LOG_DIR/${id}.log}"
            done
            printf '\nTo re-attempt only the failed tools:\n'
            printf '  bash %s --only %s\n' "$0" "$(IFS=,; echo "${FAILED[*]}")"
            printf '\nTo paste a failure back to an AI for a fix, run:\n'
            printf '  bash %s --report\n' "$0"
        else
            printf '  (none)\n'
        fi

        printf '\nShims created in: %s\n' "$OSINT_BIN"
        printf 'Logs in:          %s\n\n' "$OSINT_LOG_DIR"

        printf 'FISH SHELL SETUP\n'
        printf '  1. cp scripts/osint.fish ~/.config/fish/conf.d/osint.fish\n'
        printf '  2. exec fish   # reload\n'
        printf '  3. osint-help  # see abbreviations & functions\n\n'
    } > "$OSINT_REPORT"
    cat "$OSINT_REPORT"
}

# ==============================================================================
# MAIN
# ==============================================================================

main() {
    step "OSINT installer starting"
    info "root:       $OSINT_ROOT"
    info "logs:       $OSINT_LOG_DIR"
    info "report:     $OSINT_REPORT"
    info "shim dir:   $OSINT_BIN"

    bootstrap_termux

    local -a targets
    mapfile -t targets < <(build_targets)

    if [ "${#targets[@]}" -eq 0 ]; then
        warn "no tools matched your filters"
        exit 0
    fi

    step "Installing ${#targets[@]} tool(s): ${targets[*]}"

    local id fn
    for id in "${targets[@]}"; do
        fn="${TOOL_INSTALLER[$id]:-}"
        if [ -z "$fn" ]; then
            warn "unknown tool id: $id (skipping)"
            record_skip "$id"
            continue
        fi
        # We deliberately don't set -e globally — each installer records its own
        # status and we want to continue past failures.
        "$fn" || true
    done

    step "Done"
    write_report

    if [ "${#FAILED[@]}" -gt 0 ]; then
        for id in "${FAILED[@]}"; do
            print_failure_block "$id"
        done
        exit 2
    fi
}

main "$@"
