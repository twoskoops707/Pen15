#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
#  Pen15 – Termux OSINT Suite Installer  (ARM64 / Samsung Android)
#  Target: Termux + Fish shell
#  Installs 20+ OSINT tools, configures Fish aliases, and self-heals errors.
# =============================================================================
set -euo pipefail

# ── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GRN='\033[0;32m'; YLW='\033[0;33m'
CYN='\033[0;36m'; BLD='\033[1m'; RST='\033[0m'

info()    { echo -e "${CYN}[*]${RST} $*"; }
ok()      { echo -e "${GRN}[✓]${RST} $*"; }
warn()    { echo -e "${YLW}[!]${RST} $*"; }
err()     { echo -e "${RED}[✗]${RST} $*"; }
banner()  { echo -e "\n${BLD}${CYN}═══ $* ═══${RST}\n"; }

# ── Paths ─────────────────────────────────────────────────────────────────────
OSINT_HOME="${HOME}/.osint"
FISH_FUNC="${HOME}/.config/fish/functions"
FISH_CONF="${HOME}/.config/fish/conf.d"
LOG="${OSINT_HOME}/install.log"
FAILED_LOG="${OSINT_HOME}/failed_tools.log"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
BIN="${PREFIX}/bin"

mkdir -p "${OSINT_HOME}" "${FISH_FUNC}" "${FISH_CONF}"
> "${FAILED_LOG}"

# ── Logging ───────────────────────────────────────────────────────────────────
exec > >(tee -a "${LOG}") 2>&1
echo "=== Install started $(date) ==="

# ── Sanity checks ─────────────────────────────────────────────────────────────
check_termux() {
    if [[ ! -d "/data/data/com.termux" ]]; then
        err "This script must run inside Termux."
        exit 1
    fi
    arch=$(uname -m)
    if [[ "${arch}" != "aarch64" && "${arch}" != "armv7l" ]]; then
        warn "Detected arch: ${arch}. Script targets ARM64/ARMv7 but will attempt anyway."
    else
        ok "Running on ${arch} (ARM) – good."
    fi
}

# ── Retry wrapper ─────────────────────────────────────────────────────────────
# Usage: retry <max_attempts> <delay_seconds> <cmd...>
retry() {
    local max=$1 delay=$2; shift 2
    local attempt=1
    until "$@"; do
        if (( attempt >= max )); then
            err "Command failed after ${max} attempts: $*"
            return 1
        fi
        warn "Attempt ${attempt}/${max} failed – retrying in ${delay}s…"
        sleep "${delay}"
        (( attempt++ ))
        (( delay *= 2 ))
    done
}

# ── pkg install wrapper ───────────────────────────────────────────────────────
pkg_install() {
    retry 3 5 pkg install -y "$@"
}

# ── pip install wrapper ───────────────────────────────────────────────────────
pip_install() {
    retry 3 5 pip install --quiet --no-warn-script-location "$@"
}

# ── git clone with retry ──────────────────────────────────────────────────────
git_clone() {
    local repo=$1 dest=$2
    if [[ -d "${dest}/.git" ]]; then
        info "Updating existing clone: ${dest}"
        git -C "${dest}" pull --ff-only || git -C "${dest}" fetch --all
    else
        retry 3 8 git clone --depth=1 "${repo}" "${dest}"
    fi
}

# ── Create Fish shell wrapper function ───────────────────────────────────────
# make_fish_fn <tool_name> <command_body>
make_fish_fn() {
    local name=$1 body=$2
    cat > "${FISH_FUNC}/${name}.fish" <<FISH
function ${name}
    ${body}
end
FISH
}

# ── Tool installer registry (name → install function) ─────────────────────────
declare -a TOOL_ORDER=()
declare -A TOOL_DESC=()

register_tool() {
    TOOL_ORDER+=("$1")
    TOOL_DESC["$1"]="$2"
}

# ── Mark a tool as failed (non-fatal) ────────────────────────────────────────
mark_failed() {
    local name=$1
    err "FAILED: ${name} – logged to ${FAILED_LOG}"
    echo "${name}" >> "${FAILED_LOG}"
}

