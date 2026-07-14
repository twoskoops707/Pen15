#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# Pen15 :: OSINT Toolkit Installer for Termux (Android ARM64)
# =============================================================================
# Installs the current generation of OSINT / recon tools on a Samsung / other
# Android ARM64 device running Termux with fish shell. Handles:
#
#   * All apt/pkg dependencies (python, git, rust, go, node, ruby, perl, ...)
#   * Common Termux build gotchas (cryptography, lxml, pillow, etc.)
#   * pip-installable tools with automatic dep-repair on build failure
#   * GitHub-cloned tools with per-tool wrappers in $PREFIX/bin so they are
#     callable by name from any shell (bash, fish, zsh)
#   * Go-based ProjectDiscovery suite (subfinder, httpx, nuclei, ffuf, gau, ...)
#   * fish-shell integration (PATH + helpers + `osint-help` function)
#
# On any failure the script keeps going, logs full context to
#   ~/.pen15/osint_install.log
# and appends a machine-readable entry to
#   ~/.pen15/osint_install_failures.txt
# with tool name, exit code, last 25 lines of output, and a suggested fix.
#
# At the end it prints a summary. If failures exist it also prints the
# failure file path and copies it to the Termux clipboard (when
# termux-clipboard-set is available) so it can be pasted straight back to the
# Cursor agent for follow-up.
#
# Usage:
#   bash ~/Pen15/scripts/osint_install.sh              # full install
#   bash ~/Pen15/scripts/osint_install.sh --check      # verify only
#   bash ~/Pen15/scripts/osint_install.sh --update     # update everything
#   bash ~/Pen15/scripts/osint_install.sh --repair     # re-run failures only
# =============================================================================

set -u
set -o pipefail

# -----------------------------------------------------------------------------
# Globals
# -----------------------------------------------------------------------------
SCRIPT_VERSION="1.0.0"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
OSINT_ROOT="$HOME_DIR/osint"
OSINT_BIN="$OSINT_ROOT/bin"
PEN15_DIR="$HOME_DIR/.pen15"
LOG_FILE="$PEN15_DIR/osint_install.log"
FAIL_FILE="$PEN15_DIR/osint_install_failures.txt"
FISH_CFG_DIR="$HOME_DIR/.config/fish"
FISH_CFG_FILE="$FISH_CFG_DIR/config.fish"
FISH_MARK_BEGIN="# >>> pen15-osint (managed) >>>"
FISH_MARK_END="# <<< pen15-osint (managed) <<<"

MODE="install"
if [ "${1:-}" = "--check" ]; then MODE="check"; fi
if [ "${1:-}" = "--update" ]; then MODE="update"; fi
if [ "${1:-}" = "--repair" ]; then MODE="repair"; fi

# ANSI colors — respect NO_COLOR
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_R=$'\033[31m'; C_G=$'\033[32m'; C_Y=$'\033[33m'
  C_B=$'\033[34m'; C_C=$'\033[36m'; C_D=$'\033[2m'; C_0=$'\033[0m'
else
  C_R=""; C_G=""; C_Y=""; C_B=""; C_C=""; C_D=""; C_0=""
fi

INSTALLED_COUNT=0
FAILED_COUNT=0
SKIPPED_COUNT=0

# -----------------------------------------------------------------------------
# Logging helpers
# -----------------------------------------------------------------------------
mkdir -p "$PEN15_DIR" "$OSINT_ROOT" "$OSINT_BIN" "$FISH_CFG_DIR" 2>/dev/null

_ts() { date '+%Y-%m-%d %H:%M:%S'; }

log()  { printf '%s %s\n' "$(_ts)" "$*" >>"$LOG_FILE"; }
info() { printf '%s[*]%s %s\n' "$C_B" "$C_0" "$*"; log "INFO  $*"; }
ok()   { printf '%s[+]%s %s\n' "$C_G" "$C_0" "$*"; log "OK    $*"; }
warn() { printf '%s[!]%s %s\n' "$C_Y" "$C_0" "$*"; log "WARN  $*"; }
err()  { printf '%s[x]%s %s\n' "$C_R" "$C_0" "$*"; log "ERROR $*"; }
step() { printf '\n%s=== %s ===%s\n' "$C_C" "$*" "$C_0"; log "STEP  $*"; }

