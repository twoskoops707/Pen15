#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# ARM-friendly Termux OSINT installer with fish shell integration.
# Installs tools from GitHub repos and keeps detailed logs for troubleshooting.

SCRIPT_VERSION="1.0.0"
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
OSINT_HOME="${HOME_DIR}/osint"
TOOLS_DIR="${OSINT_HOME}/tools"
VENV_DIR="${OSINT_HOME}/venvs"
BIN_DIR="${HOME_DIR}/.local/bin"
LOG_DIR="${OSINT_HOME}/logs"
NOW="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="${LOG_DIR}/install-${NOW}.log"
FAILED_ITEMS=()

mkdir -p "${TOOLS_DIR}" "${VENV_DIR}" "${BIN_DIR}" "${LOG_DIR}"
exec > >(tee -a "${LOG_FILE}") 2>&1

info() { printf '[INFO] %s\n' "$*"; }
warn() { printf '[WARN] %s\n' "$*" >&2; }
err() { printf '[ERROR] %s\n' "$*" >&2; }

on_error() {
  local code="$?"
  err "Installer failed at line ${BASH_LINENO[0]} (exit ${code})."
  err "Log file: ${LOG_FILE}"
  create_support_bundle || true
  exit "${code}"
}
trap on_error ERR

usage() {
  cat <<'EOF'
Usage: ./install_osint_termux_fish.sh [options]

Options:
  --skip-spiderfoot      Skip SpiderFoot installation
  --only <tool1,tool2>   Install only selected tools
  --update-only          Pull latest repos and update pip deps
  --help                 Show this help

Tool names:
  sherlock,theharvester,sqlmap,spiderfoot,maigret,holehe,bbot,photon
EOF
}

SKIP_SPIDERFOOT=0
UPDATE_ONLY=0
ONLY_TOOLS=""

while [[ "${#}" -gt 0 ]]; do
  case "$1" in
    --skip-spiderfoot) SKIP_SPIDERFOOT=1 ;;
    --update-only) UPDATE_ONLY=1 ;;
    --only)
      shift
      ONLY_TOOLS="${1:-}"
      if [[ -z "${ONLY_TOOLS}" ]]; then
        err "--only requires a comma-separated list."
        exit 1
      fi
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      err "Unknown argument: $1"
      usage
      exit 1
      ;;
  esac
  shift
done

retry() {
  local attempts="${1}"
  shift
  local n=1
  local delay=2
  while true; do
    if "$@"; then
      return 0
    fi
    if [[ "${n}" -ge "${attempts}" ]]; then
      return 1
    fi
    warn "Command failed (attempt ${n}/${attempts}): $*"
    sleep "${delay}"
    n=$((n + 1))
    delay=$((delay * 2))
  done
}

has_tool_selected() {
  local name="$1"
  if [[ -z "${ONLY_TOOLS}" ]]; then
    return 0
  fi
  IFS=',' read -r -a requested <<<"${ONLY_TOOLS}"
  local t
  for t in "${requested[@]}"; do
    if [[ "${t}" == "${name}" ]]; then
      return 0
    fi
  done
  return 1
}

record_failure() {
  FAILED_ITEMS+=("$1")
  warn "Marked as failed: $1"
}

pkg_install() {
  local package="$1"
  retry 3 pkg install -y "${package}"
}

ensure_fish_path() {
  mkdir -p "${HOME_DIR}/.config/fish"
  local fish_config="${HOME_DIR}/.config/fish/config.fish"
  touch "${fish_config}"

  if ! grep -q "set -gx PATH ${BIN_DIR}" "${fish_config}"; then
    printf '\n# Added by OSINT installer\nset -gx PATH %s $PATH\n' "${BIN_DIR}" >>"${fish_config}"
  fi
  if ! grep -q "set -gx OSINT_HOME ${OSINT_HOME}" "${fish_config}"; then
    printf 'set -gx OSINT_HOME %s\n' "${OSINT_HOME}" >>"${fish_config}"
  fi
}