# ── Venv helper ──────────────────────────────────────────────────────────────
# create_venv <tool_name>  →  activates venv at $OSINT_HOME/<tool_name>/venv
create_venv() {
    local name=$1
    local venv="${OSINT_HOME}/${name}/venv"
    if [[ ! -d "${venv}" ]]; then
        python -m venv "${venv}"
    fi
    # shellcheck disable=SC1091
    source "${venv}/bin/activate"
}

deactivate_venv() {
    deactivate 2>/dev/null || true
}

# =============================================================================
#  PHASE 1 – System packages
# =============================================================================
install_system_deps() {
    banner "Phase 1 – System dependencies"

    info "Updating package lists…"
    retry 3 5 pkg update -y
    retry 3 5 pkg upgrade -y

    info "Installing core build tools…"
    pkg_install \
        python git curl wget nmap whois dnsutils \
        clang make cmake libxml2 libxslt libffi openssl \
        libjpeg-turbo libpng libtiff zlib rust golang \
        perl exiftool fish jq pv termux-tools

    info "Upgrading pip & setuptools…"
    python -m ensurepip --upgrade 2>/dev/null || true
    pip_install --upgrade pip setuptools wheel

    ok "System dependencies installed."
}

# =============================================================================
#  PHASE 2 – OSINT tool installers
# =============================================================================

# ── Sherlock ─────────────────────────────────────────────────────────────────
register_tool "sherlock" "Username hunt across 300+ social networks"
install_sherlock() {
    banner "Sherlock"
    local dir="${OSINT_HOME}/sherlock"
    git_clone "https://github.com/sherlock-project/sherlock.git" "${dir}"
    create_venv "sherlock"
    pip_install -r "${dir}/requirements.txt"
    deactivate_venv
    make_fish_fn "sherlock" \
        "source ${OSINT_HOME}/sherlock/venv/bin/activate.fish; python ${dir}/sherlock/sherlock.py \$argv; deactivate"
    ok "Sherlock ready → sherlock <username>"
}

# ── Maigret ───────────────────────────────────────────────────────────────────
register_tool "maigret" "Username hunt across 3000+ sites"
install_maigret() {
    banner "Maigret"
    create_venv "maigret"
    # Try pip first; if fails (needs Rust compile), fall back to cargo
    if pip_install maigret 2>/dev/null; then
        make_fish_fn "maigret" \
            "source ${OSINT_HOME}/maigret/venv/bin/activate.fish; maigret \$argv; deactivate"
    else
        warn "pip install failed – building from source with cargo…"
        local dir="${OSINT_HOME}/maigret-src"
        git_clone "https://github.com/krishpranav/maigret.git" "${dir}"
        ( cd "${dir}" && retry 3 30 cargo build --release )
        cp "${dir}/target/release/maigret" "${BIN}/maigret"
        make_fish_fn "maigret" "command maigret \$argv"
    fi
    deactivate_venv
    ok "Maigret ready → maigret <username>"
}

# ── theHarvester ─────────────────────────────────────────────────────────────
register_tool "theharvester" "Email/subdomain/host harvester"
install_theharvester() {
    banner "theHarvester"
    local dir="${OSINT_HOME}/theHarvester"
    git_clone "https://github.com/laramies/theHarvester.git" "${dir}"
    create_venv "theharvester"
    # lxml on ARM needs libxml2/libxslt headers
    CFLAGS="-I${PREFIX}/include" LDFLAGS="-L${PREFIX}/lib" \
        pip_install -r "${dir}/requirements/base.txt"
    deactivate_venv
    make_fish_fn "theharvester" \
        "source ${OSINT_HOME}/theharvester/venv/bin/activate.fish; python ${dir}/theHarvester.py \$argv; deactivate"
    ok "theHarvester ready → theharvester -d <domain> -b all"
}