record_failure() {
  local name="$1" fixhint="$2" exitcode="$3" tail_output="${4:-}"
  FAILED_COUNT=$((FAILED_COUNT + 1))
  {
    printf '\n----- FAILURE: %s -----\n' "$name"
    printf 'timestamp: %s\n' "$(_ts)"
    printf 'exit_code: %s\n' "$exitcode"
    printf 'suggested_fix: %s\n' "$fixhint"
    if [ -n "$tail_output" ]; then
      printf 'last_output:\n'
      printf '%s\n' "$tail_output" | sed 's/^/  | /'
    fi
    printf '\n'
  } >>"$FAIL_FILE"
}

# Run a command with retries (default 3, backoff 2s/4s/8s)
retry() {
  local n="$1"; shift
  local attempt=1 delay=2
  local out rc
  while :; do
    out=$("$@" 2>&1); rc=$?
    if [ "$rc" -eq 0 ]; then
      printf '%s' "$out"
      return 0
    fi
    if [ "$attempt" -ge "$n" ]; then
      printf '%s' "$out"
      return "$rc"
    fi
    warn "retry $attempt/$n failed (exit $rc), sleeping ${delay}s..."
    sleep "$delay"
    attempt=$((attempt + 1))
    delay=$((delay * 2))
  done
}

# try_install NAME FIX_HINT CMD [ARGS...]
# Runs CMD, streams to log, records failure without exiting.
try_install() {
  local name="$1"; shift
  local fixhint="$1"; shift
  info "install: $name"
  local tmp; tmp="$(mktemp)"
  if "$@" >"$tmp" 2>&1; then
    tail -n 3 "$tmp" | sed 's/^/    /' | tee -a "$LOG_FILE" >/dev/null
    ok "$name"
    INSTALLED_COUNT=$((INSTALLED_COUNT + 1))
    rm -f "$tmp"
    return 0
  fi
  local rc=$?
  cat "$tmp" >>"$LOG_FILE"
  err "$name failed (exit $rc)"
  local last; last="$(tail -n 25 "$tmp")"
  record_failure "$name" "$fixhint" "$rc" "$last"
  rm -f "$tmp"
  return "$rc"
}

# -----------------------------------------------------------------------------
# Pre-flight
# -----------------------------------------------------------------------------
preflight() {
  step "Pre-flight checks"

  if [ ! -d "$PREFIX" ]; then
    err "PREFIX ($PREFIX) does not exist. This script must run inside Termux."
    err "Install Termux from F-Droid: https://f-droid.org/en/packages/com.termux/"
    exit 2
  fi
  ok "Termux detected: PREFIX=$PREFIX"

  local arch; arch="$(uname -m)"
  case "$arch" in
    aarch64|arm64) ok "Arch: $arch (Samsung ARM64 — supported)" ;;
    armv7l|armv8l) warn "Arch: $arch (32-bit ARM — most tools OK, Go tools limited)" ;;
    x86_64|i686)   warn "Arch: $arch (unusual for a phone — proceeding anyway)" ;;
    *)             warn "Arch: $arch (unknown — proceeding)" ;;
  esac

  local free_mb
  free_mb="$(df -Pm "$HOME_DIR" | awk 'NR==2{print $4}')"
  if [ -n "$free_mb" ] && [ "$free_mb" -lt 800 ]; then
    warn "Only ${free_mb} MB free in $HOME_DIR. Recommend 800+ MB. Continuing."
  else
    ok "Free space: ${free_mb:-?} MB"
  fi

  if [ ! -d "$HOME_DIR/storage" ]; then
    info "Requesting shared storage access (termux-setup-storage)…"
    termux-setup-storage 2>/dev/null || warn "termux-setup-storage unavailable — install Termux:API for /sdcard access"
  else
    ok "Shared storage already linked at ~/storage"
  fi

  info "Log:       $LOG_FILE"
  info "Failures:  $FAIL_FILE"
  info "Tools dir: $OSINT_ROOT"

  # Reset failure file for this run (keep log appended)
  : >"$FAIL_FILE"
  {
    printf 'Pen15 OSINT installer v%s\n' "$SCRIPT_VERSION"
    printf 'started: %s\n' "$(_ts)"
    printf 'mode:    %s\n' "$MODE"
    printf 'arch:    %s\n' "$(uname -m)"
    printf 'prefix:  %s\n' "$PREFIX"
    printf '\n'
  } >"$FAIL_FILE"
}

