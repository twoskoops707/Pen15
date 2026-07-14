#!/data/data/com.termux/files/usr/bin/bash
#
# install_osint_termux.sh — Pen15 OSINT toolkit installer for Termux (ARM Android)
# =============================================================================
#
# Installs the main OSINT tools and every build dependency they need into a
# Termux environment, and wires them into the fish shell so they are on PATH
# the next time you open a terminal.
#
# It is SELF-HEALING: every step is wrapped so that a failure does not abort
# the whole run. When a step fails the script tries known fixes (missing build
# deps, PEP-668 lockouts, pipx vs pip fallbacks, git re-clone) and retries.
# Anything that still cannot be fixed automatically is written to a report
# file so you can send it back for a targeted fix.
#
# Tools installed (cloned from GitHub where not in the pkg/apt repos):
#   Username OSINT : Sherlock, Maigret, socialscan
#   Email OSINT    : theHarvester, holehe, h8mail
#   Domain / recon : SpiderFoot, Recon-ng, Sublist3r, dnsrecon, Photon,
#                    subfinder, amass, httpx, nuclei (ProjectDiscovery)
#   Web / injection: sqlmap ("SQL gate")
#   Phone OSINT    : PhoneInfoga
#   Metadata       : exiftool
#   Core utilities : nmap, whois, dig (dnsutils), curl, wget, jq, git
#
# Usage:
#   bash install_osint_termux.sh              # full install (default)
#   bash install_osint_termux.sh --doctor     # only check what is installed
#   bash install_osint_termux.sh --no-go      # skip the Go tools (big/slow)
#   bash install_osint_termux.sh --report     # reprint the last error report
#   bash install_osint_termux.sh --help
#
# Re-running is safe: everything is idempotent (clone -> pull, install -> skip).
# =============================================================================

# We deliberately do NOT use `set -e`: one failing tool must never stop the
# rest of the toolkit from installing. We handle errors explicitly instead.
set -uo pipefail

# -----------------------------------------------------------------------------
# Paths, logging and result tracking
# -----------------------------------------------------------------------------
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
OSINT_HOME="$HOME_DIR/osint"                      # cloned repos live here
STATE_DIR="$HOME_DIR/.pen15/osint"               # logs + report live here
LOG_FILE="$STATE_DIR/install_$(date +%Y%m%d_%H%M%S).log"
REPORT_FILE="$STATE_DIR/last_report.txt"
GO_BIN="$HOME_DIR/go/bin"
LOCAL_BIN="$HOME_DIR/.local/bin"

INSTALL_GO=1
MODE="install"

# Absolute path to this script, so generated launchers can call back into it.
SCRIPT_PATH="$(cd "$(dirname "$0")" 2>/dev/null && pwd)/$(basename "$0")"

# Result buckets (name lists) for the final summary.
RESULT_OK=()
RESULT_SKIP=()
RESULT_FAIL=()

mkdir -p "$STATE_DIR" "$OSINT_HOME" "$LOCAL_BIN" 2>/dev/null || true

# Colours (fall back to empty strings if the terminal is dumb).
if [ -t 1 ]; then
    C_RESET=$'\033[0m'; C_R=$'\033[1;31m'; C_G=$'\033[1;32m'
    C_Y=$'\033[1;33m'; C_B=$'\033[1;34m'; C_C=$'\033[1;36m'
else
    C_RESET=""; C_R=""; C_G=""; C_Y=""; C_B=""; C_C=""
fi

# Every message goes to the console AND to the log file.
_log()     { printf '%s\n' "$*" | tee -a "$LOG_FILE" >/dev/null; printf '%s\n' "$*"; }
section()  { _log ""; _log "${C_B}================================================================${C_RESET}"; _log "${C_B}  $*${C_RESET}"; _log "${C_B}================================================================${C_RESET}"; }
info()     { _log "${C_C}[*]${C_RESET} $*"; }
ok()       { _log "${C_G}[OK]${C_RESET} $*"; }
warn()     { _log "${C_Y}[!]${C_RESET} $*"; }
err()      { _log "${C_R}[X]${C_RESET} $*"; }

mark_ok()   { RESULT_OK+=("$1"); }
mark_skip() { RESULT_SKIP+=("$1"); }
mark_fail() { RESULT_FAIL+=("$1"); }