# ── SpiderFoot ────────────────────────────────────────────────────────────────
register_tool "spiderfoot" "OSINT automation framework (web UI)"
install_spiderfoot() {
    banner "SpiderFoot"
    local dir="${OSINT_HOME}/spiderfoot"
    git_clone "https://github.com/smicallef/spiderfoot.git" "${dir}"
    create_venv "spiderfoot"
    CFLAGS="-I${PREFIX}/include" LDFLAGS="-L${PREFIX}/lib" \
        pip_install -r "${dir}/requirements.txt"
    deactivate_venv
    make_fish_fn "spiderfoot" \
        "source ${OSINT_HOME}/spiderfoot/venv/bin/activate.fish; python ${dir}/sf.py \$argv; deactivate"
    ok "SpiderFoot ready → spiderfoot -l 0.0.0.0:5009 (opens web UI)"
}

# ── SQLMap ────────────────────────────────────────────────────────────────────
register_tool "sqlmap" "Automatic SQL injection & DB takeover"
install_sqlmap() {
    banner "SQLMap"
    local dir="${OSINT_HOME}/sqlmap"
    git_clone "https://github.com/sqlmapproject/sqlmap.git" "${dir}"
    # sqlmap is pure Python – no venv needed
    ln -sf "${dir}/sqlmap.py" "${BIN}/sqlmap" 2>/dev/null || \
        cp "${dir}/sqlmap.py" "${BIN}/sqlmap"
    chmod +x "${BIN}/sqlmap"
    make_fish_fn "sqlmap" "python ${dir}/sqlmap.py \$argv"
    ok "SQLMap ready → sqlmap -u <url>"
}

# ── Recon-ng ──────────────────────────────────────────────────────────────────
register_tool "recon-ng" "Full-featured web reconnaissance framework"
install_reconng() {
    banner "Recon-ng"
    local dir="${OSINT_HOME}/recon-ng"
    git_clone "https://github.com/lanmaster53/recon-ng.git" "${dir}"
    create_venv "recon-ng"
    pip_install -r "${dir}/REQUIREMENTS"
    deactivate_venv
    make_fish_fn "recon-ng" \
        "source ${OSINT_HOME}/recon-ng/venv/bin/activate.fish; python ${dir}/recon-ng \$argv; deactivate"
    ok "Recon-ng ready → recon-ng"
}

# ── Holehe ────────────────────────────────────────────────────────────────────
register_tool "holehe" "Check email across 120+ sites"
install_holehe() {
    banner "Holehe"
    create_venv "holehe"
    pip_install holehe
    deactivate_venv
    make_fish_fn "holehe" \
        "source ${OSINT_HOME}/holehe/venv/bin/activate.fish; holehe \$argv; deactivate"
    ok "Holehe ready → holehe <email>"
}

# ── h8mail ────────────────────────────────────────────────────────────────────
register_tool "h8mail" "Email breach hunting & OSINT"
install_h8mail() {
    banner "h8mail"
    create_venv "h8mail"
    pip_install h8mail
    deactivate_venv
    make_fish_fn "h8mail" \
        "source ${OSINT_HOME}/h8mail/venv/bin/activate.fish; h8mail \$argv; deactivate"
    ok "h8mail ready → h8mail -t <email>"
}

# ── PhoneInfoga ───────────────────────────────────────────────────────────────
register_tool "phoneinfoga" "Phone number OSINT scanner"
install_phoneinfoga() {
    banner "PhoneInfoga"
    local goarch
    goarch=$(uname -m | sed 's/aarch64/arm64/;s/armv7l/arm/')
    local binary="${OSINT_HOME}/phoneinfoga_bin"
    mkdir -p "${binary}"
    local url
    url=$(curl -s "https://api.github.com/repos/sundowndev/phoneinfoga/releases/latest" \
        | python -c "import sys,json; releases=json.load(sys.stdin)['assets']; \
          print(next(a['browser_download_url'] for a in releases \
          if 'linux' in a['name'] and '${goarch}' in a['name'] and a['name'].endswith('.tar.gz')), '')" 2>/dev/null || echo "")
    if [[ -n "${url}" ]]; then
        retry 3 10 curl -L -o "${binary}/phoneinfoga.tar.gz" "${url}"
        tar -xzf "${binary}/phoneinfoga.tar.gz" -C "${binary}"
        chmod +x "${binary}/phoneinfoga"
        cp "${binary}/phoneinfoga" "${BIN}/phoneinfoga"
        make_fish_fn "phoneinfoga" "command phoneinfoga \$argv"
        ok "PhoneInfoga ready → phoneinfoga scan -n +<phone>"
    else
        # Fallback: build from Go source
        warn "Binary not found – building from Go source…"
        local dir="${OSINT_HOME}/phoneinfoga"
        git_clone "https://github.com/sundowndev/phoneinfoga.git" "${dir}"
        ( cd "${dir}" && retry 3 30 go build -o "${BIN}/phoneinfoga" ./cmd/... )
        make_fish_fn "phoneinfoga" "command phoneinfoga \$argv"
        ok "PhoneInfoga built from source → phoneinfoga scan -n +<phone>"
    fi
}

