#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
#  Pen15 OSINT Toolkit Installer for Termux (ARM Android / Samsung)
# -----------------------------------------------------------------------------
#  Installs the main OSINT tools + every dependency into a Termux environment
#  and wires them into the fish shell.
#
#  Design goals (from the request):
#    * ARM Samsung phone, Termux, fish shell
#    * Install the well-known OSINT tools (Sherlock, theHarvester, sqlmap,
#      SpiderFoot, ...) plus newer ones you may not know about
#    * Clone from GitHub because most of these are NOT in the pkg/apt repos
#    * "For any hiccup that could happen it can either send it back to you
#       or fix it in the script"  ->  every step is wrapped so it:
#         1. self-heals where possible (retries, fallbacks, build-dep fixes)
#         2. records the failure and writes a diagnostic report you can paste
#            back to the AI ("send it back to you")
#
#  This script is written in bash (the most reliable interpreter in Termux)
#  but it configures the *fish* shell so the tools are available there.
#
#  Safe to re-run: it is idempotent. Re-running only fixes what is missing.
#
#  Usage:
#    bash install_osint_termux.sh              # full install
#    bash install_osint_termux.sh --check      # only verify what's installed
#    bash install_osint_termux.sh --report     # only (re)generate the report
#    bash install_osint_termux.sh --no-go      # skip the heavy Go tools
#    bash install_osint_termux.sh --minimal    # core tools only, skip extras
#    bash install_osint_termux.sh --help
#
#  LEGAL: Authorized testing / research on assets you own or have written
#  permission to test. You are solely responsible for how you use this.
# =============================================================================

# NOTE: we deliberately do NOT use `set -e`. A single failing tool must never
# abort the whole install — instead each failure is captured and reported.
set -u
set -o pipefail

# -----------------------------------------------------------------------------
# 0. Constants & globals
# -----------------------------------------------------------------------------
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME="${HOME:-/data/data/com.termux/files/home}"

OSINT_HOME="${OSINT_HOME:-$HOME/osint-tools}"     # where GitHub repos are cloned
LOCAL_BIN="$HOME/.local/bin"                        # pipx / user scripts
GO_BIN="$HOME/go/bin"                               # go install output
FISH_CONF_DIR="$HOME/.config/fish/conf.d"
FISH_CONF="$FISH_CONF_DIR/osint.fish"

LOG_DIR="$HOME/.pen15/logs"
STAMP="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="$LOG_DIR/osint_install_$STAMP.log"
REPORT_FILE="$HOME/.pen15/osint_report_$STAMP.txt"

# Behaviour flags
DO_GO=1
MINIMAL=0
CHECK_ONLY=0
REPORT_ONLY=0

# Failure / success bookkeeping (parallel arrays: name -> reason)
FAILED_NAMES=()
FAILED_REASONS=()
INSTALLED=()
SKIPPED=()

# Colours (fall back to empty strings if not a tty)
if [ -t 1 ]; then
    C_RED=$'\033[1;31m'; C_GRN=$'\033[1;32m'; C_YEL=$'\033[1;33m'
    C_BLU=$'\033[1;34m'; C_CYN=$'\033[1;36m'; C_DIM=$'\033[2m'; C_RST=$'\033[0m'
else
    C_RED=''; C_GRN=''; C_YEL=''; C_BLU=''; C_CYN=''; C_DIM=''; C_RST=''
fi

# -----------------------------------------------------------------------------
# 1. Logging helpers
# -----------------------------------------------------------------------------
_ts() { date +'%H:%M:%S'; }

