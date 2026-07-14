#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
#  Pen15 — Termux OSINT Toolkit Installer  (ARM64 / Samsung / any Android 11+)
# =============================================================================
#
#  Installs the main OSINT tools into a Termux environment that uses the fish
#  shell.  Most of these tools are NOT in the pkg/apt repositories, so they are
#  cloned from GitHub and wired up with launcher shims that work from fish.
#
#  It is written in bash (bash is far more robust for this kind of scripting
#  than fish) but it detects and configures fish so the tools are on your PATH
#  the next time you open a fish prompt.
#
#  Design goals (per request):
#    * Install the "main" OSINT tools + all of their dependencies.
#    * Clone from GitHub because most are gone from pkg/apt.
#    * When something breaks, EITHER auto-heal it in the script and retry,
#      OR fall back to producing a copy-paste bug report you can send back
#      to your assistant for a fix.
#
#  Usage (from fish or bash):
#      bash termux_osint_installer.sh              # install everything
#      bash termux_osint_installer.sh --minimal    # only the headline tools
#      bash termux_osint_installer.sh --check       # just report what's installed
#      bash termux_osint_installer.sh --only sherlock,sqlmap
#      bash termux_osint_installer.sh --skip amass,nuclei
#      bash termux_osint_installer.sh --no-go       # skip the (heavy) Go tools
#      bash termux_osint_installer.sh --update      # update already-installed tools
#      bash termux_osint_installer.sh --list        # list known tools
#      bash termux_osint_installer.sh --report      # reprint the last report
#      bash termux_osint_installer.sh --help
#
#  Legal: authorized testing / research only. You are responsible for your use.
# =============================================================================

# We deliberately do NOT use `set -e`. A single failing tool must never abort
# the whole run — every step is wrapped so we can heal, retry, and keep going.
set -uo pipefail

# -----------------------------------------------------------------------------
# 0. Constants / environment detection
# -----------------------------------------------------------------------------
VERSION="1.0.0"
SELF="$(basename "$0")"

# Termux prefix (falls back sensibly when run outside Termux, e.g. for testing).
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"

IS_TERMUX=0
if [ -n "${TERMUX_VERSION:-}" ] || [ -d "/data/data/com.termux/files/usr" ]; then
    IS_TERMUX=1
fi

# Where GitHub clones + per-tool virtualenvs live.
OSINT_HOME="$HOME_DIR/.osint"
VENV_DIR="$OSINT_HOME/venvs"
REPO_DIR="$OSINT_HOME/repos"

# Launcher shims go on the PATH. In Termux $PREFIX/bin is already on PATH.
if [ "$IS_TERMUX" -eq 1 ] && [ -w "$PREFIX/bin" ]; then
    BIN_DIR="$PREFIX/bin"
else
    BIN_DIR="$HOME_DIR/.local/bin"
fi

# Logs / reports / diagnostics.
LOG_ROOT="$HOME_DIR/.pen15/logs"
TS="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="$LOG_ROOT/osint-install-$TS.log"
REPORT_FILE="$LOG_ROOT/osint-report.txt"
BUGREPORT_FILE="$LOG_ROOT/osint-bugreport-$TS.md"
STATE_DIR="$OSINT_HOME/state"

# Runtime flags (parsed below).
MODE="install"        # install | check | update | list | report
ASSUME_YES=0
DO_GO=1
ONLY_LIST=""
SKIP_LIST=""
MINIMAL=0

# ANSI colours (disabled if not a TTY).
if [ -t 1 ]; then
    C_RED="$(printf '\033[31m')"; C_GRN="$(printf '\033[32m')"
    C_YEL="$(printf '\033[33m')"; C_BLU="$(printf '\033[36m')"
    C_BLD="$(printf '\033[1m')";  C_RST="$(printf '\033[0m')"
else
    C_RED=""; C_GRN=""; C_YEL=""; C_BLU=""; C_BLD=""; C_RST=""
fi

# Per-tool result tracking (id -> status string).
declare -A RESULT
declare -A RESULT_NOTE