# ── GHunt ─────────────────────────────────────────────────────────────────────
register_tool "ghunt" "Investigate Google accounts from email"
install_ghunt() {
    banner "GHunt"
    local dir="${OSINT_HOME}/ghunt"
    git_clone "https://github.com/mxrch/GHunt.git" "${dir}"
    create_venv "ghunt"
    pip_install -r "${dir}/requirements.txt"
    deactivate_venv
    make_fish_fn "ghunt" \
        "source ${OSINT_HOME}/ghunt/venv/bin/activate.fish; python ${dir}/ghunt.py \$argv; deactivate"
    ok "GHunt ready → ghunt email <email>"
}

# ── Blackbird ─────────────────────────────────────────────────────────────────
register_tool "blackbird" "Email & username OSINT – 600+ sites"
install_blackbird() {
    banner "Blackbird"
    local dir="${OSINT_HOME}/blackbird"
    git_clone "https://github.com/p1ngul1n0/blackbird.git" "${dir}"
    create_venv "blackbird"
    pip_install -r "${dir}/requirements.txt"
    deactivate_venv
    make_fish_fn "blackbird" \
        "source ${OSINT_HOME}/blackbird/venv/bin/activate.fish; python ${dir}/blackbird.py \$argv; deactivate"
    ok "Blackbird ready → blackbird -u <username> | -e <email>"
}

# ── Socialscan ────────────────────────────────────────────────────────────────
register_tool "socialscan" "Email/username availability across platforms"
install_socialscan() {
    banner "Socialscan"
    create_venv "socialscan"
    pip_install socialscan
    deactivate_venv
    make_fish_fn "socialscan" \
        "source ${OSINT_HOME}/socialscan/venv/bin/activate.fish; socialscan \$argv; deactivate"
    ok "Socialscan ready → socialscan <username/email>"
}

# ── Sublist3r ─────────────────────────────────────────────────────────────────
register_tool "sublist3r" "Fast subdomain enumeration"
install_sublist3r() {
    banner "Sublist3r"
    local dir="${OSINT_HOME}/Sublist3r"
    git_clone "https://github.com/aboul3la/Sublist3r.git" "${dir}"
    create_venv "sublist3r"
    pip_install -r "${dir}/requirements.txt"
    deactivate_venv
    make_fish_fn "sublist3r" \
        "source ${OSINT_HOME}/sublist3r/venv/bin/activate.fish; python ${dir}/sublist3r.py \$argv; deactivate"
    ok "Sublist3r ready → sublist3r -d <domain>"
}

# ── Photon ────────────────────────────────────────────────────────────────────
register_tool "photon" "Fast intelligent web crawler for OSINT"
install_photon() {
    banner "Photon"
    local dir="${OSINT_HOME}/Photon"
    git_clone "https://github.com/s0md3v/Photon.git" "${dir}"
    create_venv "photon"
    pip_install -r "${dir}/requirements.txt"
    deactivate_venv
    make_fish_fn "photon" \
        "source ${OSINT_HOME}/photon/venv/bin/activate.fish; python ${dir}/photon.py \$argv; deactivate"
    ok "Photon ready → photon -u <url>"
}