log()  { printf '%s\n' "$*" | tee -a "$LOG_FILE" >/dev/null; printf '%s\n' "$*"; }
info() { printf '%s[%s] %s%s\n' "$C_CYN" "$(_ts)" "$*" "$C_RST"; printf '[%s] INFO  %s\n' "$(_ts)" "$*" >>"$LOG_FILE"; }
ok()   { printf '%s[%s] [ OK ] %s%s\n' "$C_GRN" "$(_ts)" "$*" "$C_RST"; printf '[%s] OK    %s\n' "$(_ts)" "$*" >>"$LOG_FILE"; }
warn() { printf '%s[%s] [WARN] %s%s\n' "$C_YEL" "$(_ts)" "$*" "$C_RST"; printf '[%s] WARN  %s\n' "$(_ts)" "$*" >>"$LOG_FILE"; }
err()  { printf '%s[%s] [FAIL] %s%s\n' "$C_RED" "$(_ts)" "$*" "$C_RST"; printf '[%s] FAIL  %s\n' "$(_ts)" "$*" >>"$LOG_FILE"; }
hdr()  { printf '\n%s==== %s ====%s\n' "$C_BLU" "$*" "$C_RST"; printf '\n==== %s ====\n' "$*" >>"$LOG_FILE"; }

record_fail() { FAILED_NAMES+=("$1"); FAILED_REASONS+=("$2"); err "$1 — $2"; }
record_ok()   { INSTALLED+=("$1"); }
record_skip() { SKIPPED+=("$1"); warn "$1 — skipped ($2)"; }

# -----------------------------------------------------------------------------
# 2. Core utilities: retry, run_step
# -----------------------------------------------------------------------------

# retry <attempts> <sleep_base_seconds> -- <command...>
# Exponential backoff. Returns the command's exit code (0 on eventual success).
retry() {
    local attempts="$1"; local base="$2"; shift 2
    [ "$1" = "--" ] && shift
    local n=1 delay="$base" rc=0
    while true; do
        "$@" >>"$LOG_FILE" 2>&1
        rc=$?
        [ "$rc" -eq 0 ] && return 0
        if [ "$n" -ge "$attempts" ]; then
            return "$rc"
        fi
        warn "attempt $n/$attempts failed (rc=$rc): $* — retrying in ${delay}s"
        sleep "$delay"
        n=$((n + 1)); delay=$((delay * 2))
    done
}

# have <cmd>  -> true if command exists on PATH
have() { command -v "$1" >/dev/null 2>&1; }

# py_importable <module>  -> true if python can import it
py_importable() { python3 -c "import importlib,sys; importlib.import_module(sys.argv[1])" "$1" >/dev/null 2>&1; }

# -----------------------------------------------------------------------------
# 3. Environment detection & self-heal of build dependencies
# -----------------------------------------------------------------------------
detect_env() {
    hdr "Environment check"
    mkdir -p "$LOG_DIR" "$OSINT_HOME" "$LOCAL_BIN" "$FISH_CONF_DIR" "$HOME/.pen15"

    if have getprop; then
        info "Device: $(getprop ro.product.manufacturer 2>/dev/null) $(getprop ro.product.model 2>/dev/null)"
        info "Android: $(getprop ro.build.version.release 2>/dev/null) (SDK $(getprop ro.build.version.sdk 2>/dev/null))"
    fi
    info "Arch: $(uname -m)   Kernel: $(uname -r)"
    info "PREFIX: $PREFIX"
    info "Log file: $LOG_FILE"

    if ! have pkg; then
        err "This does not look like Termux ('pkg' not found)."
        err "Install Termux from F-Droid, open it, then run this script again."
        if [ "${FORCE:-0}" != "1" ]; then
            err "Set FORCE=1 to override, but nothing will work outside Termux."
            exit 1
        fi
    fi
}

# Make pip builds succeed on Termux by pointing to the right system libs and
# tolerating the compiler quirks that break cffi/lxml/cryptography/etc.
setup_build_env() {
    export CFLAGS="${CFLAGS:-} -Wno-error=implicit-function-declaration"
    export LDFLAGS="${LDFLAGS:-} -L$PREFIX/lib"
    export CPPFLAGS="${CPPFLAGS:-} -I$PREFIX/include"
    # Rust based wheels (cryptography) are memory hungry — keep it to one job.
    export CARGO_BUILD_JOBS=1
    export CARGO_NET_GIT_FETCH_WITH_CLI=true
    # Let pip use pre-built system packages where we installed them via pkg.
    export PIP_PREFER_BINARY=1
    # Avoid pip complaining about the "externally managed" env on newer pythons.
    export PIP_BREAK_SYSTEM_PACKAGES=1
    # Ensure our bin dirs are visible for the rest of this run.
    export PATH="$LOCAL_BIN:$GO_BIN:$PATH"
    export GOBIN="$GO_BIN"
    export GOFLAGS="-buildvcs=false"
}

