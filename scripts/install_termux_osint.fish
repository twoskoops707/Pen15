#!/data/data/com.termux/files/usr/bin/fish
# Install OSINT and recon tools on ARM Termux with fish shell integration.
# Intended for authorized testing, personal recon, and systems you have permission to assess.

set -gx TERMUX_PREFIX (set -q PREFIX; and echo $PREFIX; or echo /data/data/com.termux/files/usr)
set -gx PATH $TERMUX_PREFIX/bin $HOME/.local/bin $HOME/go/bin $PATH
set -g INSTALL_ROOT "$HOME/osint-tools"
set -g LOG_DIR "$HOME/.pen15/logs"
set -g FISH_CONF_DIR "$HOME/.config/fish/conf.d"
set -g RUN_ID (date +%Y%m%d_%H%M%S)
set -g LOG_FILE "$LOG_DIR/osint-install-$RUN_ID.log"
set -g REPORT_FILE "$LOG_DIR/osint-install-report-$RUN_ID.txt"
set -g MAX_RETRIES 3
set -g FAILURES
set -g WARNINGS

function banner
    echo ""
    echo "============================================================"
    echo "  PEN15 Termux ARM OSINT Installer"
    echo "============================================================"
    echo "Authorized testing only. Do not scan or attack systems unless"
    echo "you own them or have explicit written permission."
    echo ""
end

function log_line
    set -l message $argv
    mkdir -p "$LOG_DIR"
    printf "[%s] %s\n" (date "+%Y-%m-%d %H:%M:%S") "$message" | tee -a "$LOG_FILE"
end

function record_failure
    set -a FAILURES "$argv"
    log_line "[FAIL] $argv"
end

function record_warning
    set -a WARNINGS "$argv"
    log_line "[WARN] $argv"
end

function run_cmd
    set -l label $argv[1]
    set -l cmd $argv[2..-1]

    log_line "[RUN] $label"
    for attempt in (seq 1 $MAX_RETRIES)
        log_line "attempt $attempt/$MAX_RETRIES: $cmd"
        eval $cmd 2>&1 | tee -a "$LOG_FILE"
        set -l status_code $pipestatus[1]
        if test "$status_code" -eq 0
            log_line "[OK] $label"
            return 0
        end

        log_line "[RETRY] $label exited with $status_code"
        if test "$attempt" -lt "$MAX_RETRIES"
            sleep (math "$attempt * 3")
        end
    end

    record_failure "$label"
    return 1
end

function ensure_dirs
    mkdir -p "$INSTALL_ROOT" "$LOG_DIR" "$FISH_CONF_DIR" "$HOME/.local/bin" "$HOME/.pen15"
end

function check_termux
    if not test -d /data/data/com.termux/files/usr
        record_warning "This does not look like Termux. The script will continue, but package installs may fail."
    end

    if test (uname -m) != "aarch64"
        record_warning "Detected architecture "(uname -m)". Samsung ARM64 phones usually report aarch64; binary installs may fall back to source builds."
    end
end

function install_base_packages
    run_cmd "Refresh Termux package metadata" "pkg update -y"
    run_cmd "Upgrade installed Termux packages" "pkg upgrade -y"
    run_cmd "Install base dependencies" "pkg install -y fish git curl wget jq tar unzip xz-utils coreutils findutils grep sed gawk proot resolv-conf"
    run_cmd "Install Python, pipx, and build dependencies" "pkg install -y python python-pip python-cryptography clang make cmake pkg-config rust openssl libffi libxml2 libxslt zlib"
    run_cmd "Install network and recon packages available in Termux" "pkg install -y nmap whois dnsutils openssl termux-api"
    run_cmd "Install Go for modern recon tools" "pkg install -y golang"
    run_cmd "Prepare Python tooling" "python -m pip install --upgrade pip setuptools wheel pipx"
end

function clone_or_update
    set -l name $argv[1]
    set -l repo $argv[2]
    set -l dir "$INSTALL_ROOT/$name"

    if test -d "$dir/.git"
        run_cmd "Update $name" "git -C '$dir' pull --ff-only"
    else
        run_cmd "Clone $name" "git clone --depth 1 '$repo' '$dir'"
    end
end

function pipx_install
    set -l package $argv[1]
    set -l import_or_command $argv[2]

    if command -q "$import_or_command"
        log_line "[OK] $import_or_command already on PATH"
        return 0
    end

    run_cmd "Install $package with pipx" "python -m pipx install --force '$package'"
    or run_cmd "Fallback pip install $package" "python -m pip install --user --upgrade '$package'"
end