# -----------------------------------------------------------------------------
# 1. Tool registry
# -----------------------------------------------------------------------------
# Each entry:  id | group | method | source | verify | description
#   group   : main | people | domain | web | go   (used by --minimal / grouping)
#   method  : pipx | pipx-git | git-venv | git-plain | go
#   source  : pypi name, git URL, or go module path
#   verify  : command name that must resolve after install
#
# NOTE: order matters — lighter/native tools first so a slow Go build never
# blocks the headline tools the user explicitly asked for.
TOOLS=(
  # ---- headline tools the user named -------------------------------------
  "sherlock|main|pipx|sherlock-project|sherlock|Hunt usernames across 400+ social networks"
  "theharvester|main|pipx-git|https://github.com/laramies/theHarvester.git|theHarvester|Emails, subdomains, hosts & names from public sources"
  "sqlmap|main|git-plain|https://github.com/sqlmapproject/sqlmap.git|sqlmap|Automatic SQL injection & DB takeover"
  "spiderfoot|main|git-venv|https://github.com/smicallef/spiderfoot.git|spiderfoot|Automated OSINT recon engine (CLI + web UI)"
  "sublist3r|main|pipx-git|https://github.com/aboul3la/Sublist3r.git|sublist3r|Fast subdomain enumeration"
  "recon-ng|main|git-venv|https://github.com/lanmaster53/recon-ng.git|recon-ng|Full-featured web reconnaissance framework"

  # ---- people / account OSINT --------------------------------------------
  "holehe|people|pipx|holehe|holehe|Check if an email is used on 120+ sites"
  "maigret|people|pipx|maigret|maigret|Deep username profiling across 3000+ sites"
  "socialscan|people|pipx|socialscan|socialscan|Check email/username availability & usage"
  "h8mail|people|pipx|h8mail|h8mail|Email breach / password hunting"
  "toutatis|people|pipx|toutatis|toutatis|Extract Instagram account info"
  "ghunt|people|pipx|ghunt|ghunt|Investigate Google accounts (needs cookie auth)"

  # ---- domain / infra OSINT ----------------------------------------------
  "dnsrecon|domain|pipx|dnsrecon|dnsrecon|DNS enumeration & zone transfer testing"
  "dnstwist|domain|pipx|dnstwist|dnstwist|Typosquat / phishing domain permutations"
  "wafw00f|web|pipx|wafw00f|wafw00f|Fingerprint the WAF protecting a site"
  "photon|domain|git-venv|https://github.com/s0md3v/Photon.git|photon|Fast crawler for OSINT data extraction"

  # ---- Go-based projectdiscovery / tomnomnom tools (group: go) ----------
  "subfinder|go|go|github.com/projectdiscovery/subfinder/v2/cmd/subfinder@latest|subfinder|Passive subdomain discovery"
  "httpx|go|go|github.com/projectdiscovery/httpx/cmd/httpx@latest|httpx|Fast multi-purpose HTTP probing"
  "nuclei|go|go|github.com/projectdiscovery/nuclei/v3/cmd/nuclei@latest|nuclei|Template-based vulnerability scanner"
  "assetfinder|go|go|github.com/tomnomnom/assetfinder@latest|assetfinder|Find related domains & subdomains"
  "waybackurls|go|go|github.com/tomnomnom/waybackurls@latest|waybackurls|Pull URLs from the Wayback Machine"
  "gau|go|go|github.com/lc/gau/v2/cmd/gau@latest|gau|Fetch known URLs from AlienVault/CommonCrawl/Wayback"
  "amass|go|go|github.com/owasp-amass/amass/v4/...@master|amass|OWASP in-depth attack-surface mapping"
)

# The headline set for --minimal.
MINIMAL_IDS="sherlock theharvester sqlmap spiderfoot sublist3r recon-ng"

# -----------------------------------------------------------------------------
# 2. Logging helpers
# -----------------------------------------------------------------------------
log()   { printf '%s\n' "$*" | tee -a "$LOG_FILE" >/dev/null; }
say()   { printf '%s\n' "$*"; printf '%s\n' "$*" >>"$LOG_FILE" 2>/dev/null; }
info()  { say "${C_BLU}[*]${C_RST} $*"; }
ok()    { say "${C_GRN}[OK]${C_RST} $*"; }
warn()  { say "${C_YEL}[!]${C_RST} $*"; }
err()   { say "${C_RED}[X]${C_RST} $*"; }
hdr()   { say ""; say "${C_BLD}=== $* ===${C_RST}"; }

# Run a command, tee its output to the log, return its exit code.
run() {
    say "${C_BLU}\$${C_RST} $*"
    "$@" >>"$LOG_FILE" 2>&1
    return $?
}

# Run and also capture output into a named file (for healing analysis).
run_cap() {
    local capfile="$1"; shift
    say "${C_BLU}\$${C_RST} $*"
    "$@" >"$capfile" 2>&1
    local rc=$?
    cat "$capfile" >>"$LOG_FILE" 2>/dev/null
    return $rc
}

# -----------------------------------------------------------------------------
# 3. Retry / network helpers
# -----------------------------------------------------------------------------
# Retry a command with exponential backoff (4s, 8s, 16s, 32s). Good for flaky
# mobile networks where a single git clone / pip fetch can hiccup.
retry_net() {
    local max=4 attempt=1 delay=4 rc=0
    while :; do
        "$@"
        rc=$?
        [ $rc -eq 0 ] && return 0
        if [ $attempt -ge $max ]; then
            return $rc
        fi
        warn "Network step failed (attempt $attempt/$max). Retrying in ${delay}s..."
        sleep "$delay"
        attempt=$((attempt + 1))
        delay=$((delay * 2))
    done
}