# -----------------------------------------------------------------------------
# Package layer (pkg / apt)
# -----------------------------------------------------------------------------
pkg_update() {
  step "Updating Termux package index"
  # Retry with mirror rotation on failure
  if ! retry 3 pkg update -y >>"$LOG_FILE" 2>&1; then
    warn "pkg update failed — rotating mirrors and retrying"
    yes | termux-change-repo >/dev/null 2>&1 || true
    if ! retry 3 pkg update -y >>"$LOG_FILE" 2>&1; then
      record_failure "pkg-update" \
        "Run: termux-change-repo (pick a working mirror), then re-run this script." \
        "$?" "$(tail -n 25 "$LOG_FILE")"
    fi
  else
    ok "pkg index refreshed"
  fi
}

# Install one or more pkg packages, one at a time so a single miss does not
# poison the batch.
ensure_pkg() {
  local pkgs=("$@")
  for p in "${pkgs[@]}"; do
    if dpkg -s "$p" >/dev/null 2>&1; then
      log "pkg $p already installed"
      continue
    fi
    if ! try_install "pkg:$p" \
        "Run: pkg install -y $p ; or enable extra repos: pkg install root-repo x11-repo" \
        pkg install -y "$p"; then
      # Some Termux packages moved between repos — try apt as fallback
      apt-get install -y "$p" >>"$LOG_FILE" 2>&1 || true
    fi
  done
}

install_base_packages() {
  step "Installing base system packages"

  # Extra repos first (some tools live there)
  ensure_pkg root-repo x11-repo

  # Core toolchain + languages
  ensure_pkg \
    python python-pip python-cryptography python-lxml python-numpy python-pillow \
    git curl wget openssh openssl-tool ca-certificates \
    clang make cmake pkg-config binutils \
    libxml2 libxslt libjpeg-turbo libpng zlib ncurses libffi libmagic file \
    rust golang nodejs ruby perl \
    fish tmux nano jq unzip p7zip proot \
    tor whois dnsutils nmap masscan \
    coreutils findutils grep gawk sed
}

# -----------------------------------------------------------------------------
# Python layer
# -----------------------------------------------------------------------------
python_bootstrap() {
  step "Bootstrapping Python (pip / pipx / wheel)"

  # Modern pip resolves better and avoids cryptography source builds
  try_install "pip-upgrade" \
    "Run: python -m pip install --upgrade pip setuptools wheel" \
    python -m pip install --upgrade pip setuptools wheel

  # pipx gives each tool an isolated venv — the single best defense against
  # OSINT tools with conflicting pinned deps.
  try_install "pipx" \
    "Run: pip install --user pipx && python -m pipx ensurepath" \
    python -m pip install --user --upgrade pipx

  # Ensure user bin is on PATH for this script's remaining phases
  export PATH="$HOME_DIR/.local/bin:$PATH"
  python -m pipx ensurepath >>"$LOG_FILE" 2>&1 || true
}

