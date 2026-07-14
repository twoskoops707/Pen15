# Pen15 OSINT installer helpers for Termux (Fish shell)
# Source from install_osint_tools.fish — do not run directly.

if not set -q PEN15_OSINT_LIB_LOADED
    set -gx PEN15_OSINT_LIB_LOADED 1
else
    return
end

set -gx PEN15_DIR "$HOME/.pen15"
set -gx OSINT_TOOLS_DIR "$HOME/osint-tools"
set -gx OSINT_LOG "$PEN15_DIR/osint-install.log"
set -gx OSINT_REPORT "$PEN15_DIR/osint-install-report.txt"
set -gx OSINT_FAILED_LIST "$PEN15_DIR/osint-failed.txt"

# Track install results for the final report.
set -gx OSINT_OK_LIST
set -gx OSINT_FAIL_LIST

# Known OSINT artifacts — used by clean/reset routines.
set -gx OSINT_BIN_WRAPPERS sherlock maigret holehe theHarvester sublist3r \
    spiderfoot sf recon-ng sqlmap photon h8mail blackbird cloud_enum \
    phoneinfoga amass nikto shodan ghunt bbot socialscan

set -gx OSINT_PIP_PACKAGES sherlock-project maigret holehe theHarvester theharvester \
    sublist3r h8mail socialscan sqlmap shodan bbot ghunt mitmproxy hashid

set -gx OSINT_REPO_DIRS \
    "$OSINT_TOOLS_DIR/sherlock" \
    "$OSINT_TOOLS_DIR/maigret" \
    "$OSINT_TOOLS_DIR/holehe" \
    "$OSINT_TOOLS_DIR/theHarvester" \
    "$OSINT_TOOLS_DIR/Sublist3r" \
    "$OSINT_TOOLS_DIR/sqlmap" \
    "$OSINT_TOOLS_DIR/Photon" \
    "$OSINT_TOOLS_DIR/h8mail" \
    "$OSINT_TOOLS_DIR/blackbird" \
    "$OSINT_TOOLS_DIR/cloud_enum" \
    "$OSINT_TOOLS_DIR/GHunt" \
    "$OSINT_TOOLS_DIR/nikto" \
    "$HOME/spiderfoot" \
    "$HOME/recon-ng" \
    "$HOME/theHarvester" \
    "$HOME/nikto" \
    "$HOME/sherlock" \
    "$HOME/maigret" \
    "$HOME/holehe" \
    "$HOME/Sublist3r" \
    "$HOME/sublist3r" \
    "$HOME/sqlmap" \
    "$HOME/blackbird" \
    "$HOME/cloud_enum" \
    "$HOME/GHunt" \
    "$HOME/Photon" \
    "$HOME/h8mail" \
    "$HOME/john-src" \
    "$HOME/hydra-src"

set -gx OSINT_LEGACY_GARBAGE \
    /tmp/sf-req-lite.txt \
    /tmp/hashcat.7z \
    /tmp/hashcat_extracted \
    "$HOME/.cache/pip" \
    "$HOME/.cache/maigret" \
    "$HOME/.cache/sherlock"

# NEVER delete these paths or anything inside them (user data / private config).
set -gx OSINT_PROTECTED_PREFIXES \
    "$HOME/.pen15" \
    "$HOME/.termux" \
    "$HOME/storage" \
    "$HOME/Pen15/recon" \
    "$HOME/Pen15/scans" \
    "$HOME/Pen15/captures" \
    "$HOME/Pen15/hashes" \
    "$HOME/Pen15/reports" \
    "$HOME/Pen15/dorks" \
    "$HOME/Pen15/payloads" \
    "$HOME/recon-ng/workspaces" \
    "$HOME/.spiderfoot"

# Only these files inside ~/.pen15 may ever be removed (install diagnostics only).
set -gx OSINT_PEN15_REMOVABLE_LOGS \
    "$PEN15_DIR/osint-install.log" \
    "$PEN15_DIR/osint-install-report.txt" \
    "$PEN15_DIR/osint-failed.txt"

function is_protected_path
    set -l target $argv[1]
    if test -z "$target"
        return 1
    end
    # Normalize: reject home itself or any protected prefix.
    if test "$target" = "$HOME"; or test "$target" = "/"
        return 0
    end
    for prefix in $OSINT_PROTECTED_PREFIXES
        if test "$target" = "$prefix"; or string match -q "$prefix/*" "$target"
            return 0
        end
    end
    return 1