# -----------------------------------------------------------------------------
# 4. Package (pkg) install helpers
# -----------------------------------------------------------------------------
pkg_update() {
    hdr "Updating Termux packages"
    if retry 4 4 -- pkg update -y; then ok "pkg update"; else warn "pkg update had issues (continuing)"; fi
    retry 2 4 -- pkg upgrade -y >/dev/null 2>&1 || warn "pkg upgrade skipped"
}

# ensure_pkg <pkg> [pkg...]  -> install any missing packages, record failures
ensure_pkg() {
    local p
    for p in "$@"; do
        if dpkg -s "$p" >/dev/null 2>&1 || pkg list-installed 2>/dev/null | grep -q "^$p/"; then
            continue
        fi
        if retry 3 4 -- pkg install -y "$p"; then
            ok "pkg: $p"
        else
            record_fail "pkg:$p" "pkg install failed — see $LOG_FILE"
        fi
    done
}

install_base_packages() {
    hdr "Installing base packages & build dependencies"
    # root-repo & x11-repo give access to extra tools (tshark etc.)
    ensure_pkg root-repo
    pkg update -y >>"$LOG_FILE" 2>&1 || true

    # Core runtimes & VCS
    ensure_pkg python python-pip git curl wget openssh tar unzip

    # Build toolchain (needed to compile python wheels from source)
    ensure_pkg clang make cmake pkg-config binutils rust

    # Native libs that python wheels commonly link against
    ensure_pkg libxml2 libxslt libjpeg-turbo libffi openssl freetype littlecms

    # Pre-built heavy python libs (avoid slow/failing pip source builds)
    ensure_pkg python-cryptography python-lxml python-numpy python-pillow

    # OSINT-adjacent CLI tools available directly from Termux repos
    ensure_pkg nmap whois dnsutils ldns net-tools proxychains-ng tor jq ripgrep

    # exiftool (metadata extraction) — package name varies
    if ! have exiftool; then
        ensure_pkg exiftool || ensure_pkg perl-image-exiftool || \
            record_skip "exiftool" "not in repo — install perl-image-exiftool manually if needed"
    fi

    # fish shell itself (the user's target shell)
    ensure_pkg fish

    # pipx for clean, isolated installs of python OSINT apps
    if ! have pipx; then
        ensure_pkg python-pipx || python3 -m pip install --user pipx >>"$LOG_FILE" 2>&1 || \
            record_fail "pipx" "could not install pipx"
    fi
    if have pipx; then pipx ensurepath >>"$LOG_FILE" 2>&1 || true; fi
}

# -----------------------------------------------------------------------------
# 5. Install helpers for the tools themselves
# -----------------------------------------------------------------------------

# pipx_tool <display> <check_cmd> <pipx_spec> [extra pipx args...]
# Installs an isolated app via pipx. Falls back to `pip install --user`.
pipx_tool() {
    local disp="$1" check="$2" spec="$3"; shift 3
    if have "$check"; then ok "$disp already present"; record_ok "$disp"; return 0; fi
    info "Installing $disp (pipx: $spec)"
    if have pipx && retry 2 5 -- pipx install --system-site-packages "$spec" "$@"; then
        ok "$disp (pipx)"; record_ok "$disp"; return 0
    fi
    warn "pipx failed for $disp — trying pip --user"
    if retry 2 5 -- python3 -m pip install --user "$spec"; then
        ok "$disp (pip --user)"; record_ok "$disp"; return 0
    fi
    record_fail "$disp" "pipx and pip both failed for '$spec'"
    return 1
}