function install_python_osint
    pipx_install "sherlock-project" "sherlock"
    pipx_install "maigret" "maigret"
    pipx_install "holehe" "holehe"
    pipx_install "ghunt" "ghunt"
    pipx_install "waybackpy" "waybackpy"
    pipx_install "dnsrecon" "dnsrecon"
    pipx_install "socialscan" "socialscan"
    run_cmd "Install WhatsMyName-Python with pipx" "python -m pipx install --force 'git+https://github.com/C3n7ral051nt4g3ncy/WhatsMyName-Python.git'"

    clone_or_update "sqlmap" "https://github.com/sqlmapproject/sqlmap.git"
    if test -f "$INSTALL_ROOT/sqlmap/sqlmap.py"
        printf '#!/data/data/com.termux/files/usr/bin/sh\nexec python "%s/sqlmap/sqlmap.py" "$@"\n' "$INSTALL_ROOT" > "$HOME/.local/bin/sqlmap"
        chmod +x "$HOME/.local/bin/sqlmap"
        log_line "[OK] sqlmap wrapper installed at $HOME/.local/bin/sqlmap"
    else
        record_failure "sqlmap wrapper creation"
    end

    clone_or_update "theHarvester" "https://github.com/laramies/theHarvester.git"
    if test -d "$INSTALL_ROOT/theHarvester"
        run_cmd "Install uv for theHarvester" "python -m pip install --user --upgrade uv"
        run_cmd "Sync theHarvester dependencies" "cd '$INSTALL_ROOT/theHarvester'; python -m uv sync"
        printf '#!/data/data/com.termux/files/usr/bin/sh\ncd "%s/theHarvester" && exec python -m uv run theHarvester "$@"\n' "$INSTALL_ROOT" > "$HOME/.local/bin/theHarvester"
        chmod +x "$HOME/.local/bin/theHarvester"
    end

    clone_or_update "spiderfoot" "https://github.com/smicallef/spiderfoot.git"
    if test -d "$INSTALL_ROOT/spiderfoot"
        run_cmd "Create SpiderFoot virtualenv" "python -m venv '$INSTALL_ROOT/spiderfoot/.venv'"
        run_cmd "Install SpiderFoot dependencies" "'$INSTALL_ROOT/spiderfoot/.venv/bin/python' -m pip install --upgrade pip setuptools wheel; '$INSTALL_ROOT/spiderfoot/.venv/bin/python' -m pip install -r '$INSTALL_ROOT/spiderfoot/requirements.txt'"
        printf '#!/data/data/com.termux/files/usr/bin/sh\ncd "%s/spiderfoot" && exec .venv/bin/python sf.py "$@"\n' "$INSTALL_ROOT" > "$HOME/.local/bin/spiderfoot"
        chmod +x "$HOME/.local/bin/spiderfoot"
    end
end

function install_go_recon
    set -gx GOPATH "$HOME/go"
    mkdir -p "$GOPATH/bin"

    run_cmd "Install ProjectDiscovery subfinder" "go install -v github.com/projectdiscovery/subfinder/v2/cmd/subfinder@latest"
    run_cmd "Install ProjectDiscovery httpx" "go install -v github.com/projectdiscovery/httpx/cmd/httpx@latest"
    run_cmd "Install ProjectDiscovery nuclei" "go install -v github.com/projectdiscovery/nuclei/v3/cmd/nuclei@latest"
    run_cmd "Install OWASP Amass" "go install -v github.com/owasp-amass/amass/v4/...@latest"
    run_cmd "Install gau URL collector" "go install -v github.com/lc/gau/v2/cmd/gau@latest"
    run_cmd "Install waybackurls" "go install -v github.com/tomnomnom/waybackurls@latest"
    run_cmd "Install ffuf" "go install -v github.com/ffuf/ffuf/v2@latest"
    run_cmd "Install assetfinder" "go install -v github.com/tomnomnom/assetfinder@latest"
    run_cmd "Install TruffleHog secret scanner" "go install -v github.com/trufflesecurity/trufflehog/v3@latest"

    for bin in subfinder httpx nuclei amass gau waybackurls ffuf assetfinder trufflehog
        if test -x "$GOPATH/bin/$bin"
            ln -sf "$GOPATH/bin/$bin" "$HOME/.local/bin/$bin"
        else
            record_failure "$bin binary link"
        end
    end

    if not test -d "$HOME/nuclei-templates"
        run_cmd "Clone nuclei templates" "git clone --depth 1 https://github.com/projectdiscovery/nuclei-templates.git '$HOME/nuclei-templates'"
    else
        run_cmd "Update nuclei templates" "git -C '$HOME/nuclei-templates' pull --ff-only"
    end
end

function install_phoneinfoga
    set -l tmpdir "$HOME/.cache/phoneinfoga-install-$RUN_ID"
    mkdir -p "$tmpdir"

    run_cmd "Download PhoneInfoga installer" "curl -fsSL 'https://raw.githubusercontent.com/sundowndev/phoneinfoga/master/support/scripts/install' -o '$tmpdir/install-phoneinfoga.sh'"
    if test -f "$tmpdir/install-phoneinfoga.sh"
        run_cmd "Install PhoneInfoga ARM binary" "cd '$tmpdir'; bash install-phoneinfoga.sh; chmod +x phoneinfoga; install -m 755 phoneinfoga '$HOME/.local/bin/phoneinfoga'"
    end
