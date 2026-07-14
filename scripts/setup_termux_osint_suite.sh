#!/data/data/com.termux/files/usr/bin/bash
#
# ARM Termux OSINT bootstrap installer for fish shell users.
# - Installs base build/runtime dependencies
# - Clones/updates major OSINT repositories from GitHub
# - Creates isolated Python virtualenvs per tool
# - Adds fish shell helpers and PATH wiring
# - Retries common transient failures and stores detailed logs

set -Eeuo pipefail

readonly SCRIPT_NAME="$(basename "$0")"
readonly DATE_STAMP="$(date +"%Y%m%d-%H%M%S")"
readonly LOG_DIR="${HOME}/.osint-installer/logs"
readonly LOG_FILE="${LOG_DIR}/install-${DATE_STAMP}.log"
readonly INSTALL_ROOT="${OSINT_INSTALL_ROOT:-${HOME}/osint-suite}"
readonly TOOL_ROOT="${INSTALL_ROOT}/tools"
readonly VENV_ROOT="${INSTALL_ROOT}/venvs"
readonly BIN_ROOT="${HOME}/.local/bin"
readonly FISH_CONFIG="${HOME}/.config/fish/config.fish"

mkdir -p "${LOG_DIR}" "${TOOL_ROOT}" "${VENV_ROOT}" "${BIN_ROOT}" "$(dirname "${FISH_CONFIG}")"
touch "${LOG_FILE}"

exec > >(tee -a "${LOG_FILE}") 2>&1

log() {
  printf '[%s] [INFO] %s\n' "$(date +"%H:%M:%S")" "$*"
}

warn() {
  printf '[%s] [WARN] %s\n' "$(date +"%H:%M:%S")" "$*"
}

error() {
  printf '[%s] [ERROR] %s\n' "$(date +"%H:%M:%S")" "$*" >&2
}

report_failure() {
  local exit_code="$1"
  local line_no="$2"
  error "Installer failed on line ${line_no} (exit ${exit_code})."
  error "Full log: ${LOG_FILE}"
  if [[ -n "${OSINT_INSTALLER_WEBHOOK_URL:-}" ]]; then
    warn "Attempting to send failure report to webhook."
    curl -fsS -X POST "${OSINT_INSTALLER_WEBHOOK_URL}" \
      -H "Content-Type: application/json" \
      -d "{\"script\":\"${SCRIPT_NAME}\",\"exit_code\":${exit_code},\"line\":${line_no},\"log_file\":\"${LOG_FILE}\"}" \
      || warn "Webhook reporting failed. Check connectivity or URL."
  fi
}

trap 'report_failure "$?" "$LINENO"' ERR

retry() {
  local attempts="$1"
  local wait_seconds="$2"
  shift 2
  local i
  for ((i = 1; i <= attempts; i++)); do
    if "$@"; then
      return 0
    fi
    warn "Attempt ${i}/${attempts} failed: $*"
    if (( i < attempts )); then
      sleep "${wait_seconds}"
    fi
  done
  return 1
}

require_termux_arm() {
  if [[ ! -d "/data/data/com.termux/files/usr" ]]; then
    error "This script is intended for Termux."
    exit 1
  fi
  local arch
  arch="$(uname -m)"
  case "${arch}" in
    aarch64|armv7l|armv8*|arm64)
      log "Detected supported ARM architecture: ${arch}"
      ;;
    *)
      warn "Detected architecture ${arch}. Continuing, but this script is tuned for ARM."
      ;;
  esac
}

install_base_packages() {
  log "Updating package metadata..."
  retry 3 5 pkg update -y
  retry 3 5 pkg upgrade -y

  local packages=(
    fish
    git
    curl
    wget
    python
    python-pip
    openssl
    libffi
    libxml2
    libxslt
    zlib
    rust
    clang
    make
    cmake
    pkg-config
    libjpeg-turbo
    libpcap
    nmap
    jq
    go
    coreutils
    findutils
    gnupg
  )

  log "Installing base dependencies..."
  retry 3 5 pkg install -y "${packages[@]}"

  log "Upgrading Python packaging tooling..."
  retry 3 5 python -m pip install --upgrade pip setuptools wheel
  retry 3 5 python -m pip install --upgrade virtualenv pipx
  python -m pipx ensurepath || true
}

clone_or_update_repo() {
  local name="$1"
  local repo_url="$2"
  local destination="${TOOL_ROOT}/${name}"

  if [[ -d "${destination}/.git" ]]; then
    log "Updating ${name}..."
    retry 3 5 git -C "${destination}" fetch --all --tags
    retry 3 5 git -C "${destination}" pull --ff-only
  else
    log "Cloning ${name}..."
    retry 3 5 git clone --depth 1 "${repo_url}" "${destination}"
  fi
}