# git_clone <url> <dest>  (idempotent, with retry + shallow)
git_clone() {
    local url="$1" dest="$2"
    if [ -d "$dest/.git" ]; then
        info "Updating $(basename "$dest")"
        ( cd "$dest" && retry 3 4 -- git pull --ff-only ) || warn "could not update $(basename "$dest") (keeping existing)"
        return 0
    fi
    retry 3 4 -- git clone --depth 1 "$url" "$dest"
}

# make_wrapper <name> <interpreter> <script-path> [pre-args...]
# Writes an executable wrapper into $PREFIX/bin so the tool runs from ANY shell
# (bash *and* fish), regardless of the repo layout.
make_wrapper() {
    local name="$1" interp="$2" script="$3"; shift 3
    local wrapper="$PREFIX/bin/$name"
    {
        echo "#!$PREFIX/bin/bash"
        echo "# auto-generated by install_osint_termux.sh"
        echo "exec $interp \"$script\" $* \"\$@\""
    } > "$wrapper"
    chmod +x "$wrapper"
}

# git_python_tool <display> <name> <repo-url> <entry-relpath> [--req <requirements-file>]
# Clones a python repo, installs its requirements, and drops a CLI wrapper.
git_python_tool() {
    local disp="$1" name="$2" url="$3" entry="$4"; shift 4
    local reqfile=""
    if [ "${1:-}" = "--req" ]; then reqfile="$2"; shift 2; fi
    local dest="$OSINT_HOME/$name"

    info "Installing $disp (git: $url)"
    if ! git_clone "$url" "$dest"; then
        record_fail "$disp" "git clone failed: $url"
        return 1
    fi
    if [ -n "$reqfile" ] && [ -f "$dest/$reqfile" ]; then
        retry 2 5 -- python3 -m pip install --user -r "$dest/$reqfile" || \
            warn "$disp: some requirements failed to install (tool may still work)"
    fi
    if [ ! -f "$dest/$entry" ]; then
        record_fail "$disp" "entry script not found: $entry (repo layout changed?)"
        return 1
    fi
    make_wrapper "$name" "python3" "$dest/$entry"
    ok "$disp -> $name"
    record_ok "$disp"
}

# go_tool <display> <binname> <go-package>
go_tool() {
    local disp="$1" bin="$2" pkg="$3"
    if [ "$DO_GO" -ne 1 ]; then record_skip "$disp" "--no-go"; return 0; fi
    if have "$bin"; then ok "$disp already present"; record_ok "$disp"; return 0; fi
    if ! have go; then
        ensure_pkg golang || { record_fail "$disp" "golang not available"; return 1; }
    fi
    info "Installing $disp (go install $pkg) — this can be slow on a phone"
    if retry 2 8 -- go install "$pkg"; then
        ok "$disp (go)"; record_ok "$disp"
    else
        record_fail "$disp" "go install failed for $pkg (low RAM? try on charger / close apps)"
    fi
}

# -----------------------------------------------------------------------------
# 6. The actual OSINT tool set
# -----------------------------------------------------------------------------
install_python_pip_tools() {
    hdr "OSINT tools — pipx / pip (isolated python apps)"

    # --- Classics ---------------------------------------------------------
    pipx_tool  "Sherlock (usernames, 400+ sites)" sherlock    "sherlock-project"
    pipx_tool  "theHarvester (emails/subdomains)" theHarvester "git+https://github.com/laramies/theHarvester.git"
    pipx_tool  "Maigret (usernames, 3000+ sites)" maigret     "maigret"
    pipx_tool  "holehe (email -> accounts)"       holehe      "holehe"
    pipx_tool  "h8mail (email breach hunter)"     h8mail      "h8mail"
    pipx_tool  "dnsrecon (DNS enumeration)"       dnsrecon    "dnsrecon"

    # --- Newer / less well-known -----------------------------------------
    pipx_tool  "socialscan (email/username avail)" socialscan "socialscan"
    pipx_tool  "toutatis (Instagram OSINT)"        toutatis   "toutatis"
    pipx_tool  "ghunt (Google account OSINT)"      ghunt      "ghunt"
    pipx_tool  "xeuledoc (Google Docs OSINT)"      xeuledoc   "xeuledoc"
    pipx_tool  "checkdmarc (email domain posture)" checkdmarc "checkdmarc"
    pipx_tool  "waymore (wayback URL harvesting)"  waymore    "waymore"

    if [ "$MINIMAL" -eq 1 ]; then return 0; fi

    # heavier / optional
    pipx_tool  "wafw00f (WAF fingerprint)"         wafw00f    "wafw00f"
    pipx_tool  "dnstwist (typosquat/phishing)"     dnstwist   "dnstwist"
}

