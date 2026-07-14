#!/data/data/com.termux/files/usr/bin/fish
# Pen15 OSINT Toolkit Installer for Termux (ARM Samsung / Fish shell)
#
# Installs the main OSINT stack from GitHub + pip with self-healing retries.
# Most tools are NOT in pkg/apt — this script clones repos and wires up PATH.
#
# Usage (in Termux Fish):
#   curl -fsSL https://raw.githubusercontent.com/twoskoops707/Pen15/main/scripts/osint/bootstrap.sh | bash
#   # or, if repo is already cloned:
#   fish ~/Pen15/scripts/osint/install_osint_tools.fish
#   fish ~/Pen15/scripts/osint/install_osint_tools.fish --retry-failed
#   fish ~/Pen15/scripts/osint/install_osint_tools.fish --clean-first
#   fish ~/Pen15/scripts/osint/clean_osint_tools.fish --reset
#   fish ~/Pen15/scripts/osint/check_osint_tools.fish
#
# Logs & reports:
#   ~/.pen15/osint-install.log
#   ~/.pen15/osint-install-report.txt

set -l script_dir (dirname (status -f))
set -gx OSINT_SCRIPT_DIR $script_dir
source "$script_dir/lib_pen15_osint.fish"

set -l retry_failed 0
set -l skip_heavy 0
set -l clean_first 0
set -l no_cleanup 0
set -l assume_yes 0
for arg in $argv
    switch $arg
        case --retry-failed
            set retry_failed 1
        case --skip-heavy
            set skip_heavy 1
        case --clean-first
            set clean_first 1
        case --no-cleanup
            set no_cleanup 1
        case -y --yes
            set assume_yes 1
        case -h --help
            echo "Usage: fish install_osint_tools.fish [options]"
            echo "  --retry-failed  Only re-attempt tools listed in ~/.pen15/osint-failed.txt"
            echo "  --skip-heavy    Skip SpiderFoot, BBOT, GHunt (large / ARM compile issues)"
            echo "  --clean-first   Wipe previous OSINT install before starting (fixes broken state)"
            echo "  --no-cleanup    Skip post-install temp/cache cleanup"
            echo "  --yes / -y      Skip clean-first confirmation prompt"
            exit 0
    end
end

# ---------------------------------------------------------------------------
# Per-tool installers (each tries pip → git → fallback)
# ---------------------------------------------------------------------------

function install_sherlock
    log_msg INFO "=== Sherlock (username OSINT, 400+ sites) ==="
    if pip_install sherlock-project
        link_bin (python3 -c "import shutil; print(shutil.which('sherlock') or '')" 2>/dev/null) sherlock 2>/dev/null
        mark_ok sherlock
        return 0
    end
    clone_repo https://github.com/sherlock-project/sherlock "$OSINT_TOOLS_DIR/sherlock"
    and pip_install_reqs "$OSINT_TOOLS_DIR/sherlock/requirements.txt"
    and begin
        echo '#!/data/data/com.termux/files/usr/bin/fish' > "$PREFIX/bin/sherlock"
        echo 'python3 $HOME/osint-tools/sherlock/sherlock/sherlock.py $argv' >> "$PREFIX/bin/sherlock"
        chmod +x "$PREFIX/bin/sherlock"
        mark_ok sherlock
        return 0
    end
    mark_fail sherlock "pip and git install both failed"
    return 1
end

function install_maigret
    log_msg INFO "=== Maigret (advanced Sherlock fork, 3000+ sites) ==="
    if pip_install maigret
        mark_ok maigret
        return 0
    end
    clone_repo https://github.com/soxoj/maigret "$OSINT_TOOLS_DIR/maigret"
    and pip_install_reqs "$OSINT_TOOLS_DIR/maigret/requirements.txt"
    and begin
        ln -sf "$OSINT_TOOLS_DIR/maigret/maigret.py" "$PREFIX/bin/maigret"
        mark_ok maigret
        return 0
    end
    mark_fail maigret "install failed"
    return 1