end

function install_extra_repos
    clone_or_update "SecLists" "https://github.com/danielmiessler/SecLists.git"
    clone_or_update "PayloadsAllTheThings" "https://github.com/swisskyrepo/PayloadsAllTheThings.git"
    clone_or_update "OSINT-Framework" "https://github.com/lockfale/OSINT-Framework.git"
end

function write_fish_integration
    set -l conf "$FISH_CONF_DIR/pen15-osint.fish"
    begin
        echo "# PEN15 OSINT Termux helpers. Generated by scripts/install_termux_osint.fish."
        echo 'set -gx PATH $HOME/.local/bin $HOME/go/bin $PATH'
        echo 'set -gx PEN15_OSINT_HOME $HOME/osint-tools'
        echo 'set -gx PEN15_LOG_DIR $HOME/.pen15/logs'
        echo ""
        echo "alias osint-tools='ls \$PEN15_OSINT_HOME'"
        echo "alias osint-logs='ls -lt \$PEN15_LOG_DIR'"
        echo "alias osint-report='set latest (ls -t \$PEN15_LOG_DIR/osint-install-report-*.txt 2>/dev/null | head -n 1); and cat \$latest'"
        echo "alias sf-web='spiderfoot -l 127.0.0.1:5001'"
        echo "alias nuclei-safe='nuclei -templates \$HOME/nuclei-templates -severity low,medium'"
        echo ""
        echo "function osint-help"
        echo '    echo "PEN15 OSINT quick commands:"'
        echo '    echo "  sherlock USER --print-found"'
        echo '    echo "  maigret USER"'
        echo '    echo "  holehe email@example.com"'
        echo '    echo "  ghunt login; ghunt email email@example.com"'
        echo '    echo "  theHarvester -d example.com -b bing,duckduckgo,crtsh"'
        echo '    echo "  spiderfoot -l 127.0.0.1:5001"'
        echo '    echo "  sqlmap -u https://target.example/page?id=1 --batch"'
        echo '    echo "  subfinder -d example.com | httpx"'
        echo '    echo "  amass enum -passive -d example.com"'
        echo '    echo "  phoneinfoga scan -n +15551234567"'
        echo '    echo "Logs: $PEN15_LOG_DIR"'
        echo "end"
    end > "$conf"

    log_line "[OK] fish integration written to $conf"
end

function verify_tools
    set -l commands fish git curl python pipx nmap whois dig jq sherlock maigret holehe ghunt sqlmap theHarvester spiderfoot subfinder httpx nuclei amass gau waybackurls ffuf assetfinder phoneinfoga
    for cmd in $commands
        if command -q "$cmd"
            log_line "[FOUND] $cmd -> "(command -s "$cmd")
        else
            record_failure "$cmd missing from PATH"
        end
    end
end

function write_report
    begin
        echo "PEN15 Termux OSINT installer report"
        echo "Run ID: $RUN_ID"
        echo "Date: "(date)
        echo "Device: "(uname -a)
        echo "Termux prefix: $TERMUX_PREFIX"
        echo "Install root: $INSTALL_ROOT"
        echo "Log file: $LOG_FILE"
        echo ""
        echo "Failures:"
        if test (count $FAILURES) -eq 0
            echo "  none"
        else
            for failure in $FAILURES
                echo "  - $failure"
            end
        end
        echo ""
        echo "Warnings:"
        if test (count $WARNINGS) -eq 0
            echo "  none"
        else
            for warning in $WARNINGS
                echo "  - $warning"
            end
        end
        echo ""
        echo "Next steps:"
        echo "  1. Restart Termux or run: source ~/.config/fish/conf.d/pen15-osint.fish"
        echo "  2. Run: osint-help"
        echo "  3. If anything failed, send back this report and the log file above."
    end > "$REPORT_FILE"

    echo ""
    echo "============================================================"
    echo "Installer report: $REPORT_FILE"
    echo "Full log:         $LOG_FILE"
    echo "============================================================"
end

function main
    banner
    ensure_dirs
    check_termux
    install_base_packages
    install_python_osint
    install_go_recon
    install_phoneinfoga
    install_extra_repos
    write_fish_integration
    verify_tools
    write_report

    if test (count $FAILURES) -eq 0
        echo "Install completed without recorded failures."
    else
        echo "Install completed with "(count $FAILURES)" recorded issue(s)."
        echo "Paste this back for help:"
        echo "  $REPORT_FILE"
        echo "  $LOG_FILE"
    end
end

main $argv