end

function safe_rm_path
    set -l target $argv[1]
    set -l dry_run $argv[2]
    if test -z "$target"
        return 1
    end
    if is_protected_path $target
        log_msg WARN "SKIP protected (user data): $target"
        return 1
    end
    if not test -e $target
        return 1
    end
    if test "$dry_run" = 1
        echo "  [dry-run] would remove: $target"
    else
        rm -rf $target 2>/dev/null
        log_msg INFO "Removed: $target"
    end
    return 0
end

function safe_rm_install_logs
    set -l dry_run $argv[1]
    log_msg INFO "Clearing install diagnostic logs only (not API keys or personal files)..."
    for f in $OSINT_PEN15_REMOVABLE_LOGS
        if test -f $f
            if test "$dry_run" = 1
                echo "  [dry-run] would remove log: $f"
            else
                rm -f $f 2>/dev/null
                log_msg INFO "Removed log: $f"
            end
        end
    end
end

function cleanup_pycache_in_tool_dirs
    set -l dry_run $argv[1]
    log_msg INFO "Clearing __pycache__ inside OSINT tool dirs only..."
    for dir in $OSINT_REPO_DIRS $OSINT_TOOLS_DIR
        if test -d $dir; and not is_protected_path $dir
            find $dir -maxdepth 6 -type d -name '__pycache__' 2>/dev/null | while read -l cache
                if not is_protected_path $cache
                    safe_rm_path $cache $dry_run
                end
            end
        end
    end
end

function log_msg
    set -l level "INFO"
    if test (count $argv) -ge 2
        set level $argv[1]
        set -e argv[1]
    end
    set -l msg (string join " " $argv)
    set -l ts (date '+%Y-%m-%d %H:%M:%S')
    set -l line "[$ts] [$level] $msg"
    echo $line
    mkdir -p $PEN15_DIR
    echo $line >> $OSINT_LOG
end

function mark_ok
    set -gx OSINT_OK_LIST $OSINT_OK_LIST $argv[1]
    log_msg OK "Installed: $argv[1]"
end

function mark_fail
    set -l name $argv[1]
    set -l reason ""
    if test (count $argv) -ge 2
        set reason $argv[2]
    end
    set -gx OSINT_FAIL_LIST $OSINT_FAIL_LIST "$name|$reason"
    log_msg FAIL "Failed: $name — $reason"
    echo "$name|$reason" >> $OSINT_FAILED_LIST
end

function retry_cmd
    set -l attempts 3
    set -l delay 2
    for i in (seq 1 $attempts)
        $argv
        and return 0
        log_msg WARN "Attempt $i/$attempts failed: $argv[1]"
        sleep $delay
        set delay (math $delay '*' 2)
    end
    return 1
end

function pip_install
    set -l pip_flags --upgrade
    if pip3 install --help 2>/dev/null | grep -q break-system-packages
        set pip_flags $pip_flags --break-system-packages
    end
    retry_cmd pip3 install $pip_flags $argv
end

function pip_install_reqs
    set -l reqfile $argv[1]
    set -l pip_flags
    if pip3 install --help 2>/dev/null | grep -q break-system-packages
        set pip_flags --break-system-packages
    end
    if test -f $reqfile
        retry_cmd pip3 install $pip_flags -r $reqfile
        return $status
    end
    mark_fail (basename (dirname $reqfile)) "requirements file missing: $reqfile"
    return 1
end

function ensure_pkg
    for pkg in $argv
        if not pkg list-installed 2>/dev/null | grep -q "^$pkg/"
            log_msg INFO "Installing pkg: $pkg"
            retry_cmd pkg install -y $pkg
            or begin
                mark_fail "pkg:$pkg" "pkg install failed after retries"
                return 1
            end
        end
    end
    return 0
end

function ensure_dir
    mkdir -p $argv[1]
end

function link_bin
    set -l target $argv[1]
    set -l name $argv[2]
    if test -x $target
        ln -sf $target "$PREFIX/bin/$name"
        return 0
    end
    return 1
end

function clone_repo
    set -l url $argv[1]
    set -l dest $argv[2]
    if test -d $dest/.git
        log_msg INFO "Updating repo: $dest"
        git -C $dest pull --ff-only 2>/dev/null; or git -C $dest fetch --depth 1 origin 2>/dev/null
        return 0
    end
    ensure_dir (dirname $dest)
    retry_cmd git clone --depth 1 $url $dest