end

function install_holehe
    log_msg INFO "=== Holehe (email → registered accounts, 120+ sites) ==="
    if pip_install holehe
        mark_ok holehe
        return 0
    end
    clone_repo https://github.com/megadose/holehe "$OSINT_TOOLS_DIR/holehe"
    and pip_install_reqs "$OSINT_TOOLS_DIR/holehe/requirements.txt"
    and mark_ok holehe
    or mark_fail holehe "install failed"
end

function install_theharvester
    log_msg INFO "=== theHarvester (emails, subdomains, hosts) ==="
    clone_repo https://github.com/laramies/theHarvester "$OSINT_TOOLS_DIR/theHarvester"
    set -l req "$OSINT_TOOLS_DIR/theHarvester/requirements/base.txt"
    if not test -f $req
        set req "$OSINT_TOOLS_DIR/theHarvester/requirements.txt"
    end
    pip_install_reqs $req
    or pip_install theHarvester

    # Wrapper — upstream moved to python module entry
    echo '#!/data/data/com.termux/files/usr/bin/bash' > "$PREFIX/bin/theHarvester"
    echo 'cd "$HOME/osint-tools/theHarvester" 2>/dev/null || cd "$HOME/theHarvester" 2>/dev/null' >> "$PREFIX/bin/theHarvester"
    echo 'if command -v theHarvester >/dev/null 2>&1; then exec theHarvester "$@"; fi' >> "$PREFIX/bin/theHarvester"
    echo 'exec python3 theHarvester.py "$@"' >> "$PREFIX/bin/theHarvester"
    chmod +x "$PREFIX/bin/theHarvester"

    if command -v theHarvester >/dev/null; or test -f "$OSINT_TOOLS_DIR/theHarvester/theHarvester.py"
        mark_ok theHarvester
    else
        mark_fail theHarvester "clone/requirements failed"
    end
end

function install_sublist3r
    log_msg INFO "=== Sublist3r (subdomain enumeration) ==="
    if pip_install sublist3r
        mark_ok sublist3r
        return 0
    end
    clone_repo https://github.com/aboul3la/Sublist3r "$OSINT_TOOLS_DIR/Sublist3r"
    and pip_install_reqs "$OSINT_TOOLS_DIR/Sublist3r/requirements.txt"
    and begin
        echo '#!/data/data/com.termux/files/usr/bin/bash' > "$PREFIX/bin/sublist3r"
        echo 'exec python3 $HOME/osint-tools/Sublist3r/sublist3r.py "$@"' >> "$PREFIX/bin/sublist3r"
        chmod +x "$PREFIX/bin/sublist3r"
        mark_ok sublist3r
        return 0
    end
    mark_fail sublist3r "install failed"
end

function install_spiderfoot
    log_msg INFO "=== SpiderFoot (200+ OSINT modules, web UI) ==="
    clone_repo https://github.com/smicallef/spiderfoot "$HOME/spiderfoot"

    set -l sf_req "$HOME/spiderfoot/requirements.txt"
    # M2Crypto often fails on ARM Termux — try workarounds
    ensure_pkg swig openssl openssl-tool
    pip_install_reqs $sf_req
    or begin
        log_msg WARN "SpiderFoot full requirements failed — trying without M2Crypto-heavy modules"
        grep -v -i m2crypto $sf_req > /tmp/sf-req-lite.txt 2>/dev/null
        pip_install_reqs /tmp/sf-req-lite.txt
    end

    echo '#!/data/data/com.termux/files/usr/bin/bash' > "$PREFIX/bin/spiderfoot"
    echo 'cd $HOME/spiderfoot && python3 sf.py "$@"' >> "$PREFIX/bin/spiderfoot"
    chmod +x "$PREFIX/bin/spiderfoot"
    echo '#!/data/data/com.termux/files/usr/bin/bash' > "$PREFIX/bin/sf"
    echo 'cd $HOME/spiderfoot && python3 sf.py "$@"' >> "$PREFIX/bin/sf"
    chmod +x "$PREFIX/bin/sf"

    if test -f "$HOME/spiderfoot/sf.py"
        if python3 -c "import cherrypy" 2>/dev/null
            mark_ok spiderfoot
        else
            mark_fail spiderfoot "cloned but cherrypy/M2Crypto deps missing — try: proot-distro install debian"
            log_msg INFO "Fallback: BBOT is installed as a lighter SpiderFoot alternative"
        end
    else
        mark_fail spiderfoot "git clone failed"
    end