# -----------------------------------------------------------------------------
# Argument parsing
# -----------------------------------------------------------------------------
for arg in "$@"; do
    case "$arg" in
        --doctor)  MODE="doctor" ;;
        --report)  MODE="report" ;;
        --no-go)   INSTALL_GO=0 ;;
        --help|-h) MODE="help" ;;
        *) warn "Unknown option: $arg (use --help)";;
    esac
done

print_help() {
    # Print the contiguous leading comment block (everything from line 2 up to
    # the first non-comment line), stripping the leading "# ".
    awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
}

# -----------------------------------------------------------------------------
# Low-level helpers
# -----------------------------------------------------------------------------

# have <cmd> — is a command available on PATH?
have() { command -v "$1" >/dev/null 2>&1; }

# retry <n> <cmd...> — run cmd up to n times with exponential backoff.
# Output is appended to the log file. Returns the command's exit code.
retry() {
    local tries="$1"; shift
    local n=1 delay=4
    while true; do
        if "$@" >>"$LOG_FILE" 2>&1; then
            return 0
        fi
        if [ "$n" -ge "$tries" ]; then
            return 1
        fi
        warn "attempt $n/$tries failed, retrying in ${delay}s: $*"
        sleep "$delay"
        n=$((n + 1)); delay=$((delay * 2))
    done
}

# -----------------------------------------------------------------------------
# Package (pkg) helpers — with self-healing
# -----------------------------------------------------------------------------
pkg_update() {
    info "Updating package lists..."
    retry 3 pkg update -y || warn "pkg update had issues (continuing)"
}

# pkg_install <pkg...> — install packages, tolerating individual failures.
# Missing packages are logged but do not stop the run.
pkg_install() {
    local p
    for p in "$@"; do
        if dpkg -s "$p" >/dev/null 2>&1; then
            continue
        fi
        if retry 3 pkg install -y "$p"; then
            :
        else
            # Self-heal: a stale index is the usual culprit — refresh and retry once.
            warn "pkg install $p failed; refreshing index and retrying"
            pkg update -y >>"$LOG_FILE" 2>&1 || true
            retry 2 pkg install -y "$p" || warn "package not available: $p (skipping)"
        fi
    done
}

# -----------------------------------------------------------------------------
# Python / pip / pipx helpers — with self-healing
# -----------------------------------------------------------------------------

# pip_user_install <spec...> — pip install into the user site, reusing the
# prebuilt Termux python-* wheels. Heals the PEP-668 "externally managed"
# lockout by retrying with --break-system-packages.
pip_user_install() {
    if retry 2 python3 -m pip install --user --upgrade "$@"; then
        return 0
    fi
    warn "pip install failed; retrying with --break-system-packages"
    retry 2 python3 -m pip install --user --upgrade --break-system-packages "$@"
}

# pipx_install <pkg> [extra pipx args...] — install an isolated CLI app.
# Falls back to pip --user (which can reuse system wheels) when pipx cannot
# build the isolated environment.
pipx_install() {
    local pkg="$1"; shift || true
    if have pipx; then
        if retry 2 pipx install --force "$pkg" "$@"; then
            return 0
        fi
        warn "pipx install $pkg failed; falling back to pip --user"
    fi
    pip_user_install "$pkg"
}

# -----------------------------------------------------------------------------
# Git helpers — with self-healing
# -----------------------------------------------------------------------------

# git_get <url> <dest> — clone if missing, otherwise pull. On a corrupt/partial
# clone it wipes the directory and clones fresh.
git_get() {
    local url="$1" dest="$2"
    if [ -d "$dest/.git" ]; then
        info "Updating $(basename "$dest") ..."
        ( cd "$dest" && retry 3 git pull --ff-only ) || warn "git pull failed for $dest (using existing copy)"
        return 0
    fi
    if [ -e "$dest" ]; then
        warn "$dest exists but is not a git repo; removing and re-cloning"
        rm -rf "$dest"
    fi
    if retry 3 git clone --depth 1 "$url" "$dest"; then
        return 0
    fi
    warn "shallow clone failed; retrying with a full clone"
    rm -rf "$dest"
    retry 2 git clone "$url" "$dest"
}

# make_launcher <name> <command-line> — write an executable wrapper on PATH so
# fish (and any shell) can run a cloned tool by name.
make_launcher() {
    local name="$1" cmdline="$2"
    local target="$PREFIX/bin/$name"
    {
        echo "#!$PREFIX/bin/bash"
        echo "# Auto-generated by install_osint_termux.sh"
        echo "exec $cmdline \"\$@\""
    } > "$target"
    chmod +x "$target"
}