# ── X-OSINT ───────────────────────────────────────────────────────────────────
register_tool "xosint" "All-in-one: phone/email/IP/host/domain OSINT"
install_xosint() {
    banner "X-OSINT"
    local dir="${OSINT_HOME}/X-osint"
    git_clone "https://github.com/TermuxHackz/X-osint.git" "${dir}"
    create_venv "xosint"
    [[ -f "${dir}/requirements.txt" ]] && pip_install -r "${dir}/requirements.txt"
    deactivate_venv
    make_fish_fn "xosint" \
        "source ${OSINT_HOME}/xosint/venv/bin/activate.fish; python ${dir}/xosint.py \$argv; deactivate"
    ok "X-OSINT ready → xosint"
}

# ── GhostIntel ────────────────────────────────────────────────────────────────
register_tool "ghostintel" "Zero-API OSINT: username/email/domain/IP/phone – 129+ platforms"
install_ghostintel() {
    banner "GhostIntel"
    local dir="${OSINT_HOME}/GhostIntel"
    git_clone "https://github.com/ruyynn/GhostIntel.git" "${dir}"
    create_venv "ghostintel"
    [[ -f "${dir}/requirements.txt" ]] && pip_install -r "${dir}/requirements.txt"
    deactivate_venv
    local entry
    entry=$(find "${dir}" -maxdepth 1 -name "*.py" | head -1)
    make_fish_fn "ghostintel" \
        "source ${OSINT_HOME}/ghostintel/venv/bin/activate.fish; python ${entry} \$argv; deactivate"
    ok "GhostIntel ready → ghostintel -u <username>"
}

# ── OpenOSINT (AI-powered) ────────────────────────────────────────────────────
register_tool "openosint" "AI-powered OSINT agent (Claude/GPT/Ollama)"
install_openosint() {
    banner "OpenOSINT"
    local dir="${OSINT_HOME}/OpenOSINT"
    git_clone "https://github.com/CogitoGITHUB/OpenOSINT.git" "${dir}"
    create_venv "openosint"
    [[ -f "${dir}/requirements.txt" ]] && pip_install -r "${dir}/requirements.txt" || \
        pip_install anthropic openai rich dnspython requests python-whois phonenumbers Pillow
    deactivate_venv
    make_fish_fn "openosint" \
        "source ${OSINT_HOME}/openosint/venv/bin/activate.fish; python -m openosint \$argv; deactivate"
    ok "OpenOSINT ready → openosint investigate <target>  (needs ANTHROPIC_API_KEY or --provider openai)"
}

# ── MOSINT ────────────────────────────────────────────────────────────────────
register_tool "mosint" "Fast email OSINT – social-account detection"
install_mosint() {
    banner "MOSINT"
    local goarch
    goarch=$(uname -m | sed 's/aarch64/arm64/;s/armv7l/arm/')
    local url
    url=$(curl -s "https://api.github.com/repos/alpkeskin/mosint/releases/latest" \
        | python -c "import sys,json; assets=json.load(sys.stdin)['assets']; \
          print(next((a['browser_download_url'] for a in assets \
          if 'linux' in a['name'].lower() and '${goarch}' in a['name'].lower()), ''), end='')" 2>/dev/null || echo "")
    if [[ -n "${url}" ]]; then
        local tmp="${OSINT_HOME}/mosint_dl"
        mkdir -p "${tmp}"
        retry 3 10 curl -L -o "${tmp}/mosint" "${url}"
        chmod +x "${tmp}/mosint"
        cp "${tmp}/mosint" "${BIN}/mosint"
    else
        warn "No pre-built MOSINT binary – building from Go…"
        local dir="${OSINT_HOME}/mosint"
        git_clone "https://github.com/alpkeskin/mosint.git" "${dir}"
        ( cd "${dir}" && retry 3 30 go build -o "${BIN}/mosint" . )
    fi
    make_fish_fn "mosint" "command mosint \$argv"
    ok "MOSINT ready → mosint <email>"
}