end

function install_recon_ng
    log_msg INFO "=== Recon-ng (modular recon framework) ==="
    clone_repo https://github.com/lanmaster53/recon-ng "$HOME/recon-ng"
    set -l req "$HOME/recon-ng/requirements.txt"
    if not test -f $req
        set req "$HOME/recon-ng/REQUIREMENTS"
    end
    pip_install_reqs $req

    echo '#!/data/data/com.termux/files/usr/bin/bash' > "$PREFIX/bin/recon-ng"
    echo 'cd $HOME/recon-ng && python3 recon-ng "$@"' >> "$PREFIX/bin/recon-ng"
    chmod +x "$PREFIX/bin/recon-ng"

    if test -f "$HOME/recon-ng/recon-ng"
        mark_ok recon-ng
    else
        mark_fail recon-ng "clone failed"
    end
end

function install_sqlmap
    log_msg INFO "=== SQLMap (SQL injection / DB takeover) ==="
    if pip_install sqlmap
        mark_ok sqlmap
        return 0
    end
    clone_repo https://github.com/sqlmapproject/sqlmap "$OSINT_TOOLS_DIR/sqlmap"
    and begin
        ln -sf "$OSINT_TOOLS_DIR/sqlmap/sqlmap.py" "$PREFIX/bin/sqlmap"
        mark_ok sqlmap
        return 0
    end
    mark_fail sqlmap "install failed"
end

function install_photon
    log_msg INFO "=== Photon (fast web crawler for OSINT) ==="
    clone_repo https://github.com/s0md3v/Photon "$OSINT_TOOLS_DIR/Photon"
    pip_install_reqs "$OSINT_TOOLS_DIR/Photon/requirements.txt"
    echo '#!/data/data/com.termux/files/usr/bin/bash' > "$PREFIX/bin/photon"
    echo 'exec python3 $HOME/osint-tools/Photon/photon.py "$@"' >> "$PREFIX/bin/photon"
    chmod +x "$PREFIX/bin/photon"
    if test -f "$OSINT_TOOLS_DIR/Photon/photon.py"
        mark_ok photon
    else
        mark_fail photon "clone failed"
    end
end

function install_h8mail
    log_msg INFO "=== h8mail (email breach hunting) ==="
    if pip_install h8mail
        mark_ok h8mail
        return 0
    end
    clone_repo https://github.com/khast3x/h8mail "$OSINT_TOOLS_DIR/h8mail"
    and pip_install_reqs "$OSINT_TOOLS_DIR/h8mail/requirements.txt"
    and mark_ok h8mail
    or mark_fail h8mail "install failed"
end

function install_socialscan
    log_msg INFO "=== socialscan (username/email availability checker) ==="
    if pip_install socialscan
        mark_ok socialscan
        return 0
    end
    mark_fail socialscan "pip install failed"
end

function install_blackbird
    log_msg INFO "=== Blackbird (username OSINT, fast) ==="
    clone_repo https://github.com/p1ngul1n0/blackbird "$OSINT_TOOLS_DIR/blackbird"
    pip_install_reqs "$OSINT_TOOLS_DIR/blackbird/requirements.txt"
    echo '#!/data/data/com.termux/files/usr/bin/bash' > "$PREFIX/bin/blackbird"
    echo 'exec python3 $HOME/osint-tools/blackbird/blackbird.py "$@"' >> "$PREFIX/bin/blackbird"
    chmod +x "$PREFIX/bin/blackbird"
    if test -f "$OSINT_TOOLS_DIR/blackbird/blackbird.py"
        mark_ok blackbird
    else
        mark_fail blackbird "clone failed"
    end