clone_or_update() {
  local repo_url="$1"
  local target_dir="$2"

  if [[ -d "${target_dir}/.git" ]]; then
    info "Updating $(basename "${target_dir}") ..."
    retry 3 git -C "${target_dir}" pull --ff-only
  else
    info "Cloning ${repo_url} ..."
    retry 3 git clone --depth 1 "${repo_url}" "${target_dir}"
  fi
}

ensure_venv() {
  local venv_path="$1"
  if [[ ! -d "${venv_path}" ]]; then
    python -m venv "${venv_path}"
  fi
}

install_requirements() {
  local venv_path="$1"
  local req_file="$2"
  local cwd="$3"

  "${venv_path}/bin/python" -m pip install --upgrade pip wheel setuptools
  if [[ -f "${cwd}/${req_file}" ]]; then
    retry 2 "${venv_path}/bin/pip" install -r "${cwd}/${req_file}"
  else
    warn "Requirements file not found: ${cwd}/${req_file}. Trying editable install."
    retry 2 "${venv_path}/bin/pip" install -e "${cwd}"
  fi
}

make_launcher() {
  local name="$1"
  local run_cmd="$2"
  local launcher="${BIN_DIR}/${name}"

  cat >"${launcher}" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
set -e
${run_cmd} "\$@"
EOF
  chmod +x "${launcher}"
}

install_python_tool() {
  local name="$1"
  local repo="$2"
  local requirements="$3"
  local entry="$4"
  local tool_dir="${TOOLS_DIR}/${name}"
  local venv_path="${VENV_DIR}/${name}"

  if ! has_tool_selected "${name}"; then
    return 0
  fi

  info "Installing ${name} ..."
  if ! clone_or_update "${repo}" "${tool_dir}"; then
    record_failure "${name}: git clone/pull failed"
    return 1
  fi

  if [[ "${UPDATE_ONLY}" -eq 0 || ! -d "${venv_path}" ]]; then
    if ! ensure_venv "${venv_path}"; then
      record_failure "${name}: venv creation failed"
      return 1
    fi
  fi

  if ! install_requirements "${venv_path}" "${requirements}" "${tool_dir}"; then
    record_failure "${name}: pip requirements failed"
    return 1
  fi

  local run_cmd="${venv_path}/bin/python ${tool_dir}/${entry}"
  make_launcher "${name}" "${run_cmd}"
  info "${name} installed. Run with: ${name}"
}

install_bbot() {
  local name="bbot"
  if ! has_tool_selected "${name}"; then
    return 0
  fi
  info "Installing ${name} ..."
  local repo="https://github.com/blacklanternsecurity/bbot"
  local tool_dir="${TOOLS_DIR}/${name}"
  local venv_path="${VENV_DIR}/${name}"

  if ! clone_or_update "${repo}" "${tool_dir}"; then
    record_failure "${name}: git clone/pull failed"
    return 1
  fi
  ensure_venv "${venv_path}"
  "${venv_path}/bin/python" -m pip install --upgrade pip wheel setuptools
  if ! retry 2 "${venv_path}/bin/pip" install -e "${tool_dir}"; then
    record_failure "${name}: install failed"
    return 1
  fi
  make_launcher "${name}" "${venv_path}/bin/bbot"
}

install_spiderfoot_proot() {
  info "Installing SpiderFoot via proot-distro fallback (Debian) ..."
  retry 3 pkg install -y proot-distro

  if ! proot-distro list | grep -q '^debian'; then
    retry 2 proot-distro install debian
  fi

  local cmd="
set -e
apt update
DEBIAN_FRONTEND=noninteractive apt install -y git python3 python3-pip python3-venv build-essential libssl-dev swig
mkdir -p /root/osint/tools
if [ -d /root/osint/tools/spiderfoot/.git ]; then
  git -C /root/osint/tools/spiderfoot pull --ff-only
else
  git clone --depth 1 https://github.com/smicallef/spiderfoot /root/osint/tools/spiderfoot
fi
python3 -m venv /root/osint/venvs/spiderfoot
/root/osint/venvs/spiderfoot/bin/python -m pip install --upgrade pip wheel setuptools
/root/osint/venvs/spiderfoot/bin/pip install -r /root/osint/tools/spiderfoot/requirements.txt
"

  retry 2 proot-distro login debian -- bash -lc "${cmd}"
  make_launcher "spiderfoot" "proot-distro login debian -- bash -lc '/root/osint/venvs/spiderfoot/bin/python /root/osint/tools/spiderfoot/sf.py'"
  info "SpiderFoot (proot) installed. Run with: spiderfoot -l 127.0.0.1:5001"
}