install_git_python_tools() {
    hdr "OSINT tools — GitHub clones (not in pkg/apt)"

    # sqlmap ("SQL gate") — SQL injection / DB takeover
    git_python_tool "sqlmap (SQL injection)" sqlmap \
        "https://github.com/sqlmapproject/sqlmap.git" "sqlmap.py"

    # SpiderFoot — big automated OSINT correlation framework (web UI + CLI)
    git_python_tool "SpiderFoot (automation)" spiderfoot \
        "https://github.com/smicallef/spiderfoot.git" "sf.py" --req "requirements.txt"
    # spiderfoot web server wrapper too
    if [ -f "$OSINT_HOME/spiderfoot/sf.py" ]; then
        make_wrapper "spiderfoot-web" "python3" "$OSINT_HOME/spiderfoot/sf.py" "-l 127.0.0.1:5001"
        info "SpiderFoot web UI: run 'spiderfoot-web' then open http://127.0.0.1:5001"
    fi

    # Sublist3r — subdomain enumeration
    git_python_tool "Sublist3r (subdomains)" sublist3r \
        "https://github.com/aboul3la/Sublist3r.git" "sublist3r.py" --req "requirements.txt"

    # Recon-ng — full reconnaissance framework
    git_python_tool "Recon-ng (framework)" recon-ng \
        "https://github.com/lanmaster53/recon-ng.git" "recon-ng" --req "REQUIREMENTS"

    if [ "$MINIMAL" -eq 1 ]; then return 0; fi

    # Photon — fast web crawler for OSINT
    git_python_tool "Photon (web crawler)" photon \
        "https://github.com/s0md3v/Photon.git" "photon.py" --req "requirements.txt"

    # FinalRecon — all-in-one web recon
    git_python_tool "FinalRecon (web recon)" finalrecon \
        "https://github.com/thewhiteh4t/FinalRecon.git" "finalrecon.py" --req "requirements.txt"

    # blackbird — username / email account search
    git_python_tool "blackbird (username/email)" blackbird \
        "https://github.com/p1ngul1n0/blackbird.git" "blackbird.py" --req "requirements.txt"

    # Mr.Holmes — info gathering (domain/phone/username)
    git_python_tool "Mr.Holmes (multi OSINT)" mrholmes \
        "https://github.com/Lucksi/Mr.Holmes.git" "MrHolmes.py" --req "requirements.txt"

    # Osintgram / others need creds — skipped intentionally.
}

install_go_tools() {
    hdr "OSINT tools — Go (projectdiscovery & friends)"
    if [ "$DO_GO" -ne 1 ]; then record_skip "go-tools" "--no-go"; return 0; fi
    ensure_pkg golang

    go_tool "subfinder (subdomains)"   subfinder    "github.com/projectdiscovery/subfinder/v2/cmd/subfinder@latest"
    go_tool "httpx (http probing)"     httpx        "github.com/projectdiscovery/httpx/cmd/httpx@latest"

    if [ "$MINIMAL" -eq 1 ]; then return 0; fi

    go_tool "nuclei (vuln templates)"  nuclei       "github.com/projectdiscovery/nuclei/v3/cmd/nuclei@latest"
    go_tool "dnsx (dns toolkit)"       dnsx         "github.com/projectdiscovery/dnsx/cmd/dnsx@latest"
    go_tool "gau (getallurls)"         gau          "github.com/lc/gau/v2/cmd/gau@latest"
    go_tool "waybackurls"              waybackurls  "github.com/tomnomnom/waybackurls@latest"
    go_tool "assetfinder"              assetfinder  "github.com/tomnomnom/assetfinder@latest"
}