end

function install_github_release_binary
    set -l repo $argv[1]
    set -l asset_pattern $argv[2]
    set -l bin_name $argv[3]
    set -l install_path "$PREFIX/bin/$bin_name"

    if command -v $bin_name >/dev/null
        mark_ok $bin_name
        return 0
    end

    set -l api_url "https://api.github.com/repos/$repo/releases/latest"
    set -l tag (curl -fsSL $api_url | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": "\([^"]*\)".*/\1/')
    if test -z "$tag"
        mark_fail $bin_name "could not fetch GitHub release tag"
        return 1
    end

    set -l asset_url (curl -fsSL $api_url | grep browser_download_url | grep $asset_pattern | head -1 | sed 's/.*"browser_download_url": "\([^"]*\)".*/\1/')
    if test -z "$asset_url"
        mark_fail $bin_name "no release asset matching $asset_pattern"
        return 1
    end

    set -l tmpdir (mktemp -d)
    set -l archive "$tmpdir/archive"
    curl -fsSL -o $archive $asset_url
    or begin
        rm -rf $tmpdir
        mark_fail $bin_name "download failed"
        return 1
    end

    if string match -q '*.tar.gz' $asset_url
        tar -xzf $archive -C $tmpdir
    else if string match -q '*.zip' $asset_url
        ensure_pkg unzip
        unzip -q $archive -d $tmpdir
    end

    set -l binary (find $tmpdir -type f -name $bin_name 2>/dev/null | head -1)
    if test -z "$binary"
        set binary (find $tmpdir -type f -perm /111 2>/dev/null | head -1)
    end

    if test -n "$binary"
        cp $binary $install_path
        chmod +x $install_path
        rm -rf $tmpdir
        mark_ok $bin_name
        return 0
    end

    rm -rf $tmpdir
    mark_fail $bin_name "binary not found in release archive"
    return 1
end

function fix_python_build_env
    log_msg INFO "Preparing Python build environment..."
    ensure_pkg python python-pip clang make cmake binutils libffi openssl openssl-tool \
        swig pkg-config rust libjpeg-turbo libxml2 libxslt zlib

    pip_install pip setuptools wheel cython
    or log_msg WARN "pip bootstrap had issues — continuing"
end

function fix_termux_basics
    log_msg INFO "Running Termux baseline setup..."
    ensure_dir $PEN15_DIR
    ensure_dir $OSINT_TOOLS_DIR
    ensure_dir "$HOME/.config/fish/conf.d"

    if not test -f "$HOME/.termux/termux.properties"
        mkdir -p "$HOME/.termux"
    end
    if not grep -q 'allow-external-apps=true' "$HOME/.termux/termux.properties" 2>/dev/null
        echo 'allow-external-apps=true' >> "$HOME/.termux/termux.properties"
        log_msg INFO "Added allow-external-apps=true for Pen15 integration"
    end

    if not command -v termux-setup-storage >/dev/null
        log_msg WARN "termux-setup-storage not found — run manually if you need shared storage"
    else if not test -d "$HOME/storage"
        log_msg INFO "Grant storage: run 'termux-setup-storage' when prompted"
    end

    retry_cmd pkg update -y
    retry_cmd pkg upgrade -y
end

function write_fish_aliases
    set -l conf "$HOME/.config/fish/conf.d/pen15-osint.fish"
    set -l check_script "$OSINT_SCRIPT_DIR/check_osint_tools.fish"
    if test -z "$OSINT_SCRIPT_DIR"
        set check_script "$HOME/Pen15/scripts/osint/check_osint_tools.fish"
    end
    log_msg INFO "Writing Fish aliases to $conf"
    echo '# Pen15 OSINT tool shortcuts — auto-generated' > $conf
    echo 'set -gx OSINT_TOOLS_DIR "$HOME/osint-tools"' >> $conf
    echo 'set -gx PEN15_DIR "$HOME/.pen15"' >> $conf
    echo '' >> $conf
    echo 'function osint-check' >> $conf
    echo "    fish $check_script" >> $conf
    echo 'end' >> $conf
    echo '' >> $conf
    echo 'function osint-clean' >> $conf
    echo "    fish $OSINT_SCRIPT_DIR/clean_osint_tools.fish \$argv" >> $conf
    echo 'end' >> $conf
    echo '' >> $conf
    echo 'abbr -a -- sf "cd ~/spiderfoot && python3 sf.py"' >> $conf
    echo 'abbr -a -- harvest "theHarvester"' >> $conf
    echo 'abbr -a -- recon "recon-ng"' >> $conf
end

function write_install_report
    log_msg INFO "Writing install report to $OSINT_REPORT"
    echo "=== Pen15 OSINT Install Report ===" > $OSINT_REPORT
    echo "Date: "(date) >> $OSINT_REPORT
    echo "Device arch: "(uname -m) >> $OSINT_REPORT
    echo "Termux prefix: $PREFIX" >> $OSINT_REPORT
    echo "" >> $OSINT_REPORT
    echo "--- SUCCESS ("(count $OSINT_OK_LIST)") ---" >> $OSINT_REPORT
    for item in $OSINT_OK_LIST
        echo "  [OK] $item" >> $OSINT_REPORT
    end
    echo "" >> $OSINT_REPORT
    echo "--- FAILED ("(count $OSINT_FAIL_LIST)") ---" >> $OSINT_REPORT
    for item in $OSINT_FAIL_LIST
        echo "  [FAIL] $item" >> $OSINT_REPORT
    end
    echo "" >> $OSINT_REPORT
    echo "Full log: $OSINT_LOG" >> $OSINT_REPORT
    echo "" >> $OSINT_REPORT
    echo "To share failures with support / Cursor agent, run:" >> $OSINT_REPORT
    echo "  cat $OSINT_REPORT" >> $OSINT_REPORT
    echo "  cat $OSINT_LOG | tail -100" >> $OSINT_REPORT
end

function show_summary
    write_install_report
    echo ""
    echo "=============================================="
    echo "  Pen15 OSINT Install Complete"
    echo "=============================================="
    echo "  OK:   "(count $OSINT_OK_LIST)
    echo "  FAIL: "(count $OSINT_FAIL_LIST)
    echo ""
    echo "  Report: $OSINT_REPORT"
    echo "  Log:    $OSINT_LOG"
    echo ""
    if test (count $OSINT_FAIL_LIST) -gt 0
        echo "  Some tools failed. Re-run with:"
        echo "    fish install_osint_tools.fish --retry-failed"
        echo ""
        echo "  Or paste this to get help:"
        echo "    cat $OSINT_REPORT"
    end
    echo "  Verify: fish check_osint_tools.fish"
    echo "  Clean:  fish clean_osint_tools.fish --temp"
    echo "  Reset:  fish clean_osint_tools.fish --reset  (wipe broken install, keep API keys)"
    echo "=============================================="
end

# ---------------------------------------------------------------------------
# Cleanup helpers — temp files, broken clones, full reset
# ---------------------------------------------------------------------------

function cleanup_legacy_garbage
    set -l dry_run $argv[1]
    log_msg INFO "Removing legacy install garbage and build leftovers..."

    for p in $OSINT_LEGACY_GARBAGE
        safe_rm_path $p $dry_run
    end

    for p in $PREFIX/tmp/pip-*
        if test -e $p
            safe_rm_path $p $dry_run
        end
    end

    cleanup_pycache_in_tool_dirs $dry_run
end

function cleanup_temp_artifacts
    set -l dry_run $argv[1]
    log_msg INFO "Cleaning temp build artifacts and caches..."

    set -l temp_paths /tmp/sf-req-lite.txt "$HOME/.cache/pip"

    for p in $temp_paths
        safe_rm_path $p $dry_run
    end

    for p in $PREFIX/tmp/pip-*
        if test -e $p
            safe_rm_path $p $dry_run
        end
    end

    if command -v pip3 >/dev/null
        if test "$dry_run" = 1
            echo "  [dry-run] would run: pip3 cache purge"
        else
            pip3 cache purge 2>/dev/null
            log_msg INFO "Purged pip cache"
        end
    end

    if command -v pkg >/dev/null
        if test "$dry_run" = 1
            echo "  [dry-run] would run: pkg clean"
        else
            pkg clean -y 2>/dev/null
            log_msg INFO "Ran pkg clean"
        end
    end
end

function is_broken_clone
    set -l dir $argv[1]
    if not test -d $dir
        return 1
    end
    if not test -d "$dir/.git"
        return 0
    end
    # Empty or nearly empty clone
    set -l file_count (find $dir -mindepth 1 -maxdepth 2 2>/dev/null | wc -l | string trim)
    if test "$file_count" -lt 2
        return 0
    end
    return 1
end

function cleanup_broken_clones
    set -l dry_run $argv[1]
    log_msg INFO "Scanning for broken / partial git clones..."

    for dir in $OSINT_REPO_DIRS
        if is_broken_clone $dir
            safe_rm_path $dir $dry_run
        end
    end

    # Catch stray partial dirs under osint-tools
    if test -d $OSINT_TOOLS_DIR
        for dir in $OSINT_TOOLS_DIR/*
            if test -d $dir; and is_broken_clone $dir
                safe_rm_path $dir $dry_run
            end
        end
    end
end

function cleanup_osint_wrappers
    set -l dry_run $argv[1]
    log_msg INFO "Removing OSINT bin wrappers from $PREFIX/bin ..."

    for name in $OSINT_BIN_WRAPPERS
        set -l path "$PREFIX/bin/$name"
        if test -e $path; or test -L $path
            if test "$dry_run" = 1
                echo "  [dry-run] would remove: $path"
            else
                rm -f $path 2>/dev/null
                log_msg INFO "Removed wrapper: $path"
            end
        end
    end
end

function cleanup_osint_repos
    set -l dry_run $argv[1]
    log_msg INFO "Removing OSINT git clone directories (preserving user workspaces)..."

    for dir in $OSINT_REPO_DIRS
        if test -d $dir
            # recon-ng: keep investigation workspaces
            if test "$dir" = "$HOME/recon-ng"
                if test "$dry_run" = 1
                    echo "  [dry-run] would remove recon-ng tool files (keep workspaces/)"
                else
                    find "$dir" -mindepth 1 -maxdepth 1 ! -name workspaces -exec rm -rf {} + 2>/dev/null
                    log_msg INFO "Removed recon-ng tool files; kept workspaces/"
                end
                continue
            end
            safe_rm_path $dir $dry_run
        end
    end

    if test -d $OSINT_TOOLS_DIR
        safe_rm_path $OSINT_TOOLS_DIR $dry_run
    end
end

function cleanup_osint_pip
    set -l dry_run $argv[1]
    set -l pip_flags
    if pip3 uninstall --help 2>/dev/null | grep -q break-system-packages
        set pip_flags --break-system-packages
    end

    log_msg INFO "Uninstalling OSINT pip packages..."
    for pkg in $OSINT_PIP_PACKAGES
        if pip3 show $pkg >/dev/null 2>&1
            if test "$dry_run" = 1
                echo "  [dry-run] would uninstall pip: $pkg"
            else
                pip3 uninstall -y $pip_flags $pkg 2>/dev/null
                log_msg INFO "Uninstalled pip: $pkg"
            end
        end
    end
end

function cleanup_osint_logs
    set -l dry_run $argv[1]
    safe_rm_install_logs $dry_run
end

function reset_osint_install
    set -l dry_run $argv[1]
    log_msg INFO "=== Full OSINT reset (repos + wrappers + pip + garbage) ==="
    cleanup_osint_wrappers $dry_run
    cleanup_osint_repos $dry_run
    cleanup_osint_pip $dry_run
    cleanup_legacy_garbage $dry_run
    cleanup_broken_clones $dry_run
    cleanup_temp_artifacts $dry_run

    set -l conf "$HOME/.config/fish/conf.d/pen15-osint.fish"
    if test -f $conf
        if test "$dry_run" = 1
            echo "  [dry-run] would remove: $conf"
        else
            rm -f $conf 2>/dev/null
            log_msg INFO "Removed fish aliases: $conf"
        end
    end
end

function run_post_install_cleanup
    set -l had_failures $argv[1]
    log_msg INFO "=== Post-install cleanup ==="
    cleanup_temp_artifacts 0
    cleanup_broken_clones 0

    if test $had_failures -gt 0
        log_msg WARN "Install had failures — broken partial clones removed"
        echo ""
        echo "  Tip: if installs keep failing, wipe and retry fresh:"
        echo "    fish clean_osint_tools.fish --reset --yes"
        echo "    fish install_osint_tools.fish --skip-heavy"
    end
end