# -----------------------------------------------------------------------------
# 4. Self-healing: inspect a captured error log and fix common Termux problems
# -----------------------------------------------------------------------------
# Returns 0 if it applied a fix (caller should retry), 1 if nothing matched.
HEAL_BROKEN_APPLIED=""
heal_from_log() {
    local capfile="$1"
    [ -f "$capfile" ] || return 1
    local applied=1  # 1 == nothing done yet

    # -- Out of disk: cannot heal, must stop this step cleanly ---------------
    if grep -qiE "No space left on device|write error: No space" "$capfile"; then
        err "Out of storage space. Free up space and re-run. (This tool skipped.)"
        return 1
    fi

    # -- DNS / mirror resolution failures ------------------------------------
    if grep -qiE "Could not resolve host|Temporary failure in name resolution|Failed to connect|Connection timed out" "$capfile"; then
        warn "Network/DNS trouble detected. Refreshing package lists..."
        run pkg update -y || true
        applied=0
    fi

    # -- Broken dpkg / apt state ---------------------------------------------
    if grep -qiE "dpkg was interrupted|Unable to acquire the dpkg|is another process using it|--fix-broken|E: Could not get lock" "$capfile"; then
        warn "Package manager left in a broken state. Repairing..."
        run dpkg --configure -a || true
        run apt-get --fix-broken install -y || true
        applied=0
    fi

    # -- Package repo missing / not found ------------------------------------
    if grep -qiE "Unable to locate package|has no installation candidate|E: Unable to find" "$capfile"; then
        warn "A package could not be located. Updating repos (consider 'termux-change-repo' if this persists)..."
        run pkg update -y || true
        applied=0
    fi

    # -- PEP 668 externally-managed-environment ------------------------------
    if grep -qiE "externally-managed-environment" "$capfile"; then
        warn "Detected PEP 668 lockout — will use --break-system-packages / isolated venvs."
        export PIP_BREAK_SYSTEM_PACKAGES=1
        applied=0
    fi

    # -- lxml / libxml2 build failures ---------------------------------------
    if grep -qiE "libxml|xmlversion.h|xmlCheckVersion|Could not find function xmlCheckVersion|lxml" "$capfile"; then
        warn "Missing libxml2/libxslt headers — installing."
        ensure_pkgs libxml2 libxslt
        applied=0
    fi

    # -- Rust needed for cryptography / pydantic-core / orjson ----------------
    if grep -qiE "Rust|cargo|maturin|setuptools-rust|cryptography.*rust|can not find Rust compiler" "$capfile"; then
        warn "A dependency needs the Rust toolchain — installing rust."
        ensure_pkgs rust
        applied=0
    fi

    # -- OpenSSL headers ------------------------------------------------------
    if grep -qiE "openssl/opensslv.h|Could not find openssl|libssl|OPENSSL" "$capfile"; then
        warn "Missing OpenSSL headers — installing."
        ensure_pkgs openssl openssl-tool
        applied=0
    fi

    # -- libffi (cffi) --------------------------------------------------------
    if grep -qiE "ffi.h|libffi|cffi" "$capfile"; then
        warn "Missing libffi — installing."
        ensure_pkgs libffi
        applied=0
    fi

    # -- Pillow / jpeg --------------------------------------------------------
    if grep -qiE "jpeglib.h|libjpeg|Pillow|zlib" "$capfile"; then
        warn "Missing image/compression headers — installing."
        ensure_pkgs libjpeg-turbo zlib
        applied=0
    fi

    # -- Generic compiler missing --------------------------------------------
    if grep -qiE "gcc: not found|clang: not found|command 'clang'|C compiler cannot create executables|error: command 'cc'" "$capfile"; then
        warn "Missing C toolchain — installing build tools."
        ensure_pkgs clang make binutils pkg-config
        applied=0
    fi

    # -- Go missing / GOPATH --------------------------------------------------
    if grep -qiE "go: command not found|'go' is not recognized|cannot find GOROOT" "$capfile"; then
        warn "Go toolchain missing — installing golang."
        ensure_pkgs golang
        applied=0
    fi

    return $applied
}

# -----------------------------------------------------------------------------
# 5. Package helpers
# -----------------------------------------------------------------------------
INSTALLED_PKGS=""   # cache to avoid re-running pkg for the same package
ensure_pkgs() {
    local p
    for p in "$@"; do
        case " $INSTALLED_PKGS " in
            *" $p "*) continue ;;
        esac
        if run pkg install -y "$p"; then
            INSTALLED_PKGS="$INSTALLED_PKGS $p"
        else
            warn "pkg install $p failed once — repairing and retrying."
            run dpkg --configure -a || true
            run apt-get --fix-broken install -y || true
            run pkg update -y || true
            if retry_net pkg install -y "$p"; then
                INSTALLED_PKGS="$INSTALLED_PKGS $p"
            else
                warn "Could not install package: $p (continuing; a tool may fail later)."
            fi
        fi
    done
}