# phoneinfoga — phone number OSINT (Go binary; use official release, not build)
install_phoneinfoga() {
    hdr "phoneinfoga (phone number OSINT)"
    if have phoneinfoga; then ok "phoneinfoga already present"; record_ok "phoneinfoga"; return 0; fi
    local arch tag url tmp
    case "$(uname -m)" in
        aarch64|arm64) arch="arm64" ;;
        armv7l|armv8l|arm) arch="armv6" ;;
        x86_64) arch="x86_64" ;;
        *) arch="arm64" ;;
    esac
    tag="$(curl -fsSL https://api.github.com/repos/sundowndev/phoneinfoga/releases/latest 2>>"$LOG_FILE" | grep -m1 '"tag_name"' | cut -d'"' -f4)"
    if [ -z "$tag" ]; then record_fail "phoneinfoga" "could not query latest release (network?)"; return 1; fi
    url="https://github.com/sundowndev/phoneinfoga/releases/download/${tag}/phoneinfoga_Linux_${arch}.tar.gz"
    tmp="$(mktemp -d)"
    if retry 3 5 -- curl -fSL -o "$tmp/pi.tgz" "$url" && tar -xzf "$tmp/pi.tgz" -C "$tmp" >>"$LOG_FILE" 2>&1; then
        if install -m 755 "$tmp/phoneinfoga" "$PREFIX/bin/phoneinfoga" 2>>"$LOG_FILE"; then
            ok "phoneinfoga ($tag/$arch)"; record_ok "phoneinfoga"
        else
            record_fail "phoneinfoga" "install to \$PREFIX/bin failed"
        fi
    else
        record_fail "phoneinfoga" "download/extract failed: $url"
    fi
    rm -rf "$tmp"
}

# -----------------------------------------------------------------------------
# 7. Fish shell integration
# -----------------------------------------------------------------------------
configure_fish() {
    hdr "Configuring fish shell"
    mkdir -p "$FISH_CONF_DIR"
    cat > "$FISH_CONF" <<FISH
# ==========================================================================
#  osint.fish  — auto-generated by install_osint_termux.sh
#  Makes the OSINT toolkit available in fish. Safe to regenerate.
# ==========================================================================

# --- PATH: pipx apps, go binaries, and cloned-tool wrappers ---------------
for dir in "$LOCAL_BIN" "$GO_BIN" "$PREFIX/bin"
    if test -d \$dir; and not contains \$dir \$PATH
        set -gx PATH \$dir \$PATH
    end
end

set -gx OSINT_HOME "$OSINT_HOME"

# --- Convenience abbreviations (kept simple for broad fish compatibility) -
if status is-interactive
    abbr -a sher  'sherlock'
    abbr -a maig  'maigret'
    abbr -a harv  'theHarvester -b all -d'
    abbr -a sqli  'sqlmap -u'
    abbr -a subs  'sublist3r -d'
    abbr -a subf  'subfinder -d'
    abbr -a holea 'holehe'
    abbr -a phone 'phoneinfoga scan -n'
end

# --- Helper: list the installed OSINT tools ------------------------------
function osint-tools --description 'List Pen15 OSINT tools and their status'
    set -l tools sherlock maigret theHarvester holehe h8mail dnsrecon \\
        socialscan toutatis ghunt xeuledoc checkdmarc waymore wafw00f dnstwist \\
        sqlmap spiderfoot spiderfoot-web sublist3r recon-ng photon finalrecon \\
        blackbird mrholmes phoneinfoga subfinder httpx nuclei dnsx gau \\
        waybackurls assetfinder nmap whois dig exiftool proxychains4
    echo "Pen15 OSINT toolkit"
    echo "-------------------"
    for t in \$tools
        if command -q \$t
            printf '  \033[1;32m[ OK ]\033[0m %s\n' \$t
        else
            printf '  \033[2m[  - ]\033[0m %s (not installed)\n' \$t
        end
    end
    echo ""
    echo "Cloned repos live in: \$OSINT_HOME"
    echo "Re-run the installer to add/repair tools."
end

# --- Helper: regenerate the diagnostic report ----------------------------
function osint-report --description 'Regenerate the OSINT diagnostic report'
    bash "$OSINT_HOME/.installer/install_osint_termux.sh" --report
end
FISH
    ok "Wrote fish config: $FISH_CONF"

    # Keep a copy of this script where the fish helper expects it, so
    # 'osint-report' works even if the repo checkout moves.
    mkdir -p "$OSINT_HOME/.installer"
    cp -f "$0" "$OSINT_HOME/.installer/install_osint_termux.sh" 2>/dev/null || true
    chmod +x "$OSINT_HOME/.installer/install_osint_termux.sh" 2>/dev/null || true
}