# install_repo_reqs <dir> — install a repo's Python requirements if present.
install_repo_reqs() {
    local dir="$1" reqs
    for reqs in requirements.txt REQUIREMENTS requirements/base.txt; do
        if [ -f "$dir/$reqs" ]; then
            info "Installing Python requirements for $(basename "$dir") ($reqs)..."
            pip_user_install -r "$dir/$reqs" || warn "some requirements for $(basename "$dir") failed"
            return 0
        fi
    done
}

# =============================================================================
# Environment checks
# =============================================================================
check_env() {
    section "Environment check"
    if [ ! -d "$PREFIX" ] || ! have pkg; then
        warn "This does not look like Termux ($PREFIX / pkg not found)."
        warn "The script is designed for Termux on Android. Continuing anyway,"
        warn "but pkg-based steps will be skipped."
    else
        ok "Termux detected: $PREFIX"
    fi
    info "Architecture: $(uname -m)"
    info "Logs:   $LOG_FILE"
    info "Repos:  $OSINT_HOME"

    # Storage access is needed so results can be written somewhere you can read
    # them from the Android file manager. Best-effort only.
    if [ ! -d "$HOME_DIR/storage" ] && have termux-setup-storage; then
        info "Requesting storage access (approve the Android prompt if it appears)..."
        termux-setup-storage >>"$LOG_FILE" 2>&1 || warn "termux-setup-storage skipped"
    fi
}

# =============================================================================
# Base dependencies — the toolchain + prebuilt wheels that let everything else
# build cleanly. Installing these first is what prevents most "hiccups".
# =============================================================================
install_base() {
    section "Base dependencies (toolchain + libraries)"
    pkg_update

    info "Core CLI + build toolchain..."
    pkg_install python python-pip git curl wget openssh jq \
                clang make cmake binutils pkg-config rust \
                libffi openssl libcrypt zlib

    info "Native libraries needed to build Python OSINT deps (lxml, Pillow, ...)"
    pkg_install libxml2 libxslt libjpeg-turbo libpng freetype

    info "Prebuilt Python wheels (avoids slow/fragile source builds)..."
    # These match the heavy compiled deps of the OSINT tools. Installing the
    # Termux-provided wheels means pip can reuse them instead of compiling.
    pkg_install python-lxml python-cryptography python-numpy python-pandas python-pillow

    info "Networking / recon CLI tools from the Termux repos..."
    pkg_install nmap whois dnsutils exiftool

    info "Upgrading pip and installing pipx (isolated CLI installs)..."
    python3 -m pip install --user --upgrade pip >>"$LOG_FILE" 2>&1 || \
        python3 -m pip install --user --upgrade --break-system-packages pip >>"$LOG_FILE" 2>&1 || \
        warn "pip self-upgrade skipped"
    pkg_install pipx || pip_user_install pipx
    # Make sure pipx's bin dir is created and known.
    if have pipx; then
        pipx ensurepath >>"$LOG_FILE" 2>&1 || true
    fi
    ok "Base dependencies done"
}

# =============================================================================
# Individual tool installers
# Each records its outcome via mark_ok / mark_skip / mark_fail.
# =============================================================================

# --- Username OSINT ----------------------------------------------------------
install_sherlock() {
    info "Sherlock (username across 400+ sites)..."
    if pipx_install sherlock-project; then
        # The pip package exposes both `sherlock` and `python -m sherlock`.
        have sherlock || make_launcher sherlock "python3 -m sherlock_project"
        ok "Sherlock installed"; mark_ok "sherlock"
    else
        # GitHub fallback — sherlock is not in pkg/apt.
        if git_get https://github.com/sherlock-project/sherlock.git "$OSINT_HOME/sherlock"; then
            install_repo_reqs "$OSINT_HOME/sherlock"
            make_launcher sherlock "python3 $OSINT_HOME/sherlock/sherlock/sherlock.py"
            ok "Sherlock installed from GitHub"; mark_ok "sherlock (github)"
        else
            err "Sherlock failed"; mark_fail "sherlock"
        fi
    fi
}

install_maigret() {
    info "Maigret (modern Sherlock alternative, 3000+ sites)..."
    if pipx_install maigret; then ok "Maigret installed"; mark_ok "maigret"
    else err "Maigret failed"; mark_fail "maigret"; fi
}

