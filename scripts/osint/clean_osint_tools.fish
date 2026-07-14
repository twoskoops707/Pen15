#!/data/data/com.termux/files/usr/bin/fish
# Pen15 OSINT Cleanup — remove temp junk, broken clones, or full reset
#
# Usage:
#   fish clean_osint_tools.fish              # temp/cache only (safe, default)
#   fish clean_osint_tools.fish --temp       # same as default
#   fish clean_osint_tools.fish --broken     # remove partial/failed git clones only
#   fish clean_osint_tools.fish --reset      # wipe all OSINT installs (keeps API keys)
#   fish clean_osint_tools.fish --full       # reset + clear install logs
#   fish clean_osint_tools.fish --dry-run    # show what would be deleted
#   fish clean_osint_tools.fish --reset --yes  # skip confirmation prompt
#
# API keys in ~/.pen15/*.txt are NEVER deleted unless --purge-keys is passed.

set -l script_dir (dirname (status -f))
set -gx OSINT_SCRIPT_DIR $script_dir
source "$script_dir/lib_pen15_osint.fish"

set -l mode temp
set -l dry_run 0
set -l assume_yes 0
set -l purge_keys 0

for arg in $argv
    switch $arg
        case --temp
            set mode temp
        case --broken
            set mode broken
        case --reset
            set mode reset
        case --full
            set mode full
        case --dry-run
            set dry_run 1
        case -y --yes
            set assume_yes 1
        case --purge-keys
            set purge_keys 1
        case -h --help
            echo "Usage: fish clean_osint_tools.fish [mode] [options]"
            echo ""
            echo "Modes:"
            echo "  --temp     Clear pip/pkg cache and build temp files (default, safe)"
            echo "  --broken   Remove incomplete / failed git clones only"
            echo "  --reset    Remove all OSINT repos, wrappers, and pip packages"
            echo "  --full     --reset plus install logs"
            echo ""
            echo "Options:"
            echo "  --dry-run     Show what would be deleted without deleting"
            echo "  --yes / -y    Skip confirmation prompts"
            echo "  --purge-keys  Also delete ~/.pen15 API key files (use with care)"
            exit 0
    end
end

function confirm_action
    set -l prompt $argv[1]
    if test $assume_yes -eq 1
        return 0
    end
    echo ""
    echo "$prompt"
    echo -n "Type YES to continue: "
    read -l answer
    test "$answer" = "YES"
end

echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║  Pen15 OSINT Cleanup (Termux / ARM64)            ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""
echo "Mode: $mode"(test $dry_run -eq 1; and echo " (dry-run)")
echo ""

switch $mode
    case temp
        cleanup_temp_artifacts $dry_run
        cleanup_broken_clones $dry_run
        echo ""
        echo "Done — temp files and broken clones cleared."
        echo "Your installed tools are untouched."
    case broken
        cleanup_broken_clones $dry_run
        echo ""
        echo "Done — broken/partial clones removed."
    case reset
        if test $dry_run -eq 0
            confirm_action "RESET removes ALL OSINT tool installs (repos, pip, wrappers)."
            or begin
                echo "Aborted."
                exit 1
            end
        end
        reset_osint_install $dry_run
        echo ""
        echo "Done — OSINT stack wiped. API keys in ~/.pen15/ kept."
        echo "Reinstall: fish install_osint_tools.fish --skip-heavy"
        echo "     or:   bash nuke_osint.sh --reinstall"
    case full
        if test $dry_run -eq 0
            confirm_action "FULL cleanup wipes OSINT installs AND install logs."
            or begin
                echo "Aborted."
                exit 1
            end
        end
        reset_osint_install $dry_run
        cleanup_osint_logs $dry_run
        if test $purge_keys -eq 1
            for keyfile in "$PEN15_DIR"/*.txt
                if test -f $keyfile
                    if test $dry_run -eq 1
                        echo "  [dry-run] would remove: $keyfile"
                    else
                        rm -f $keyfile
                        log_msg INFO "Removed key file: $keyfile"
                    end
                end
            end
        end
        echo ""
        echo "Done — full cleanup complete."
        echo "Reinstall: fish install_osint_tools.fish"
end

echo ""
echo "Log: $OSINT_LOG"
echo ""