# -----------------------------------------------------------------------------
# 6. Base environment bootstrap
# -----------------------------------------------------------------------------
bootstrap_base() {
    hdr "Bootstrapping base environment"

    mkdir -p "$OSINT_HOME" "$VENV_DIR" "$REPO_DIR" "$BIN_DIR" "$LOG_ROOT" "$STATE_DIR" \
             "$HOME_DIR/.pen15" 2>/dev/null

    info "Refreshing package lists (retries on flaky networks)..."
    retry_net pkg update -y || warn "pkg update had issues — continuing."
    # Non-fatal upgrade; don't block on it.
    run pkg upgrade -y || true

    info "Installing core packages + build dependencies..."
    # Core runtime + the headers that 90% of pip builds need on ARM Termux.
    ensure_pkgs python git curl wget openssl openssl-tool \
                clang make binutils pkg-config \
                libxml2 libxslt libffi libjpeg-turbo zlib \
                rust whois dnsutils nmap jq

    # pipx keeps each Python CLI tool in its own venv — the cleanest way to
    # dodge dependency conflicts and PEP 668 on modern Termux.
    if ! command -v pipx >/dev/null 2>&1; then
        info "Installing pipx..."
        if ! run pkg install -y pipx; then
            warn "pkg pipx unavailable — installing pipx via pip."
            local cap="$STATE_DIR/pipx_boot.log"
            if ! run_cap "$cap" python -m pip install --user pipx; then
                heal_from_log "$cap" && run python -m pip install --user pipx
                if ! command -v pipx >/dev/null 2>&1; then
                    run python -m pip install --user --break-system-packages pipx || true
                fi
            fi
        fi
    fi
    # Make sure pipx-installed apps land on PATH for this run.
    export PATH="$HOME_DIR/.local/bin:$PATH"
    export PIPX_BIN_DIR="$HOME_DIR/.local/bin"
    export PIPX_HOME="$OSINT_HOME/pipx"
    mkdir -p "$PIPX_HOME" "$PIPX_BIN_DIR" 2>/dev/null

    # Go bin dir on PATH for this run (used by Go tools).
    export PATH="$HOME_DIR/go/bin:$PATH"

    ok "Base environment ready."
}

# -----------------------------------------------------------------------------
# 7. Install methods
# -----------------------------------------------------------------------------
# Each returns 0 on success, non-zero on failure. All heal-and-retry internally.

# pipx install <pypi-name>  (with one heal+retry cycle)
m_pipx() {
    local name="$1" cap="$STATE_DIR/${2:-pipx}.log"
    if run_cap "$cap" pipx install --force "$name"; then return 0; fi
    if heal_from_log "$cap"; then
        run_cap "$cap" pipx install --force "$name" && return 0
    fi
    # Last resort: retry over the network a few times (transient PyPI issues).
    retry_net pipx install --force "$name"
}

# pipx install git+<url>
m_pipx_git() {
    local url="$1" cap="$STATE_DIR/${2:-pipxgit}.log"
    if run_cap "$cap" pipx install --force "git+$url"; then return 0; fi
    if heal_from_log "$cap"; then
        run_cap "$cap" pipx install --force "git+$url" && return 0
    fi
    retry_net pipx install --force "git+$url"
}

# git clone (or pull) into $REPO_DIR/<id>
clone_or_pull() {
    local id="$1" url="$2" dst="$REPO_DIR/$id"
    if [ -d "$dst/.git" ]; then
        info "Updating existing clone: $id"
        ( cd "$dst" && retry_net git pull --ff-only ) || warn "git pull failed for $id (keeping existing checkout)."
    else
        info "Cloning $id from GitHub..."
        retry_net git clone --depth 1 "$url" "$dst" || return 1
    fi
    return 0
}

# git clone + dedicated venv + requirements + launcher shim
m_git_venv() {
    local id="$1" url="$2" verify="$3"
    local dst="$REPO_DIR/$id" venv="$VENV_DIR/$id" cap="$STATE_DIR/$id.log"

    clone_or_pull "$id" "$url" || return 1

    if [ ! -d "$venv" ]; then
        run python -m venv "$venv" || {
            warn "venv module missing — installing python-venv bits."
            ensure_pkgs python
            run python -m venv "$venv" || return 1
        }
    fi
    local vpip="$venv/bin/pip"
    run "$vpip" install --upgrade pip setuptools wheel || true

    # Install requirements if the repo ships them.
    local reqok=0 req
    for req in requirements.txt REQUIREMENTS requirements/base.txt; do
        if [ -f "$dst/$req" ]; then
            if run_cap "$cap" "$vpip" install -r "$dst/$req"; then
                reqok=1; break
            elif heal_from_log "$cap"; then
                run_cap "$cap" "$vpip" install -r "$dst/$req" && { reqok=1; break; }
            fi
        fi
    done
    # If no requirements file (or it failed) and the repo is a package,
    # install the package itself into the venv.
    if [ "$reqok" -eq 0 ] && { [ -f "$dst/setup.py" ] || [ -f "$dst/pyproject.toml" ]; }; then
        if ! run_cap "$cap" "$vpip" install "$dst"; then
            if heal_from_log "$cap"; then
                run "$vpip" install "$dst" || true
            fi
        fi
    fi

    write_venv_shim "$id" "$venv" "$dst" "$verify" || return 1
    return 0
}