install_socialscan() {
    info "socialscan (username/email availability)..."
    if pipx_install socialscan; then ok "socialscan installed"; mark_ok "socialscan"
    else err "socialscan failed"; mark_fail "socialscan"; fi
}

# --- Email OSINT -------------------------------------------------------------
install_theharvester() {
    info "theHarvester (emails, subdomains, hosts)..."
    if pipx_install theHarvester; then
        have theHarvester || have theharvester || make_launcher theHarvester "python3 -m theHarvester"
        ok "theHarvester installed"; mark_ok "theHarvester"
    elif git_get https://github.com/laramies/theHarvester.git "$OSINT_HOME/theHarvester"; then
        install_repo_reqs "$OSINT_HOME/theHarvester"
        make_launcher theHarvester "python3 $OSINT_HOME/theHarvester/theHarvester.py"
        ok "theHarvester installed from GitHub"; mark_ok "theHarvester (github)"
    else
        err "theHarvester failed"; mark_fail "theHarvester"
    fi
}

install_holehe() {
    info "holehe (find accounts registered to an email)..."
    if pipx_install holehe; then ok "holehe installed"; mark_ok "holehe"
    else err "holehe failed"; mark_fail "holehe"; fi
}

install_h8mail() {
    info "h8mail (email breach hunting)..."
    if pipx_install h8mail; then ok "h8mail installed"; mark_ok "h8mail"
    else err "h8mail failed"; mark_fail "h8mail"; fi
}

# --- Web / injection ---------------------------------------------------------
install_sqlmap() {
    info "sqlmap (the 'SQL gate' — SQL injection tool)..."
    # sqlmap is pure Python; the canonical distribution is the git repo.
    if git_get https://github.com/sqlmapproject/sqlmap.git "$OSINT_HOME/sqlmap"; then
        make_launcher sqlmap "python3 $OSINT_HOME/sqlmap/sqlmap.py"
        ok "sqlmap installed"; mark_ok "sqlmap"
    elif pipx_install sqlmap; then
        ok "sqlmap installed via pipx"; mark_ok "sqlmap (pipx)"
    else
        err "sqlmap failed"; mark_fail "sqlmap"
    fi
}

# --- Domain / recon frameworks ----------------------------------------------
install_spiderfoot() {
    info "SpiderFoot (automated OSINT — web UI + CLI)..."
    if git_get https://github.com/smicallef/spiderfoot.git "$OSINT_HOME/spiderfoot"; then
        install_repo_reqs "$OSINT_HOME/spiderfoot"
        make_launcher spiderfoot "python3 $OSINT_HOME/spiderfoot/sf.py"
        make_launcher spiderfoot-cli "python3 $OSINT_HOME/spiderfoot/sfcli.py"
        ok "SpiderFoot installed (run: spiderfoot -l 127.0.0.1:5001)"; mark_ok "spiderfoot"
    else
        err "SpiderFoot failed"; mark_fail "spiderfoot"
    fi
}

install_reconng() {
    info "Recon-ng (recon framework)..."
    if git_get https://github.com/lanmaster53/recon-ng.git "$OSINT_HOME/recon-ng"; then
        install_repo_reqs "$OSINT_HOME/recon-ng"
        make_launcher recon-ng "python3 $OSINT_HOME/recon-ng/recon-ng"
        make_launcher recon-cli "python3 $OSINT_HOME/recon-ng/recon-cli"
        ok "Recon-ng installed"; mark_ok "recon-ng"
    else
        err "Recon-ng failed"; mark_fail "recon-ng"
    fi
}

install_sublist3r() {
    info "Sublist3r (subdomain enumeration)..."
    if pipx_install sublist3r; then ok "Sublist3r installed"; mark_ok "sublist3r"
    elif git_get https://github.com/aboul3la/Sublist3r.git "$OSINT_HOME/Sublist3r"; then
        install_repo_reqs "$OSINT_HOME/Sublist3r"
        make_launcher sublist3r "python3 $OSINT_HOME/Sublist3r/sublist3r.py"
        ok "Sublist3r installed from GitHub"; mark_ok "sublist3r (github)"
    else
        err "Sublist3r failed"; mark_fail "sublist3r"
    fi
}