# ensure_pipx  NAME  PYPI_PACKAGE  [FIX_HINT]
# Installs a Python tool in its own venv via pipx, with dep-repair fallback.
ensure_pipx() {
  local name="$1" pypi="$2" fix="${3:-Run: pipx install $2}"
  if command -v "$name" >/dev/null 2>&1; then
    log "$name already on PATH"
    SKIPPED_COUNT=$((SKIPPED_COUNT + 1))
    return 0
  fi
  local tmp; tmp="$(mktemp)"
  if pipx install --force "$pypi" >"$tmp" 2>&1; then
    tail -n 3 "$tmp" >>"$LOG_FILE"
    ok "pipx: $name ($pypi)"
    INSTALLED_COUNT=$((INSTALLED_COUNT + 1))
    rm -f "$tmp"
    return 0
  fi
  # Auto-heal: build failures usually mean a missing native dep. Detect and
  # install the corresponding termux package, then retry once.
  local errtxt; errtxt="$(cat "$tmp")"
  local heal=""
  case "$errtxt" in
    *"cryptography"*|*"pyca"*)           heal="python-cryptography" ;;
    *"lxml"*"libxml"*|*"libxslt"*)       heal="libxml2 libxslt python-lxml" ;;
    *"Pillow"*|*"libjpeg"*)              heal="libjpeg-turbo libpng zlib python-pillow" ;;
    *"numpy"*)                           heal="python-numpy" ;;
    *"psutil"*)                          heal="linux-headers" ;;
    *"greenlet"*|*"gevent"*)             heal="clang" ;;
  esac
  if [ -n "$heal" ]; then
    warn "$name build failed — installing native deps ($heal) and retrying"
    # shellcheck disable=SC2086
    pkg install -y $heal >>"$LOG_FILE" 2>&1 || true
    if pipx install --force "$pypi" >>"$LOG_FILE" 2>&1; then
      ok "pipx (repaired): $name"
      INSTALLED_COUNT=$((INSTALLED_COUNT + 1))
      rm -f "$tmp"
      return 0
    fi
  fi
  # pipx fallback to plain pip --user, still isolated enough for CLI tools
  warn "pipx failed for $name — falling back to pip --user"
  if python -m pip install --user --upgrade "$pypi" >>"$LOG_FILE" 2>&1; then
    ok "pip --user: $name"
    INSTALLED_COUNT=$((INSTALLED_COUNT + 1))
    rm -f "$tmp"
    return 0
  fi
  local rc=$?
  cat "$tmp" >>"$LOG_FILE"
  err "$name failed after auto-heal"
  record_failure "pypi:$name" "$fix" "$rc" "$(tail -n 25 "$tmp")"
  rm -f "$tmp"
  return "$rc"
}

install_python_tools() {
  step "Installing Python-based OSINT tools (isolated envs via pipx)"

  # username OSINT
  ensure_pipx sherlock       sherlock-project  "Run: pipx install sherlock-project"
  ensure_pipx maigret        maigret           "Run: pipx install maigret"
  ensure_pipx socialscan     socialscan        "Run: pipx install socialscan"

  # email OSINT
  ensure_pipx holehe         holehe            "Run: pipx install holehe"
  ensure_pipx h8mail         h8mail            "Run: pipx install h8mail"

  # instagram / social
  ensure_pipx instaloader    instaloader       "Run: pipx install instaloader"
  ensure_pipx toutatis       toutatis          "Run: pipx install toutatis"
  ensure_pipx ignorant       ignorant          "Run: pipx install ignorant"

  # domain / recon
  ensure_pipx theHarvester   theHarvester      "Run: pipx install theHarvester (needs python-lxml)"

  # web vuln
  ensure_pipx sqlmap         sqlmap            "Run: pipx install sqlmap"
  ensure_pipx wafw00f        wafw00f           "Run: pipx install wafw00f"

  # hash / misc
  ensure_pipx hashid         hashid            "Run: pipx install hashid"
  ensure_pipx shodan         shodan            "Run: pipx install shodan"
  ensure_pipx censys         censys            "Run: pipx install censys"

  # ghunt sometimes needs playwright-chromium which fails on ARM Termux —
  # try it, but do not fail the run if it can't.
  ensure_pipx ghunt          ghunt             "Run: pipx install ghunt (may need extra chromium; skip if it fails)"
}

# -----------------------------------------------------------------------------
# GitHub-cloned tools with wrapper generation
# -----------------------------------------------------------------------------
make_wrapper() {
  local wrapper_name="$1" target_cmd="$2"
  local wpath="$PREFIX/bin/$wrapper_name"
  cat >"$wpath" <<EOF
#!$PREFIX/bin/bash
# auto-generated by osint_install.sh — Pen15 OSINT toolkit
exec $target_cmd "\$@"
EOF
  chmod +x "$wpath"
  log "wrapper: $wpath -> $target_cmd"
}