# -----------------------------------------------------------------------------
# 8. Verification & reporting
# -----------------------------------------------------------------------------
verify_tools() {
    hdr "Verifying installed tools"
    local tools=(sherlock maigret theHarvester holehe h8mail dnsrecon socialscan \
        toutatis ghunt xeuledoc checkdmarc waymore wafw00f dnstwist \
        sqlmap spiderfoot sublist3r recon-ng photon finalrecon blackbird mrholmes \
        phoneinfoga subfinder httpx nuclei dnsx gau waybackurls assetfinder \
        nmap whois dig exiftool proxychains4)
    local t present=0 missing=0
    for t in "${tools[@]}"; do
        if have "$t"; then
            printf '  %s[ OK ]%s %s\n' "$C_GRN" "$C_RST" "$t"
            present=$((present + 1))
        else
            printf '  %s[  - ]%s %s\n' "$C_DIM" "$C_RST" "$t"
            missing=$((missing + 1))
        fi
    done
    info "Present: $present   Missing: $missing"
}

# Writes a report you can paste back to the AI ("send it back to you").
generate_report() {
    hdr "Writing diagnostic report"
    {
        echo "=========================================================="
        echo " Pen15 OSINT Installer — Diagnostic Report"
        echo " Generated: $(date)"
        echo "=========================================================="
        echo ""
        echo "## Environment"
        echo "Arch:      $(uname -m)"
        echo "Kernel:    $(uname -r)"
        if have getprop; then
            echo "Device:    $(getprop ro.product.manufacturer 2>/dev/null) $(getprop ro.product.model 2>/dev/null)"
            echo "Android:   $(getprop ro.build.version.release 2>/dev/null) (SDK $(getprop ro.build.version.sdk 2>/dev/null))"
        fi
        echo "PREFIX:    $PREFIX"
        echo "Python:    $(python3 --version 2>&1)"
        echo "pip:       $(python3 -m pip --version 2>&1 | cut -d' ' -f1-2)"
        echo "pipx:      $(have pipx && pipx --version 2>&1 || echo 'not installed')"
        echo "go:        $(have go && go version 2>&1 || echo 'not installed')"
        echo "fish:      $(have fish && fish --version 2>&1 || echo 'not installed')"
        echo "Log file:  $LOG_FILE"
        echo ""
        echo "## Installed OK (${#INSTALLED[@]})"
        if [ "${#INSTALLED[@]}" -gt 0 ]; then printf '  - %s\n' "${INSTALLED[@]}"; else echo "  (none recorded this run)"; fi
        echo ""
        echo "## Skipped (${#SKIPPED[@]})"
        if [ "${#SKIPPED[@]}" -gt 0 ]; then printf '  - %s\n' "${SKIPPED[@]}"; else echo "  (none)"; fi
        echo ""
        echo "## FAILURES (${#FAILED_NAMES[@]})"
        if [ "${#FAILED_NAMES[@]}" -gt 0 ]; then
            local i
            for i in "${!FAILED_NAMES[@]}"; do
                echo "  - ${FAILED_NAMES[$i]}: ${FAILED_REASONS[$i]}"
            done
            echo ""
            echo "## Last 60 log lines (context for the failures above)"
            echo "----------------------------------------------------------"
            tail -n 60 "$LOG_FILE" 2>/dev/null
            echo "----------------------------------------------------------"
        else
            echo "  None — everything requested installed cleanly."
        fi
        echo ""
        echo "=========================================================="
        echo " If anything failed: copy this whole file and send it back"
        echo " to the AI so it can patch the installer for your device."
        echo " Full log: $LOG_FILE"
        echo "=========================================================="
    } | tee "$REPORT_FILE"
    echo ""
    if [ "${#FAILED_NAMES[@]}" -gt 0 ]; then
        warn "Some tools failed. Report saved to: $REPORT_FILE"
        warn "Send that file back to the AI to have the installer fixed."
    else
        ok "All good. Report saved to: $REPORT_FILE"
    fi
}