# git clone (no build) + launcher shim that runs the entry script directly
m_git_plain() {
    local id="$1" url="$2" verify="$3"
    local dst="$REPO_DIR/$id" cap="$STATE_DIR/$id.log"

    clone_or_pull "$id" "$url" || return 1

    # Some plain tools still have light python deps; install into the base
    # python only if they ship a small requirements file.
    if [ -f "$dst/requirements.txt" ]; then
        if ! run_cap "$cap" python -m pip install --user -r "$dst/requirements.txt"; then
            if heal_from_log "$cap"; then
                run python -m pip install --user -r "$dst/requirements.txt" || \
                run python -m pip install --user --break-system-packages -r "$dst/requirements.txt" || true
            else
                run python -m pip install --user --break-system-packages -r "$dst/requirements.txt" || true
            fi
        fi
    fi

    write_plain_shim "$id" "$dst" "$verify" || return 1
    return 0
}

# go install <module>
m_go() {
    local id="$1" module="$2" cap="$STATE_DIR/$id.log"
    if ! command -v go >/dev/null 2>&1; then
        ensure_pkgs golang
    fi
    export GOPATH="${GOPATH:-$HOME_DIR/go}"
    export PATH="$GOPATH/bin:$PATH"
    if run_cap "$cap" go install "$module"; then return 0; fi
    if heal_from_log "$cap"; then
        run_cap "$cap" go install "$module" && return 0
    fi
    retry_net go install "$module"
}

# -----------------------------------------------------------------------------
# 8. Launcher shims (make git-cloned tools runnable from fish AND bash)
# -----------------------------------------------------------------------------
# venv-backed tool: figure out the best entry point and wrap it.
write_venv_shim() {
    local id="$1" venv="$2" dst="$3" verify="$4"
    local shim="$BIN_DIR/$verify"

    # Prefer a console-script the venv already produced.
    if [ -x "$venv/bin/$verify" ]; then
        cat >"$shim" <<EOF
#!$PREFIX/bin/bash
exec "$venv/bin/$verify" "\$@"
EOF
        chmod +x "$shim"; return 0
    fi

    # Otherwise locate the main entry script in the repo.
    local entry=""
    case "$id" in
        spiderfoot) entry="$dst/sf.py" ;;
        recon-ng)   entry="$dst/recon-ng" ;;
        photon)     entry="$dst/Photon.py" ;;
    esac
    if [ -z "$entry" ] || [ ! -f "$entry" ]; then
        # Best-effort discovery.
        entry="$(find "$dst" -maxdepth 2 -type f \( -name "$id.py" -o -name "${id}.py" -o -name "$verify.py" \) 2>/dev/null | head -1)"
    fi
    [ -n "$entry" ] && [ -f "$entry" ] || { warn "Could not find entry script for $id."; return 1; }

    if [ "$id" = "recon-ng" ]; then
        # recon-ng ships an executable wrapper; run it with the venv python.
        cat >"$shim" <<EOF
#!$PREFIX/bin/bash
cd "$dst" && exec "$venv/bin/python" "$dst/recon-ng" "\$@"
EOF
    elif [ "$id" = "spiderfoot" ]; then
        cat >"$shim" <<EOF
#!$PREFIX/bin/bash
cd "$dst" && exec "$venv/bin/python" "$dst/sf.py" "\$@"
EOF
        # Convenience: a web-UI launcher on 127.0.0.1:5001
        cat >"$BIN_DIR/spiderfoot-web" <<EOF
#!$PREFIX/bin/bash
cd "$dst" && exec "$venv/bin/python" "$dst/sf.py" -l 127.0.0.1:5001 "\$@"
EOF
        chmod +x "$BIN_DIR/spiderfoot-web"
    else
        cat >"$shim" <<EOF
#!$PREFIX/bin/bash
cd "$dst" && exec "$venv/bin/python" "$entry" "\$@"
EOF
    fi
    chmod +x "$shim"; return 0
}

# plain (no venv) tool shim — runs against base python.
write_plain_shim() {
    local id="$1" dst="$2" verify="$3"
    local shim="$BIN_DIR/$verify" entry=""
    case "$id" in
        sqlmap) entry="$dst/sqlmap.py" ;;
        *)      entry="$(find "$dst" -maxdepth 2 -type f -name "$verify.py" 2>/dev/null | head -1)" ;;
    esac
    [ -n "$entry" ] && [ -f "$entry" ] || { warn "Could not find entry script for $id."; return 1; }
    cat >"$shim" <<EOF
#!$PREFIX/bin/bash
exec python "$entry" "\$@"
EOF
    chmod +x "$shim"; return 0
}

# -----------------------------------------------------------------------------
# 9. Tool dispatcher
# -----------------------------------------------------------------------------
# Look up a field from a registry line.
field() { printf '%s' "$1" | cut -d'|' -f"$2"; }