install_spiderfoot() {
  local name="spiderfoot"
  if [[ "${SKIP_SPIDERFOOT}" -eq 1 ]] || ! has_tool_selected "${name}"; then
    return 0
  fi

  local repo="https://github.com/smicallef/spiderfoot"
  local tool_dir="${TOOLS_DIR}/${name}"
  local venv_path="${VENV_DIR}/${name}"

  info "Installing SpiderFoot (native Termux attempt) ..."
  if ! clone_or_update "${repo}" "${tool_dir}"; then
    record_failure "${name}: clone failed"
    return 1
  fi

  ensure_venv "${venv_path}"
  "${venv_path}/bin/python" -m pip install --upgrade pip wheel setuptools

  if retry 1 "${venv_path}/bin/pip" install -r "${tool_dir}/requirements.txt"; then
    make_launcher "spiderfoot" "${venv_path}/bin/python ${tool_dir}/sf.py"
    info "SpiderFoot installed natively."
    return 0
  fi

  warn "Native SpiderFoot install failed (common on ARM due to crypto builds)."
  if ! install_spiderfoot_proot; then
    record_failure "${name}: proot fallback failed"
    return 1
  fi
}

create_support_bundle() {
  local bundle="${LOG_DIR}/support-bundle-${NOW}.tar.gz"
  tar -czf "${bundle}" -C "${OSINT_HOME}" logs || true
  warn "Support bundle created: ${bundle}"
}

main() {
  info "OSINT Installer v${SCRIPT_VERSION}"
  info "Logs: ${LOG_FILE}"
  info "Preparing Termux package base ..."

  retry 3 pkg update -y
  retry 3 pkg upgrade -y

  local base_packages=(
    git python python-pip fish curl wget jq openssl openssl-tool libffi
    clang make cmake rust pkg-config libxml2 libxslt libjpeg-turbo zlib
  )
  local p
  for p in "${base_packages[@]}"; do
    pkg_install "${p}"
  done

  ensure_fish_path
  mkdir -p "${HOME_DIR}/.config/fish/functions"

  install_python_tool "sherlock" \
    "https://github.com/sherlock-project/sherlock" \
    "requirements.txt" \
    "sherlock_project/sherlock.py" || true

  install_python_tool "theharvester" \
    "https://github.com/laramies/theHarvester" \
    "requirements/base.txt" \
    "theHarvester.py" || true

  install_python_tool "sqlmap" \
    "https://github.com/sqlmapproject/sqlmap" \
    "requirements.txt" \
    "sqlmap.py" || true

  install_python_tool "maigret" \
    "https://github.com/soxoj/maigret" \
    "requirements.txt" \
    "maigret.py" || true

  install_python_tool "holehe" \
    "https://github.com/megadose/holehe" \
    "requirements.txt" \
    "holehe.py" || true

  install_python_tool "photon" \
    "https://github.com/s0md3v/Photon" \
    "requirements.txt" \
    "photon.py" || true

  install_bbot || true
  install_spiderfoot || true

  create_support_bundle

  echo
  if [[ "${#FAILED_ITEMS[@]}" -gt 0 ]]; then
    warn "Completed with some failures:"
    printf ' - %s\n' "${FAILED_ITEMS[@]}"
    warn "Open log for details: ${LOG_FILE}"
  else
    info "All selected tools installed successfully."
  fi

  cat <<EOF

Next steps:
1) Restart fish (or run: source ~/.config/fish/config.fish)
2) Test:
   sherlock --help
   theharvester -h
   sqlmap -h
   maigret --help
   holehe --help
   bbot --help
3) SpiderFoot:
   spiderfoot -l 127.0.0.1:5001

If something breaks, share this log/support bundle:
  ${LOG_FILE}
  ${LOG_DIR}/support-bundle-${NOW}.tar.gz
EOF
}

main "$@"