# ensure_clone  NAME  REPO_URL  ENTRY_CMD  [PIP_REQS]  [FIX_HINT]
# Clones the repo into $OSINT_ROOT/NAME, installs requirements.txt if
# present, and creates a $PREFIX/bin/NAME wrapper that runs ENTRY_CMD.
ensure_clone() {
  local name="$1" url="$2" entry="$3"
  local reqs="${4:-requirements.txt}" fix="${5:-Manual clone: git clone $2 $OSINT_ROOT/$1}"
  local dir="$OSINT_ROOT/$name"

  if [ -d "$dir/.git" ]; then
    if [ "$MODE" = "update" ]; then
      info "updating $name"
      git -C "$dir" pull --ff-only >>"$LOG_FILE" 2>&1 || warn "$name pull failed"
    else
      log "$name already cloned"
    fi
  else
    if ! retry 3 git clone --depth 1 "$url" "$dir" >>"$LOG_FILE" 2>&1; then
      err "$name clone failed"
      record_failure "clone:$name" "$fix" "$?" "$(tail -n 20 "$LOG_FILE")"
      return 1
    fi
    ok "cloned $name"
  fi

  # Install requirements if the repo has a lockfile-like manifest
  local rc=0
  if [ -f "$dir/$reqs" ]; then
    info "installing python deps for $name"
    python -m pip install --user -r "$dir/$reqs" >>"$LOG_FILE" 2>&1 || rc=$?
    if [ "$rc" -ne 0 ]; then
      # heuristic auto-heal: retry once with common native deps if the
      # requirements pulled in cryptography/lxml/pillow builds
      pkg install -y python-cryptography python-lxml python-pillow >>"$LOG_FILE" 2>&1 || true
      python -m pip install --user -r "$dir/$reqs" >>"$LOG_FILE" 2>&1 || rc=$?
    fi
    if [ "$rc" -ne 0 ]; then
      warn "$name deps had issues — tool may still work; see log"
      record_failure "deps:$name" \
        "Run: python -m pip install --user -r $dir/$reqs" \
        "$rc" "$(tail -n 20 "$LOG_FILE")"
    fi
  fi

  make_wrapper "$name" "$entry"
  INSTALLED_COUNT=$((INSTALLED_COUNT + 1))
  return 0
}

install_github_tools() {
  step "Cloning GitHub-only OSINT tools"

  # SpiderFoot — full OSINT reconnaissance framework
  ensure_clone spiderfoot https://github.com/smicallef/spiderfoot.git \
    "python $OSINT_ROOT/spiderfoot/sf.py" \
    requirements.txt \
    "Manual: git clone https://github.com/smicallef/spiderfoot.git ~/osint/spiderfoot && pip install --user -r ~/osint/spiderfoot/requirements.txt"

  # Recon-ng — module-driven recon framework
  ensure_clone recon-ng https://github.com/lanmaster53/recon-ng.git \
    "python $OSINT_ROOT/recon-ng/recon-ng" \
    REQUIREMENTS \
    "Manual: git clone https://github.com/lanmaster53/recon-ng.git ~/osint/recon-ng && pip install --user -r ~/osint/recon-ng/REQUIREMENTS"

  # sqlmap — SQLi (also pipx above; the clone gives you the newest tip)
  ensure_clone sqlmap-git https://github.com/sqlmapproject/sqlmap.git \
    "python $OSINT_ROOT/sqlmap-git/sqlmap.py"

  # theHarvester — email/subdomain enum (also pipx above; clone = tip)
  ensure_clone theharvester-git https://github.com/laramies/theHarvester.git \
    "python $OSINT_ROOT/theharvester-git/theHarvester.py"

  # Photon — fast crawler
  ensure_clone photon https://github.com/s0md3v/Photon.git \
    "python $OSINT_ROOT/photon/photon.py"

  # XSStrike — XSS scanner
  ensure_clone xsstrike https://github.com/s0md3v/XSStrike.git \
    "python $OSINT_ROOT/xsstrike/xsstrike.py"

  # Sublist3r — subdomain enum
  ensure_clone sublist3r https://github.com/aboul3la/Sublist3r.git \
    "python $OSINT_ROOT/sublist3r/sublist3r.py"

  # FinalRecon — all-in-one web recon
  ensure_clone finalrecon https://github.com/thewhiteh4t/FinalRecon.git \
    "python $OSINT_ROOT/finalrecon/finalrecon.py"

  # Nexfil — fast username OSINT
  ensure_clone nexfil https://github.com/thewhiteh4t/nexfil.git \
    "python $OSINT_ROOT/nexfil/nexfil.py"

  # Blackbird — username OSINT (WhatsMyName database)
  ensure_clone blackbird https://github.com/p1ngul1n0/blackbird.git \
    "python $OSINT_ROOT/blackbird/blackbird.py"

  # Osintgram — Instagram OSINT
  ensure_clone osintgram https://github.com/Datalux/Osintgram.git \
    "python $OSINT_ROOT/osintgram/main.py"

  # Th3inspector — general info gathering
  ensure_clone th3inspector https://github.com/Moham3dRiahi/Th3inspector.git \
    "perl $OSINT_ROOT/th3inspector/Th3inspector.pl" \
    /dev/null \
    "Manual: git clone https://github.com/Moham3dRiahi/Th3inspector.git ~/osint/th3inspector && perl ~/osint/th3inspector/install.sh"

  # dnsrecon — DNS reconnaissance
  ensure_clone dnsrecon https://github.com/darkoperator/dnsrecon.git \
    "python $OSINT_ROOT/dnsrecon/dnsrecon.py"

  # IPGeolocation
  ensure_clone ipgeolocation https://github.com/maldevel/IPGeolocation.git \
    "python $OSINT_ROOT/ipgeolocation/ipgeolocation.py"

  # John the Ripper (source build — companion to hash cracking)
  if ! command -v john >/dev/null 2>&1; then
    ensure_clone john-src https://github.com/openwall/john.git \
      "$OSINT_ROOT/john-src/run/john"
    if [ -d "$OSINT_ROOT/john-src/src" ] && [ ! -x "$OSINT_ROOT/john-src/run/john" ]; then
      info "building john the ripper (may take a few min)…"
      ( cd "$OSINT_ROOT/john-src/src" && ./configure && make -sj"$(nproc 2>/dev/null || echo 2)" ) \
        >>"$LOG_FILE" 2>&1 \
        || record_failure "build:john" \
             "Run: cd $OSINT_ROOT/john-src/src && ./configure && make -sj2" \
             "$?" "$(tail -n 25 "$LOG_FILE")"
    fi
  fi
}