tool_selected() {
    local id="$1" grp="$2"
    # --only wins if set.
    if [ -n "$ONLY_LIST" ]; then
        case ",$ONLY_LIST," in *",$id,"*) return 0 ;; *) return 1 ;; esac
    fi
    # --minimal restricts to headline set.
    if [ "$MINIMAL" -eq 1 ]; then
        case " $MINIMAL_IDS " in *" $id "*) ;; *) return 1 ;; esac
    fi
    # --no-go drops the Go group.
    if [ "$DO_GO" -eq 0 ] && [ "$grp" = "go" ]; then return 1; fi
    # --skip removes explicitly.
    if [ -n "$SKIP_LIST" ]; then
        case ",$SKIP_LIST," in *",$id,"*) return 1 ;; esac
    fi
    return 0
}

install_one() {
    local line="$1"
    local id grp method src verify desc
    id="$(field "$line" 1)"; grp="$(field "$line" 2)"; method="$(field "$line" 3)"
    src="$(field "$line" 4)"; verify="$(field "$line" 5)"; desc="$(field "$line" 6)"

    hdr "$id — $desc"

    # Skip if already present and not updating.
    if [ "$MODE" != "update" ] && command -v "$verify" >/dev/null 2>&1; then
        ok "$id already installed ($(command -v "$verify"))."
        RESULT["$id"]="ok"; RESULT_NOTE["$id"]="already present"; return 0
    fi

    local rc=1
    case "$method" in
        pipx)      m_pipx "$src" "$id"; rc=$? ;;
        pipx-git)  m_pipx_git "$src" "$id"; rc=$? ;;
        git-venv)  m_git_venv "$id" "$src" "$verify"; rc=$? ;;
        git-plain) m_git_plain "$id" "$src" "$verify"; rc=$? ;;
        go)        m_go "$id" "$src"; rc=$? ;;
        *)         err "Unknown method '$method' for $id"; rc=1 ;;
    esac

    # Verify the tool resolves now.
    if command -v "$verify" >/dev/null 2>&1 || [ -x "$BIN_DIR/$verify" ]; then
        ok "$id installed."
        # Distinguish first-try vs healed by inspecting the capture log size.
        if [ -s "$STATE_DIR/$id.log" ] && grep -qi "error\|failed" "$STATE_DIR/$id.log" 2>/dev/null; then
            RESULT["$id"]="fixed"; RESULT_NOTE["$id"]="installed after auto-heal"
        else
            RESULT["$id"]="ok"; RESULT_NOTE["$id"]="installed"
        fi
        return 0
    fi

    err "$id failed to install."
    RESULT["$id"]="failed"
    RESULT_NOTE["$id"]="$(tail -n 3 "$STATE_DIR/$id.log" 2>/dev/null | tr '\n' ' ' | cut -c1-200)"
    return 1
}

# -----------------------------------------------------------------------------
# 10. Fish shell integration
# -----------------------------------------------------------------------------
configure_fish() {
    hdr "Configuring fish shell"
    local fdir="$HOME_DIR/.config/fish"
    local fcfg="$fdir/config.fish"
    mkdir -p "$fdir/functions" 2>/dev/null

    # Managed block, replaced idempotently on each run.
    local start="# >>> pen15 osint installer >>>"
    local end="# <<< pen15 osint installer <<<"

    # Strip any previous managed block.
    if [ -f "$fcfg" ]; then
        sed -i "/$start/,/$end/d" "$fcfg" 2>/dev/null || {
            # BusyBox sed fallback
            awk -v s="$start" -v e="$end" '
                $0==s{skip=1} skip==0{print} $0==e{skip=0}' "$fcfg" >"$fcfg.tmp" && mv "$fcfg.tmp" "$fcfg"
        }
    fi

    {
        printf '%s\n' "$start"
        printf '# Added by termux_osint_installer.sh — safe to remove this block.\n'
        printf 'if status is-interactive\n'
        printf '    set -gx PIPX_HOME %s/pipx\n' "$OSINT_HOME"
        printf '    set -gx PIPX_BIN_DIR $HOME/.local/bin\n'
        printf 'end\n'
        printf 'fish_add_path -g $HOME/.local/bin\n'
        printf 'fish_add_path -g $HOME/go/bin\n'
        printf 'fish_add_path -g %s\n' "$BIN_DIR"
        printf '%s\n' "$end"
    } >>"$fcfg"

    # A convenience fish function to re-run this installer.
    cat >"$fdir/functions/osint-install.fish" <<EOF
function osint-install --description 'Run the Pen15 OSINT installer'
    bash $OSINT_HOME/termux_osint_installer.sh \$argv
end
EOF

    # Keep a copy of the installer inside \$OSINT_HOME so the function above
    # keeps working even if the repo checkout is removed.
    if [ -f "$0" ]; then
        cp -f "$0" "$OSINT_HOME/termux_osint_installer.sh" 2>/dev/null || true
        chmod +x "$OSINT_HOME/termux_osint_installer.sh" 2>/dev/null || true
    fi

    ok "fish configured. Open a new fish shell (or run 'exec fish') to pick up PATH changes."
}