# ── Amass ─────────────────────────────────────────────────────────────────────
register_tool "amass" "OWASP network attack surface mapping"
install_amass() {
    banner "Amass"
    local goarch
    goarch=$(uname -m | sed 's/aarch64/arm64/;s/armv7l/arm/')
    local url
    url=$(curl -s "https://api.github.com/repos/owasp-amass/amass/releases/latest" \
        | python -c "import sys,json; assets=json.load(sys.stdin)['assets']; \
          print(next((a['browser_download_url'] for a in assets \
          if 'linux' in a['name'].lower() and '${goarch}' in a['name'].lower() and a['name'].endswith('.zip')), ''), end='')" 2>/dev/null || echo "")
    if [[ -n "${url}" ]]; then
        local tmp="${OSINT_HOME}/amass_dl"
        mkdir -p "${tmp}"
        retry 3 10 curl -L -o "${tmp}/amass.zip" "${url}"
        unzip -q -o "${tmp}/amass.zip" -d "${tmp}"
        local bin
        bin=$(find "${tmp}" -type f -name "amass" | head -1)
        [[ -n "${bin}" ]] && { chmod +x "${bin}"; cp "${bin}" "${BIN}/amass"; }
    else
        warn "No pre-built Amass binary – installing via go install…"
        retry 3 60 go install github.com/owasp-amass/amass/v4/...@latest
        cp "${HOME}/go/bin/amass" "${BIN}/amass" 2>/dev/null || true
    fi
    make_fish_fn "amass" "command amass \$argv"
    ok "Amass ready → amass enum -d <domain>"
}

# ── ExifTool ──────────────────────────────────────────────────────────────────
register_tool "exiftool" "Image/file metadata extractor"
install_exiftool() {
    banner "ExifTool"
    if command -v exiftool &>/dev/null; then
        ok "ExifTool already installed via pkg."
    else
        pkg_install perl
        git_clone "https://github.com/exiftool/exiftool.git" "${OSINT_HOME}/exiftool"
        ln -sf "${OSINT_HOME}/exiftool/exiftool" "${BIN}/exiftool"
        chmod +x "${BIN}/exiftool"
    fi
    make_fish_fn "exiftool" "command exiftool \$argv"
    ok "ExifTool ready → exiftool <file>"
}

# ── Metagoofil ────────────────────────────────────────────────────────────────
register_tool "metagoofil" "Metadata extraction from public documents"
install_metagoofil() {
    banner "Metagoofil"
    local dir="${OSINT_HOME}/metagoofil"
    git_clone "https://github.com/laramies/metagoofil.git" "${dir}"
    create_venv "metagoofil"
    [[ -f "${dir}/requirements.txt" ]] && \
        CFLAGS="-I${PREFIX}/include" pip_install -r "${dir}/requirements.txt"
    deactivate_venv
    make_fish_fn "metagoofil" \
        "source ${OSINT_HOME}/metagoofil/venv/bin/activate.fish; python ${dir}/metagoofil.py \$argv; deactivate"
    ok "Metagoofil ready → metagoofil -d <domain> -t pdf"
}

# ── Shodan CLI ────────────────────────────────────────────────────────────────
register_tool "shodan" "Shodan CLI – internet-connected device search"
install_shodan() {
    banner "Shodan CLI"
    create_venv "shodan"
    pip_install shodan
    deactivate_venv
    make_fish_fn "shodan" \
        "source ${OSINT_HOME}/shodan/venv/bin/activate.fish; shodan \$argv; deactivate"
    ok "Shodan CLI ready → shodan init <API_KEY> && shodan search apache"
}

# ── Linkook ───────────────────────────────────────────────────────────────────
register_tool "linkook" "Social link crawler / cross-platform connector"
install_linkook() {
    banner "Linkook"
    create_venv "linkook"
    pip_install linkook 2>/dev/null || {
        warn "PyPI install failed – cloning from GitHub…"
        local dir="${OSINT_HOME}/linkook"
        git_clone "https://github.com/JackJuly/linkook.git" "${dir}"
        pip_install "${dir}"
    }
    deactivate_venv
    make_fish_fn "linkook" \
        "source ${OSINT_HOME}/linkook/venv/bin/activate.fish; linkook \$argv; deactivate"
    ok "Linkook ready → linkook -u <username>"
}