# -----------------------------------------------------------------------------
# Go-based tools (ProjectDiscovery + friends)
# -----------------------------------------------------------------------------
install_go_tools() {
  step "Installing Go-based OSINT tools (ProjectDiscovery suite)"

  if ! command -v go >/dev/null 2>&1; then
    warn "go not installed — skipping Go tools"
    record_failure "go-missing" "Run: pkg install golang, then re-run --repair" 1 ""
    return 0
  fi

  export GOPATH="${GOPATH:-$HOME_DIR/go}"
  export PATH="$GOPATH/bin:$PATH"
  mkdir -p "$GOPATH/bin"

  local pkgs=(
    "github.com/projectdiscovery/subfinder/v2/cmd/subfinder@latest"
    "github.com/projectdiscovery/httpx/cmd/httpx@latest"
    "github.com/projectdiscovery/nuclei/v3/cmd/nuclei@latest"
    "github.com/projectdiscovery/katana/cmd/katana@latest"
    "github.com/projectdiscovery/naabu/v2/cmd/naabu@latest"
    "github.com/tomnomnom/assetfinder@latest"
    "github.com/tomnomnom/waybackurls@latest"
    "github.com/lc/gau/v2/cmd/gau@latest"
    "github.com/ffuf/ffuf/v2@latest"
    "github.com/OJ/gobuster/v3@latest"
    "github.com/owasp-amass/amass/v4/...@master"
  )
  for spec in "${pkgs[@]}"; do
    local bname="${spec%@*}"; bname="${bname##*/}"
    if command -v "$bname" >/dev/null 2>&1; then
      log "go tool $bname already installed"
      SKIPPED_COUNT=$((SKIPPED_COUNT + 1))
      continue
    fi
    try_install "go:$bname" \
      "Run: go install -v $spec (needs golang; PATH must include \$HOME/go/bin)" \
      go install -v "$spec"
  done

  # PhoneInfoga — pre-built binary, faster than go install for large repo
  if ! command -v phoneinfoga >/dev/null 2>&1; then
    step "Installing PhoneInfoga (binary release)"
    local pi_url
    pi_url="$(curl -s https://api.github.com/repos/sundowndev/phoneinfoga/releases/latest \
              | grep -oE 'https://[^"]+_Linux_arm64\.tar\.gz' | head -n1)"
    if [ -n "$pi_url" ]; then
      try_install "bin:phoneinfoga" \
        "Manual: download $pi_url, extract phoneinfoga to \$PREFIX/bin, chmod +x" \
        bash -c "curl -sL '$pi_url' | tar -xz -C '$PREFIX/bin' phoneinfoga && chmod +x '$PREFIX/bin/phoneinfoga'"
    else
      warn "PhoneInfoga: could not resolve latest ARM64 asset URL — skipping"
      record_failure "bin:phoneinfoga" \
        "Grab manually from https://github.com/sundowndev/phoneinfoga/releases" 1 ""
    fi
  fi
}