# -----------------------------------------------------------------------------
# 11. Reporting
# -----------------------------------------------------------------------------
build_report() {
    local n_ok=0 n_fixed=0 n_failed=0 id
    {
        printf '===============================================================\n'
        printf ' Pen15 OSINT Installer — Report (%s)\n' "$(date)"
        printf '===============================================================\n'
        printf 'Device : %s\n' "$(uname -m 2>/dev/null) / Termux=${TERMUX_VERSION:-n-a}"
        printf 'Install root : %s\n' "$OSINT_HOME"
        printf 'Shim dir     : %s\n' "$BIN_DIR"
        printf 'Full log     : %s\n\n' "$LOG_FILE"
        printf '%-14s %-8s %s\n' "TOOL" "STATUS" "NOTE"
        printf '%-14s %-8s %s\n' "----" "------" "----"
        for line in "${TOOLS[@]}"; do
            id="$(field "$line" 1)"
            local st="${RESULT[$id]:-skipped}"
            local note="${RESULT_NOTE[$id]:-}"
            case "$st" in
                ok)     n_ok=$((n_ok+1)) ;;
                fixed)  n_fixed=$((n_fixed+1)) ;;
                failed) n_failed=$((n_failed+1)) ;;
            esac
            printf '%-14s %-8s %s\n' "$id" "$st" "$note"
        done
        printf '\nSummary: %d ok, %d auto-fixed, %d failed.\n' "$n_ok" "$n_fixed" "$n_failed"
    } | tee "$REPORT_FILE"

    # If anything failed, produce a copy-paste bug report to send back.
    if [ "$n_failed" -gt 0 ]; then
        generate_bugreport
    fi
}

generate_bugreport() {
    hdr "Some tools failed — building a bug report you can send back"
    {
        printf '# Pen15 OSINT Installer — Bug Report\n\n'
        printf '_Paste this entire file back to your assistant to get a fix._\n\n'
        printf '## Environment\n\n'
        printf '```\n'
        printf 'date        : %s\n' "$(date)"
        printf 'uname -a    : %s\n' "$(uname -a 2>/dev/null)"
        printf 'arch        : %s\n' "$(uname -m 2>/dev/null)"
        printf 'termux ver  : %s\n' "${TERMUX_VERSION:-not-termux}"
        printf 'python      : %s\n' "$(python --version 2>&1)"
        printf 'pip         : %s\n' "$(python -m pip --version 2>&1)"
        printf 'pipx        : %s\n' "$(pipx --version 2>&1)"
        printf 'go          : %s\n' "$(go version 2>&1)"
        printf 'git         : %s\n' "$(git --version 2>&1)"
        printf 'free space  : %s\n' "$(df -h "$HOME_DIR" 2>/dev/null | tail -1)"
        printf '```\n\n'
        printf '## Failed tools\n\n'
        local id
        for line in "${TOOLS[@]}"; do
            id="$(field "$line" 1)"
            [ "${RESULT[$id]:-}" = "failed" ] || continue
            printf '### %s\n\n' "$id"
            printf 'Method: %s | Source: %s\n\n' "$(field "$line" 3)" "$(field "$line" 4)"
            printf 'Last error output:\n\n```\n'
            tail -n 40 "$STATE_DIR/$id.log" 2>/dev/null || printf '(no captured log)\n'
            printf '\n```\n\n'
        done
    } >"$BUGREPORT_FILE"

    warn "Bug report written to:"
    say  "    $BUGREPORT_FILE"
    # Try to copy it to the Android clipboard for easy sharing.
    if command -v termux-clipboard-set >/dev/null 2>&1; then
        if termux-clipboard-set <"$BUGREPORT_FILE" 2>/dev/null; then
            ok "Bug report copied to your clipboard — just paste it back to your assistant."
        fi
    fi
    say ""
    say "${C_BLD}To get a fix:${C_RST} open the file above (or paste from clipboard) and send"
    say "its contents back to your assistant. It contains only diagnostics — no secrets."
}