install_dnsrecon() {
    info "dnsrecon (DNS enumeration)..."
    if pipx_install dnsrecon; then ok "dnsrecon installed"; mark_ok "dnsrecon"
    else err "dnsrecon failed"; mark_fail "dnsrecon"; fi
}

install_photon() {
    info "Photon (fast web crawler for OSINT)..."
    if git_get https://github.com/s0md3v/Photon.git "$OSINT_HOME/Photon"; then
        install_repo_reqs "$OSINT_HOME/Photon"
        make_launcher photon "python3 $OSINT_HOME/Photon/photon.py"
        ok "Photon installed"; mark_ok "photon"
    else
        err "Photon failed"; mark_fail "photon"
    fi
}

# --- Phone OSINT -------------------------------------------------------------
install_phoneinfoga() {
    info "PhoneInfoga (phone number OSINT, Go)..."
    if ! have go; then warn "Go not installed; skipping PhoneInfoga"; mark_skip "phoneinfoga (no go)"; return; fi
    if retry 2 env GOFLAGS=-mod=mod go install github.com/sundowndev/phoneinfoga/v2@latest; then
        ok "PhoneInfoga installed"; mark_ok "phoneinfoga"
    else
        err "PhoneInfoga failed"; mark_fail "phoneinfoga"
    fi
}

# --- ProjectDiscovery Go tools ----------------------------------------------
install_go_tool() {
    local name="$1" pkgpath="$2"
    info "$name (Go)..."
    if ! have go; then warn "Go not installed; skipping $name"; mark_skip "$name (no go)"; return; fi
    if retry 2 env GOFLAGS=-mod=mod go install "$pkgpath"; then
        ok "$name installed"; mark_ok "$name"
    else
        err "$name failed"; mark_fail "$name"
    fi
}

install_go_suite() {
    if [ "$INSTALL_GO" -ne 1 ]; then
        warn "Skipping Go tools (--no-go). subfinder/httpx/nuclei/amass/phoneinfoga not installed."
        mark_skip "go tools (--no-go)"
        return
    fi
    section "Go-based tools (ProjectDiscovery + PhoneInfoga)"
    if ! have go; then
        info "Installing Go compiler..."
        pkg_install golang
    fi
    if ! have go; then
        warn "Go compiler unavailable — skipping all Go tools"
        mark_skip "go tools (compiler missing)"
        return
    fi
    install_go_tool subfinder github.com/projectdiscovery/subfinder/v2/cmd/subfinder@latest
    install_go_tool httpx     github.com/projectdiscovery/httpx/cmd/httpx@latest
    install_go_tool nuclei    github.com/projectdiscovery/nuclei/v3/cmd/nuclei@latest
    install_go_tool amass     github.com/owasp-amass/amass/v4/...@master
    install_phoneinfoga
}

# =============================================================================
# fish shell integration
# =============================================================================
setup_fish() {
    section "fish shell integration"
    pkg_install fish
    local confd="$HOME_DIR/.config/fish/conf.d"
    mkdir -p "$confd"
    local snippet="$confd/osint.fish"

    # conf.d/*.fish is auto-loaded by fish on every start, so PATH and the
    # abbreviations below are always available. Rewriting the file each run
    # keeps it idempotent.
    cat > "$snippet" <<EOF
# Managed by install_osint_termux.sh — do not edit by hand.
# Puts the OSINT tool locations on PATH for fish.
for dir in $PREFIX/bin $LOCAL_BIN $GO_BIN
    if test -d \$dir; and not contains \$dir \$fish_user_paths
        fish_add_path -g \$dir
    end
end

# Home of the cloned OSINT repositories.
set -gx OSINT_HOME $OSINT_HOME

# Handy shortcut: type 'osint-doctor' in fish to re-check the toolkit.
abbr -a osint-doctor 'bash $HOME_DIR/osint-doctor.sh'
EOF

    # A tiny doctor launcher in $HOME so the check is always one command away.
    cat > "$HOME_DIR/osint-doctor.sh" <<EOF
#!$PREFIX/bin/bash
exec bash "$SCRIPT_PATH" --doctor
EOF
    chmod +x "$HOME_DIR/osint-doctor.sh" 2>/dev/null || true

    ok "fish configured: $snippet"
    info "Open a new fish session (or run: source $snippet) to pick up PATH changes."
}