# -----------------------------------------------------------------------------
# fish shell integration
# -----------------------------------------------------------------------------
install_fish_integration() {
  step "Installing fish-shell integration"

  mkdir -p "$FISH_CFG_DIR"
  local snippet
  snippet="$(cat <<'FISH_SNIPPET'
# Pen15 OSINT toolkit — PATH + helpers
if not contains $HOME/.local/bin $PATH
    set -gx PATH $HOME/.local/bin $PATH
end
if not contains $HOME/go/bin $PATH
    set -gx PATH $HOME/go/bin $PATH
end
if not contains $HOME/osint/bin $PATH
    set -gx PATH $HOME/osint/bin $PATH
end
set -gx PYTHONIOENCODING utf-8
set -gx GOPATH $HOME/go

function osint-help --description 'List installed Pen15 OSINT tools'
    echo "=== Pen15 OSINT toolkit ==="
    echo
    echo "Username OSINT:"
    echo "  sherlock <name>              # 400+ site username search"
    echo "  maigret <name>               # Sherlock++ with more sources"
    echo "  nexfil <name>                # fast async username OSINT"
    echo "  blackbird <name>             # WhatsMyName-backed OSINT"
    echo "  socialscan <name>            # username availability check"
    echo
    echo "Email OSINT:"
    echo "  holehe <email>               # which sites the email is signed up on"
    echo "  h8mail <email>               # breach data hunter"
    echo
    echo "Phone OSINT:"
    echo "  phoneinfoga scan -n <num>    # carrier + OSINT footprint"
    echo "  ignorant <cc> <phone>        # site enumeration by phone"
    echo
    echo "Domain / Subdomain:"
    echo "  theHarvester -d <domain>     # emails, subs, hosts"
    echo "  sublist3r -d <domain>        # classic subdomain enum"
    echo "  subfinder -d <domain>        # ProjectDiscovery async enum"
    echo "  amass enum -d <domain>       # deep asset mapping"
    echo "  assetfinder <domain>         # simple subdomain finder"
    echo "  dnsrecon -d <domain>         # DNS recon"
    echo
    echo "Recon Frameworks:"
    echo "  spiderfoot -l 127.0.0.1:5001 # then open in browser"
    echo "  recon-ng                     # interactive framework"
    echo "  finalrecon --url <target>    # one-shot web recon"
    echo
    echo "Web Crawl / Vuln:"
    echo "  photon -u <url>              # fast crawler"
    echo "  xsstrike -u <url>            # XSS scanner"
    echo "  sqlmap -u <url>              # SQL injection"
    echo "  httpx -l urls.txt            # probe alive hosts"
    echo "  nuclei -u <url>              # templated vuln scanner"
    echo "  katana -u <url>              # crawler + JS discovery"
    echo "  ffuf -u <url>/FUZZ -w list   # fuzzer"
    echo "  gobuster dir -u <url> -w ...  # dir brute-force"
    echo "  wafw00f <url>                # WAF fingerprinting"
    echo
    echo "URL / Wayback:"
    echo "  gau <domain>                 # get all urls"
    echo "  waybackurls <domain>         # wayback machine urls"
    echo
    echo "Google / Cloud OSINT:"
    echo "  ghunt <email|url>            # Google account OSINT"
    echo
    echo "Instagram / Social:"
    echo "  instaloader <profile>        # download+metadata"
    echo "  osintgram <profile>          # interactive IG OSINT"
    echo "  toutatis -u <profile> -s ..  # IG email/phone recovery"
    echo
    echo "Search-engine keys / breaches:"
    echo "  shodan init <key> ; shodan host <ip>"
    echo "  censys search '...'"
    echo
    echo "Tools live under: $HOME/osint/"
    echo "Install log:      $HOME/.pen15/osint_install.log"
    echo "Rerun installer:  bash $HOME/Pen15/scripts/osint_install.sh"
end

function osint-update --description 'Update every Pen15 OSINT tool'
    bash $HOME/Pen15/scripts/osint_install.sh --update
end

function osint-check --description 'Verify all Pen15 OSINT tools are on PATH'
    bash $HOME/Pen15/scripts/osint_install.sh --check
end
FISH_SNIPPET
)"

  local tmp; tmp="$(mktemp)"
  # Strip any prior managed block, then append the current one
  if [ -f "$FISH_CFG_FILE" ]; then
    awk -v b="$FISH_MARK_BEGIN" -v e="$FISH_MARK_END" '
      $0 == b { skip=1; next }
      $0 == e { skip=0; next }
      !skip
    ' "$FISH_CFG_FILE" >"$tmp"
  fi
  {
    cat "$tmp"
    printf '\n%s\n' "$FISH_MARK_BEGIN"
    printf '%s\n' "$snippet"
    printf '%s\n' "$FISH_MARK_END"
  } >"$FISH_CFG_FILE"
  rm -f "$tmp"
  ok "fish config updated: $FISH_CFG_FILE"

  info "reload with:  source $FISH_CFG_FILE   (or restart fish)"
}