install_python_tool() {
  local name="$1"
  local entrypoint="$2"
  local repo_path="${TOOL_ROOT}/${name}"
  local venv_path="${VENV_ROOT}/${name}"

  log "Configuring Python environment for ${name}..."
  python -m venv "${venv_path}"
  # shellcheck source=/dev/null
  source "${venv_path}/bin/activate"

  retry 3 5 pip install --upgrade pip setuptools wheel

  if [[ -f "${repo_path}/requirements/base.txt" ]]; then
    retry 2 5 pip install -r "${repo_path}/requirements/base.txt" || {
      warn "Primary dependency install failed for ${name}; trying with build helpers."
      retry 2 5 pip install --upgrade cython
      retry 2 5 pip install -r "${repo_path}/requirements/base.txt"
    }
  fi

  if [[ -f "${repo_path}/requirements.txt" ]]; then
    retry 2 5 pip install -r "${repo_path}/requirements.txt" || {
      warn "requirements.txt install failed for ${name}; retrying once after build helper refresh."
      retry 2 5 pip install --upgrade cython
      retry 2 5 pip install -r "${repo_path}/requirements.txt"
    }
  fi

  if [[ -f "${repo_path}/pyproject.toml" || -f "${repo_path}/setup.py" ]]; then
    retry 2 5 pip install -e "${repo_path}" || warn "Editable install skipped for ${name}."
  fi

  deactivate

  cat > "${BIN_ROOT}/${name}" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
source "${venv_path}/bin/activate"
exec ${entrypoint} "\$@"
EOF
  chmod +x "${BIN_ROOT}/${name}"
}

install_sqlmap_wrapper() {
  local repo_path="${TOOL_ROOT}/sqlmap"
  cat > "${BIN_ROOT}/sqlmap" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
exec python "${repo_path}/sqlmap.py" "\$@"
EOF
  chmod +x "${BIN_ROOT}/sqlmap"
}

install_go_tools() {
  log "Installing extra reconnaissance binaries via Go..."
  export GOPATH="${HOME}/go"
  export PATH="${PATH}:${GOPATH}/bin"

  retry 2 5 go install github.com/tomnomnom/assetfinder@latest || warn "assetfinder install failed."
  retry 2 5 go install github.com/projectdiscovery/subfinder/v2/cmd/subfinder@latest || warn "subfinder install failed."
  retry 2 5 go install github.com/projectdiscovery/httpx/cmd/httpx@latest || warn "httpx install failed."
}

configure_fish() {
  log "Configuring fish shell environment..."
  local marker_start="# >>> osint-installer >>>"
  local marker_end="# <<< osint-installer <<<"

  if ! grep -Fq "${marker_start}" "${FISH_CONFIG}" 2>/dev/null; then
    {
      echo ""
      echo "${marker_start}"
      echo "set -gx OSINT_INSTALL_ROOT \"${INSTALL_ROOT}\""
      echo "fish_add_path \$HOME/.local/bin \$HOME/go/bin"
      echo ""
      echo "function osint-use"
      echo "    if test (count \$argv) -lt 1"
      echo "        echo \"Usage: osint-use <tool-name>\""
      echo "        return 1"
      echo "    end"
      echo "    set tool \"\$argv[1]\""
      echo "    set venv \"${VENV_ROOT}/\$tool\""
      echo "    if not test -f \"\$venv/bin/activate.fish\""
      echo "        echo \"No venv found at \$venv\""
      echo "        return 1"
      echo "    end"
      echo "    source \"\$venv/bin/activate.fish\""
      echo "    echo \"Activated \$tool venv\""
      echo "end"
      echo "${marker_end}"
    } >> "${FISH_CONFIG}"
  else
    log "fish config already contains osint-installer block."
  fi
}

install_tools() {
  # Core OSINT set requested by user + practical additions.
  clone_or_update_repo "sherlock" "https://github.com/sherlock-project/sherlock.git"
  clone_or_update_repo "theHarvester" "https://github.com/laramies/theHarvester.git"
  clone_or_update_repo "sqlmap" "https://github.com/sqlmapproject/sqlmap.git"
  clone_or_update_repo "spiderfoot" "https://github.com/smicallef/spiderfoot.git"
  clone_or_update_repo "maigret" "https://github.com/soxoj/maigret.git"
  clone_or_update_repo "holehe" "https://github.com/megadose/holehe.git"
  clone_or_update_repo "photon" "https://github.com/s0md3v/Photon.git"
  clone_or_update_repo "bbot" "https://github.com/blacklanternsecurity/bbot.git"

  install_python_tool "sherlock" "python ${TOOL_ROOT}/sherlock/sherlock_project/sherlock.py"
  install_python_tool "theHarvester" "python ${TOOL_ROOT}/theHarvester/theHarvester.py"
  install_python_tool "spiderfoot" "python ${TOOL_ROOT}/spiderfoot/sf.py"
  install_python_tool "maigret" "python ${TOOL_ROOT}/maigret/maigret.py"
  install_python_tool "holehe" "python ${TOOL_ROOT}/holehe/holehe.py"
  install_python_tool "photon" "python ${TOOL_ROOT}/photon/photon.py"
  install_python_tool "bbot" "python -m bbot.cli"
  install_sqlmap_wrapper

  install_go_tools
}

print_summary() {
  log "Installation completed."
  echo ""
  echo "Installed root: ${INSTALL_ROOT}"
  echo "Tool launchers : ${BIN_ROOT}"
  echo "Install log    : ${LOG_FILE}"
  echo ""
  echo "Available commands:"
  echo "  sherlock --help"
  echo "  theHarvester -h"
  echo "  sqlmap -hh"
  echo "  spiderfoot -h"
  echo "  maigret --help"
  echo "  holehe --help"
  echo "  photon --help"
  echo "  bbot --help"
  echo "  assetfinder -h"
  echo "  subfinder -h"
  echo "  httpx -h"
  echo ""
  echo "If fish is your login shell, reload with:"
  echo "  source ~/.config/fish/config.fish"
}

main() {
  require_termux_arm
  install_base_packages
  install_tools
  configure_fish
  print_summary
}

main "$@"