# -----------------------------------------------------------------------------
# 12. Update mode
# -----------------------------------------------------------------------------
update_all() {
    hdr "Updating installed OSINT tools"
    # pipx-managed tools
    if command -v pipx >/dev/null 2>&1; then
        export PIPX_HOME="$OSINT_HOME/pipx" PIPX_BIN_DIR="$HOME_DIR/.local/bin"
        run pipx upgrade-all || true
    fi
    # git clones
    local d
    for d in "$REPO_DIR"/*/; do
        [ -d "$d/.git" ] || continue
        info "git pull $(basename "$d")"
        ( cd "$d" && retry_net git pull --ff-only ) || warn "pull failed: $(basename "$d")"
    done
    # go tools: reinstall @latest for whatever is selected
    if [ "$DO_GO" -eq 1 ] && command -v go >/dev/null 2>&1; then
        for line in "${TOOLS[@]}"; do
            [ "$(field "$line" 3)" = "go" ] || continue
            local id; id="$(field "$line" 1)"
            tool_selected "$id" go || continue
            info "go install $id @latest"
            run go install "$(field "$line" 4)" || warn "go update failed: $id"
        done
    fi
    ok "Update pass complete."
}

# -----------------------------------------------------------------------------
# 13. Check / list modes
# -----------------------------------------------------------------------------
check_all() {
    hdr "OSINT tool status"
    local line id verify desc st
    for line in "${TOOLS[@]}"; do
        id="$(field "$line" 1)"; verify="$(field "$line" 5)"; desc="$(field "$line" 6)"
        if command -v "$verify" >/dev/null 2>&1 || [ -x "$BIN_DIR/$verify" ]; then
            printf '  %s[OK]%s %-13s %s\n' "$C_GRN" "$C_RST" "$id" "$desc"
        else
            printf '  %s[--]%s %-13s %s\n' "$C_YEL" "$C_RST" "$id" "$desc"
        fi
    done
}

list_all() {
    hdr "Known OSINT tools"
    local line
    printf '  %-14s %-10s %s\n' "ID" "GROUP" "DESCRIPTION"
    for line in "${TOOLS[@]}"; do
        printf '  %-14s %-10s %s\n' "$(field "$line" 1)" "$(field "$line" 2)" "$(field "$line" 6)"
    done
}

# -----------------------------------------------------------------------------
# 14. Usage
# -----------------------------------------------------------------------------
usage() {
    cat <<EOF
${C_BLD}Pen15 Termux OSINT Installer v$VERSION${C_RST}

Installs the main OSINT tools (Sherlock, theHarvester, sqlmap, SpiderFoot,
Sublist3r, Recon-ng, and many more) into Termux with all dependencies, wiring
them up so they run from your fish shell. Clones from GitHub because most of
these are not in pkg/apt. Heals common failures automatically; if it can't,
it writes a bug report you can paste back for a fix.

Usage: bash $SELF [options]

Options:
  (no options)      Install every known tool.
  --minimal         Only the headline tools: $MINIMAL_IDS
  --only a,b,c      Install only these tool ids.
  --skip a,b        Skip these tool ids.
  --no-go           Skip the (heavy) Go-based tools.
  --update          Update tools that are already installed.
  --check, -c       Report which tools are installed and exit.
  --list            List all known tools and exit.
  --report          Reprint the last install report and exit.
  --yes, -y         Assume yes to prompts (non-interactive).
  --help, -h        Show this help.

Examples:
  bash $SELF --minimal
  bash $SELF --only sherlock,maigret,holehe
  bash $SELF --no-go
EOF
}

# -----------------------------------------------------------------------------
# 15. Argument parsing
# -----------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --minimal)   MINIMAL=1 ;;
        --only)      ONLY_LIST="$(printf '%s' "${2:-}" | tr -d ' ')"; shift ;;
        --only=*)    ONLY_LIST="$(printf '%s' "${1#*=}" | tr -d ' ')" ;;
        --skip)      SKIP_LIST="$(printf '%s' "${2:-}" | tr -d ' ')"; shift ;;
        --skip=*)    SKIP_LIST="$(printf '%s' "${1#*=}" | tr -d ' ')" ;;
        --no-go)     DO_GO=0 ;;
        --update)    MODE="update" ;;
        --check|-c)  MODE="check" ;;
        --list)      MODE="list" ;;
        --report)    MODE="report" ;;
        --yes|-y)    ASSUME_YES=1 ;;
        --help|-h)   usage; exit 0 ;;
        *)           err "Unknown option: $1"; usage; exit 2 ;;
    esac
    shift
done

mkdir -p "$LOG_ROOT" "$STATE_DIR" 2>/dev/null

# -----------------------------------------------------------------------------
# 16. Main
# -----------------------------------------------------------------------------
main() {
    say "${C_BLD}Pen15 Termux OSINT Installer v$VERSION${C_RST}"
    say "Log: $LOG_FILE"
    if [ "$IS_TERMUX" -ne 1 ]; then
        warn "Termux not detected. This script targets Termux on Android."
        warn "Continuing in best-effort mode (paths may differ)."
    fi

    case "$MODE" in
        list)   list_all; exit 0 ;;
        report)
            if [ -f "$REPORT_FILE" ]; then cat "$REPORT_FILE"; else warn "No previous report found."; fi
            exit 0 ;;
        check)  # ensure BIN_DIR on PATH for detection
            export PATH="$HOME_DIR/.local/bin:$HOME_DIR/go/bin:$BIN_DIR:$PATH"
            check_all; exit 0 ;;
    esac

    bootstrap_base

    if [ "$MODE" = "update" ]; then
        update_all
        configure_fish
        check_all
        exit 0
    fi

    # Install pass.
    local line id grp
    for line in "${TOOLS[@]}"; do
        id="$(field "$line" 1)"; grp="$(field "$line" 2)"
        if tool_selected "$id" "$grp"; then
            install_one "$line"
        else
            RESULT["$id"]="skipped"
        fi
    done

    configure_fish
    build_report

    say ""
    ok  "Done. Start a new fish shell (or run 'exec fish') so the new tools are on your PATH."
    say "Re-run anytime with:  ${C_BLD}osint-install${C_RST}   (or)   bash $SELF --check"
}

main