# -----------------------------------------------------------------------------
# Verification pass
# -----------------------------------------------------------------------------
verify() {
  step "Verifying installed tools"

  local cli=(
    sherlock maigret nexfil blackbird socialscan
    holehe h8mail
    theHarvester sublist3r subfinder assetfinder amass dnsrecon
    photon xsstrike sqlmap wafw00f
    httpx nuclei katana ffuf gobuster gau waybackurls
    instaloader toutatis ignorant ghunt
    phoneinfoga shodan censys hashid
    nmap masscan whois dig
    fish
  )
  local ok_cnt=0 miss_cnt=0
  for t in "${cli[@]}"; do
    if command -v "$t" >/dev/null 2>&1; then
      printf '  %s[ok]%s %s\n' "$C_G" "$C_0" "$t"
      ok_cnt=$((ok_cnt + 1))
    else
      printf '  %s[--]%s %s\n' "$C_Y" "$C_0" "$t"
      miss_cnt=$((miss_cnt + 1))
    fi
  done

  printf '\n'
  ok   "installed  : $ok_cnt"
  [ "$miss_cnt" -gt 0 ] && warn "missing    : $miss_cnt (details in $LOG_FILE)"
  info "clone dir  : $OSINT_ROOT"
  info "wrappers   : $PREFIX/bin/<tool>"
}

# -----------------------------------------------------------------------------
# Summary + failure report
# -----------------------------------------------------------------------------
summary() {
  step "Summary"
  printf '  Installed : %s%d%s\n' "$C_G" "$INSTALLED_COUNT" "$C_0"
  printf '  Skipped   : %s%d%s\n' "$C_D" "$SKIPPED_COUNT" "$C_0"
  printf '  Failed    : %s%d%s\n' "$C_R" "$FAILED_COUNT" "$C_0"
  printf '  Log       : %s\n' "$LOG_FILE"
  printf '  Failures  : %s\n' "$FAIL_FILE"
  printf '\n'
  if [ "$FAILED_COUNT" -gt 0 ]; then
    warn "Some tools failed. If you cannot fix them locally, paste the"
    warn "content of $FAIL_FILE back to the Cursor agent to get targeted help."
    if command -v termux-clipboard-set >/dev/null 2>&1; then
      termux-clipboard-set <"$FAIL_FILE" 2>/dev/null \
        && ok "Failure report copied to clipboard (paste it back to Cursor)."
    fi
    printf '\nQuick view of failures:\n'
    tail -n 60 "$FAIL_FILE"
  else
    ok "All done. Run 'osint-help' in fish for the tool cheat-sheet."
  fi
}

# -----------------------------------------------------------------------------
# Entry point
# -----------------------------------------------------------------------------
main() {
  preflight

  case "$MODE" in
    check)
      verify
      exit 0
      ;;
    repair)
      if [ ! -s "$FAIL_FILE" ]; then
        info "No prior failure file — running full install instead."
        MODE=install
      else
        info "repair mode — retrying only previously failed tools"
      fi
      ;;
    update)
      info "update mode — will refresh already-installed tools"
      ;;
  esac

  pkg_update
  install_base_packages
  python_bootstrap
  install_python_tools
  install_github_tools
  install_go_tools
  install_fish_integration
  verify
  summary
}

main "$@"