end

function install_cloud_enum
    log_msg INFO "=== CloudEnum (AWS/Azure/GCP bucket finder) ==="
    clone_repo https://github.com/initstring/cloud_enum "$OSINT_TOOLS_DIR/cloud_enum"
    pip_install_reqs "$OSINT_TOOLS_DIR/cloud_enum/requirements.txt"
    echo '#!/data/data/com.termux/files/usr/bin/bash' > "$PREFIX/bin/cloud_enum"
    echo 'exec python3 $HOME/osint-tools/cloud_enum/cloud_enum.py "$@"' >> "$PREFIX/bin/cloud_enum"
    chmod +x "$PREFIX/bin/cloud_enum"
    if test -f "$OSINT_TOOLS_DIR/cloud_enum/cloud_enum.py"
        mark_ok cloud_enum
    else
        mark_fail cloud_enum "clone failed"
    end
end

function install_bbot
    log_msg INFO "=== BBOT (modern SpiderFoot alternative, recursive OSINT) ==="
    ensure_pkg pipx
    if pipx install bbot 2>/dev/null
        mark_ok bbot
        return 0
    end
    if pip_install bbot
        mark_ok bbot
        return 0
    end
    mark_fail bbot "needs full Linux — use: proot-distro install debian && pipx install bbot"
end

function install_phoneinfoga
    log_msg INFO "=== PhoneInfoga (phone number OSINT) ==="
    install_github_release_binary sundowndev/phoneinfoga Linux_arm64 phoneinfoga
end

function install_amass
    log_msg INFO "=== Amass (OWASP subdomain/asset enumeration) ==="
    install_github_release_binary owasp-amass/amass linux_arm64 amass
end

function install_nikto
    log_msg INFO "=== Nikto (web server scanner) ==="
    ensure_pkg perl
    clone_repo https://github.com/sullo/nikto "$OSINT_TOOLS_DIR/nikto"
    if test -f "$OSINT_TOOLS_DIR/nikto/program/nikto.pl"
        ln -sf "$OSINT_TOOLS_DIR/nikto/program/nikto.pl" "$PREFIX/bin/nikto"
        chmod +x "$OSINT_TOOLS_DIR/nikto/program/nikto.pl"
        mark_ok nikto
    else
        mark_fail nikto "clone failed"
    end
end

function install_shodan_cli
    log_msg INFO "=== Shodan CLI (requires API key) ==="
    if pip_install shodan
        mark_ok shodan
    else
        mark_fail shodan "pip install failed"
    end
end

function install_ghunt
    log_msg INFO "=== GHunt (Google account OSINT) ==="
    clone_repo https://github.com/mxrch/GHunt "$OSINT_TOOLS_DIR/GHunt"
    pip_install_reqs "$OSINT_TOOLS_DIR/GHunt/requirements.txt"
    echo '#!/data/data/com.termux/files/usr/bin/bash' > "$PREFIX/bin/ghunt"
    echo 'exec python3 $HOME/osint-tools/GHunt/ghunt.py "$@"' >> "$PREFIX/bin/ghunt"
    chmod +x "$PREFIX/bin/ghunt"
    if test -f "$OSINT_TOOLS_DIR/GHunt/ghunt.py"; or command -v ghunt >/dev/null
        mark_ok ghunt
    else
        mark_fail ghunt "clone/requirements failed (heavy deps)"
    end
end

function install_pkg_osint_base
    log_msg INFO "=== Termux pkg base (network + DNS + crawl) ==="
    ensure_pkg git curl wget jq rsync unzip p7zip \
        python python-pip \
        nmap whois dnsutils \
        perl ruby \
        fish termux-api

    for tool in nmap whois dig curl git python3 fish
        if command -v $tool >/dev/null
            mark_ok "pkg:$tool"
        else
            mark_fail "pkg:$tool" "missing after pkg install"
        end
    end