# =============================================================================
#  PHASE 3 – Fish shell environment configuration
# =============================================================================
configure_fish() {
    banner "Phase 3 – Fish shell configuration"

    cat > "${FISH_CONF}/osint.fish" <<'FISHCONF'
# ── OSINT Suite – auto-generated by Pen15 installer ──────────────────────────

set -gx OSINT_HOME $HOME/.osint
set -gx GOPATH $HOME/go
fish_add_path $HOME/go/bin

# Quick-launch menu
function osint-menu
    echo ""
    echo "╔══════════════════════════════════════════════════════╗"
    echo "║          Pen15 OSINT Suite – Quick Reference         ║"
    echo "╠══════════════════════════════════════════════════════╣"
    echo "║  sherlock      <username>    – 300+ social networks  ║"
    echo "║  maigret       <username>    – 3000+ sites           ║"
    echo "║  theharvester  -d <domain>   – emails/subdomains     ║"
    echo "║  spiderfoot    -l 0.0.0.0:5009 – web UI             ║"
    echo "║  sqlmap        -u <url>      – SQL injection         ║"
    echo "║  recon-ng                    – recon framework       ║"
    echo "║  holehe        <email>       – 120+ site check       ║"
    echo "║  h8mail        -t <email>    – breach hunt           ║"
    echo "║  phoneinfoga   scan -n +X    – phone OSINT           ║"
    echo "║  ghunt         email <email> – Google account        ║"
    echo "║  blackbird     -u <username> – 600+ sites            ║"
    echo "║  socialscan    <username>    – availability check    ║"
    echo "║  sublist3r     -d <domain>   – subdomain enum        ║"
    echo "║  photon        -u <url>      – web crawler           ║"
    echo "║  xosint                      – all-in-one menu       ║"
    echo "║  ghostintel    -u <username> – 129+ platforms        ║"
    echo "║  openosint     investigate   – AI-powered agent      ║"
    echo "║  mosint        <email>       – email social scan     ║"
    echo "║  amass         enum -d <domain> – attack surface    ║"
    echo "║  exiftool      <file>        – metadata extractor    ║"
    echo "║  metagoofil    -d <domain>   – public doc metadata   ║"
    echo "║  shodan        search <query> – device search        ║"
    echo "║  linkook       -u <username> – link crawler          ║"
    echo "╠══════════════════════════════════════════════════════╣"
    echo "║  osint-update               – update all tools       ║"
    echo "╚══════════════════════════════════════════════════════╝"
    echo ""
end

# Update all tools
function osint-update
    echo "[*] Updating OSINT tools…"
    for d in $OSINT_HOME/*/
        if test -d $d/.git
            echo "Updating: $d"
            git -C $d pull --ff-only 2>/dev/null; or git -C $d fetch --all
        end
    end
    echo "[✓] Git repos updated. Re-run pip installs if requirements changed."
end

# Handy aliases
abbr -a whos   'whois'
abbr -a nse    'nmap --script'
abbr -a osm    'osint-menu'
FISHCONF

    ok "Fish configuration written to ${FISH_CONF}/osint.fish"
    ok "Run 'osint-menu' in Fish for a quick-reference cheatsheet."
}

# =============================================================================
#  PHASE 4 – Run all installers with per-tool error isolation
# =============================================================================
run_all_installers() {
    banner "Phase 2 – Installing OSINT tools"
    local -A INSTALL_FN=(
        [sherlock]=install_sherlock
        [maigret]=install_maigret
        [theharvester]=install_theharvester
        [spiderfoot]=install_spiderfoot
        [sqlmap]=install_sqlmap
        [recon-ng]=install_reconng
        [holehe]=install_holehe
        [h8mail]=install_h8mail
        [phoneinfoga]=install_phoneinfoga
        [ghunt]=install_ghunt
        [blackbird]=install_blackbird
        [socialscan]=install_socialscan
        [sublist3r]=install_sublist3r
        [photon]=install_photon
        [xosint]=install_xosint
        [ghostintel]=install_ghostintel
        [openosint]=install_openosint
        [mosint]=install_mosint
        [amass]=install_amass
        [exiftool]=install_exiftool
        [metagoofil]=install_metagoofil
        [shodan]=install_shodan
        [linkook]=install_linkook
    )

    for tool in "${TOOL_ORDER[@]}"; do
        fn="${INSTALL_FN[$tool]:-}"
        if [[ -z "${fn}" ]]; then
            warn "No installer registered for: ${tool}"
            continue
        fi
        if "${fn}"; then
            : # success logged inside each function
        else
            mark_failed "${tool}"
        fi
    done
}

# =============================================================================
#  PHASE 5 – Retry failed tools once
# =============================================================================
retry_failed_tools() {
    if [[ ! -s "${FAILED_LOG}" ]]; then
        ok "No failed tools – all installed successfully!"
        return 0
    fi

    banner "Phase 5 – Retrying failed tools"
    local -A INSTALL_FN=(
        [sherlock]=install_sherlock     [maigret]=install_maigret
        [theharvester]=install_theharvester [spiderfoot]=install_spiderfoot
        [sqlmap]=install_sqlmap         [recon-ng]=install_reconng
        [holehe]=install_holehe         [h8mail]=install_h8mail
        [phoneinfoga]=install_phoneinfoga [ghunt]=install_ghunt
        [blackbird]=install_blackbird   [socialscan]=install_socialscan
        [sublist3r]=install_sublist3r   [photon]=install_photon
        [xosint]=install_xosint         [ghostintel]=install_ghostintel
        [openosint]=install_openosint   [mosint]=install_mosint
        [amass]=install_amass           [exiftool]=install_exiftool
        [metagoofil]=install_metagoofil [shodan]=install_shodan
        [linkook]=install_linkook
    )
    > "${FAILED_LOG}.round2"
    while IFS= read -r tool; do
        fn="${INSTALL_FN[$tool]:-}"
        if [[ -n "${fn}" ]]; then
            info "Retrying ${tool}…"
            if "${fn}"; then
                ok "${tool} installed on retry."
            else
                warn "${tool} still failed – see ${LOG} for details."
                echo "${tool}" >> "${FAILED_LOG}.round2"
            fi
        fi
    done < "${FAILED_LOG}"
    mv "${FAILED_LOG}.round2" "${FAILED_LOG}"
}

# =============================================================================
#  PHASE 6 – Summary
# =============================================================================
print_summary() {
    banner "Installation Summary"
    local total=${#TOOL_ORDER[@]}
    local failed=0
    [[ -s "${FAILED_LOG}" ]] && failed=$(wc -l < "${FAILED_LOG}")
    local passed=$(( total - failed ))

    echo -e "${GRN}Installed: ${passed}/${total}${RST}"
    if (( failed > 0 )); then
        echo -e "${RED}Failed (${failed}):${RST}"
        cat "${FAILED_LOG}"
        echo ""
        echo "Tip: Check ${LOG} for error details."
        echo "     Run  bash ${0}  again – the script skips already-cloned repos."
    fi

    echo ""
    echo -e "${BLD}Next steps:${RST}"
    echo "  1. Open Fish shell:       fish"
    echo "  2. See all tools:         osint-menu"
    echo "  3. Configure Shodan:      shodan init <YOUR_API_KEY>"
    echo "  4. For AI-powered OSINT:  set -gx ANTHROPIC_API_KEY <key>"
    echo "  5. Update all tools:      osint-update"
    echo ""
    echo "Full log: ${LOG}"
}

# =============================================================================
#  MAIN
# =============================================================================
main() {
    echo -e "${BLD}${CYN}"
    cat <<'BANNER'
  ____  _____ _   _ _ ____
 |  _ \| ____| \ | / | ___|
 | |_) |  _| |  \| | |___ \
 |  __/| |___| |\  | |___) |
 |_|   |_____|_| \_|_|____/
    OSINT Suite Installer — Termux ARM
BANNER
    echo -e "${RST}"

    check_termux
    install_system_deps
    run_all_installers
    retry_failed_tools
    configure_fish
    print_summary
}

main "$@"