# =============================================================================
# Doctor / verification
# =============================================================================
DOCTOR_MISSING=()
_check() {
    local label="$1" cmd="$2"
    if have "$cmd"; then
        ok "$label ($cmd)"
    else
        warn "$label MISSING ($cmd)"
        DOCTOR_MISSING+=("$label")
    fi
}

run_doctor() {
    section "OSINT toolkit doctor"
    _check "Sherlock"      sherlock
    _check "Maigret"       maigret
    _check "socialscan"    socialscan
    _check "theHarvester"  theHarvester
    _check "holehe"        holehe
    _check "h8mail"        h8mail
    _check "sqlmap"        sqlmap
    _check "SpiderFoot"    spiderfoot
    _check "Recon-ng"      recon-ng
    _check "Sublist3r"     sublist3r
    _check "dnsrecon"      dnsrecon
    _check "Photon"        photon
    _check "subfinder"     subfinder
    _check "httpx"         httpx
    _check "nuclei"        nuclei
    _check "amass"         amass
    _check "PhoneInfoga"   phoneinfoga
    _check "nmap"          nmap
    _check "whois"         whois
    _check "dig"           dig
    _check "exiftool"      exiftool
    echo ""
    if [ "${#DOCTOR_MISSING[@]}" -eq 0 ]; then
        ok "Everything is installed."
    else
        warn "Missing: ${DOCTOR_MISSING[*]}"
        warn "Re-run the installer to attempt a repair: bash $0"
    fi
}

# =============================================================================
# Final summary + report (the "send it back to you" artifact)
# =============================================================================
write_report() {
    {
        echo "Pen15 OSINT installer report"
        echo "Generated: $(date)"
        echo "Device:    $(uname -a)"
        echo "Termux:    $PREFIX"
        echo ""
        echo "INSTALLED (${#RESULT_OK[@]}): ${RESULT_OK[*]:-none}"
        echo "SKIPPED   (${#RESULT_SKIP[@]}): ${RESULT_SKIP[*]:-none}"
        echo "FAILED    (${#RESULT_FAIL[@]}): ${RESULT_FAIL[*]:-none}"
        echo ""
        if [ "${#RESULT_FAIL[@]}" -gt 0 ]; then
            echo "---- Last 120 log lines (context for the failures) ----"
            tail -n 120 "$LOG_FILE" 2>/dev/null
        fi
    } > "$REPORT_FILE"
}

print_summary() {
    section "Summary"
    ok   "Installed (${#RESULT_OK[@]}): ${RESULT_OK[*]:-none}"
    [ "${#RESULT_SKIP[@]}" -gt 0 ] && warn "Skipped   (${#RESULT_SKIP[@]}): ${RESULT_SKIP[*]}"
    if [ "${#RESULT_FAIL[@]}" -gt 0 ]; then
        err "Failed    (${#RESULT_FAIL[@]}): ${RESULT_FAIL[*]}"
        write_report
        echo ""
        warn "Some tools could not be auto-fixed. A report was written to:"
        warn "  $REPORT_FILE"
        warn "Send that file back for a targeted fix. To view it now:"
        warn "  bash $0 --report"
        if have termux-share; then
            warn "Or share it from your phone with:  termux-share '$REPORT_FILE'"
        fi
    else
        write_report
        echo ""
        ok "All requested tools installed with no unrecoverable errors."
    fi
    echo ""
    info "Start a NEW fish session so the tools are on PATH, then verify with:"
    info "  bash $0 --doctor"
}

# =============================================================================
# Main
# =============================================================================
main() {
    case "$MODE" in
        help)   print_help; exit 0 ;;
        report) [ -f "$REPORT_FILE" ] && cat "$REPORT_FILE" || echo "No report yet. Run the installer first."; exit 0 ;;
        doctor) run_doctor; exit 0 ;;
    esac

    section "Pen15 OSINT toolkit installer for Termux"
    info "This installs OSINT tools + all build dependencies and wires them into fish."
    info "It self-heals common failures and reports anything it cannot fix."

    check_env
    install_base

    section "Username OSINT"
    install_sherlock
    install_maigret
    install_socialscan

    section "Email OSINT"
    install_theharvester
    install_holehe
    install_h8mail

    section "Web / SQL injection"
    install_sqlmap

    section "Domain / recon"
    install_spiderfoot
    install_reconng
    install_sublist3r
    install_dnsrecon
    install_photon

    install_go_suite

    setup_fish
    print_summary
}

main "$@"