end

# ---------------------------------------------------------------------------
# Retry-failed mode: read ~/.pen15/osint-failed.txt and map to installers
# ---------------------------------------------------------------------------

function should_install
    set -l name $argv[1]
    if test $retry_failed -eq 0
        return 0
    end
    if not test -f $OSINT_FAILED_LIST
        log_msg WARN "No failed list found — running full install"
        return 0
    end
    grep -q "^$name|" $OSINT_FAILED_LIST
    return $status
end

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

function main
    echo ""
    echo "╔══════════════════════════════════════════════════╗"
    echo "║  Pen15 OSINT Toolkit Installer (Termux / ARM64)  ║"
    echo "║  Fish shell • GitHub clones • Self-healing       ║"
    echo "╚══════════════════════════════════════════════════╝"
    echo ""

    if test $retry_failed -eq 1
        log_msg INFO "Retry-failed mode — only re-attempting previously failed tools"
    end

    if test $clean_first -eq 1
        log_msg INFO "Clean-first mode — wiping previous OSINT install"
        if test $assume_yes -eq 0
            echo ""
            echo "This will remove all OSINT repos, pip packages, and wrappers."
            echo "API keys in ~/.pen15/ are kept."
            echo -n "Type YES to continue: "
            read -l answer
            if test "$answer" != "YES"
                echo "Aborted."
                exit 1
            end
        end
        # Clear stale failure list so we get a fresh run
        rm -f $OSINT_FAILED_LIST 2>/dev/null
        set -gx OSINT_OK_LIST
        set -gx OSINT_FAIL_LIST
        reset_osint_install 0
        echo ""
    end

    # Fresh log section
    echo "" >> $OSINT_LOG
    log_msg INFO "========== Install run started =========="

    fix_termux_basics
    fix_python_build_env
    install_pkg_osint_base

    # Username / social OSINT
    should_install sherlock; and install_sherlock
    should_install maigret; and install_maigret
    should_install blackbird; and install_blackbird
    should_install socialscan; and install_socialscan

    # Email OSINT
    should_install holehe; and install_holehe
    should_install theHarvester; and install_theharvester
    should_install h8mail; and install_h8mail

    # Domain / DNS / subdomain
    should_install sublist3r; and install_sublist3r
    should_install amass; and install_amass
    should_install photon; and install_photon
    should_install cloud_enum; and install_cloud_enum

    # Frameworks
    should_install recon-ng; and install_recon_ng
    if test $skip_heavy -eq 0
        should_install spiderfoot; and install_spiderfoot
        should_install bbot; and install_bbot
        should_install ghunt; and install_ghunt
    else
        log_msg INFO "Skipping heavy tools (--skip-heavy)"
    end

    # Phone / misc
    should_install phoneinfoga; and install_phoneinfoga
    should_install sqlmap; and install_sqlmap
    should_install nikto; and install_nikto
    should_install shodan; and install_shodan_cli

    # Pen15 integration dirs
    mkdir -p "$HOME/Pen15/scripts/osint"
    mkdir -p "$HOME/.pen15"
    echo "# Store API keys here (one per file):" > "$HOME/.pen15/README.txt"
    echo "#   hibp_key.txt    — Have I Been Pwned API key" >> "$HOME/.pen15/README.txt"
    echo "#   shodan_key.txt  — Shodan API key" >> "$HOME/.pen15/README.txt"
    echo "#   virustotal.txt  — VirusTotal API key" >> "$HOME/.pen15/README.txt"

    write_fish_aliases

    set -l fail_count (count $OSINT_FAIL_LIST)
    if test $no_cleanup -eq 0
        run_post_install_cleanup $fail_count
    else
        log_msg INFO "Skipping post-install cleanup (--no-cleanup)"
    end

    show_summary
end

main