print_summary() {
    hdr "Summary"
    printf '%s  Installed: %d   Skipped: %d   Failed: %d%s\n' \
        "$C_CYN" "${#INSTALLED[@]}" "${#SKIPPED[@]}" "${#FAILED_NAMES[@]}" "$C_RST"
    echo ""
    echo "Next steps:"
    echo "  1) Start (or restart) fish:   fish"
    echo "  2) List your tools:           osint-tools"
    echo "  3) Try one:                   sherlock <username>"
    echo "  4) SpiderFoot web UI:         spiderfoot-web   (then open http://127.0.0.1:5001)"
    if [ "${#FAILED_NAMES[@]}" -gt 0 ]; then
        echo ""
        echo "  Something failed? Send this file back to the AI:"
        echo "     $REPORT_FILE"
    fi
}

# -----------------------------------------------------------------------------
# 9. Argument parsing & main
# -----------------------------------------------------------------------------
usage() {
    sed -n '2,40p' "$0"
    exit 0
}

parse_args() {
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --check)    CHECK_ONLY=1 ;;
            --report)   REPORT_ONLY=1 ;;
            --no-go)    DO_GO=0 ;;
            --minimal)  MINIMAL=1 ;;
            -h|--help)  usage ;;
            *) warn "Unknown option: $1 (use --help)";;
        esac
        shift
    done
}

main() {
    parse_args "$@"
    mkdir -p "$LOG_DIR" "$HOME/.pen15"

    printf '%s' "$C_CYN"
    cat <<'BANNER'
   ___  ___ ___ _  _ _____   _____ ___   ___  _    _  _____ _____
  / _ \/ __|_ _| \| |_   _| |_   _/ _ \ / _ \| |  | |/ / __|_   _|
 | (_) \__ \| || .` | | |     | || (_) | (_) | |__| ' <| _|  | |
  \___/|___/___|_|\_| |_|     |_| \___/ \___/|____|_|\_\___| |_|
        Termux / fish OSINT toolkit installer  (ARM Android)
BANNER
    printf '%s\n' "$C_RST"

    if [ "$REPORT_ONLY" -eq 1 ]; then
        detect_env
        verify_tools
        generate_report
        exit 0
    fi

    if [ "$CHECK_ONLY" -eq 1 ]; then
        detect_env
        verify_tools
        exit 0
    fi

    detect_env
    setup_build_env
    pkg_update
    install_base_packages
    install_python_pip_tools
    install_git_python_tools
    install_go_tools
    install_phoneinfoga
    configure_fish
    verify_tools
    generate_report
    print_summary
}

# Only auto-run when executed directly (allows sourcing for tests/tooling).
if [ "${BASH_SOURCE[0]:-$0}" = "$0" ]; then
    main "$@"
fi
