# ==============================================================================
# osint.fish — Fish shell integration for the OSINT toolkit installed by
#              scripts/install_osint_tools.sh
#
# Install:
#     mkdir -p ~/.config/fish/conf.d
#     cp scripts/osint.fish ~/.config/fish/conf.d/osint.fish
#     exec fish   # reload
#
# Then:
#     osint-help          # list functions & abbreviations
#     osint-doctor        # sanity-check the install
#     osint-update        # pull latest for every installed tool
#     osint-search <user> # quick Sherlock+Maigret dual username search
# ==============================================================================

# ---- PATH additions ---------------------------------------------------------
# Termux ships PREFIX=/data/data/com.termux/files/usr; guard for portability.

set -l __osint_prefix (test -n "$PREFIX"; and echo $PREFIX; or echo "/data/data/com.termux/files/usr")
set -l __osint_home   (test -n "$HOME";   and echo $HOME;   or echo "/data/data/com.termux/files/home")

# fish_add_path is idempotent (fish >= 3.2). Only add dirs that exist.
for p in \
        $__osint_prefix/bin \
        $__osint_home/.local/bin \
        $__osint_home/go/bin \
        $__osint_home/osint-tools/bin
    if test -d $p
        fish_add_path -g -p $p 2>/dev/null; or set -gx PATH $p $PATH
    end
end

# ---- Environment ------------------------------------------------------------

set -gx OSINT_ROOT   "$__osint_home/osint-tools"
set -gx OSINT_LOG    "$OSINT_ROOT/logs"
set -gx OSINT_REPORT "$OSINT_ROOT/install-report.txt"
set -gx GOPATH       "$__osint_home/go"
set -gx GOBIN        "$GOPATH/bin"

# ---- Helper: colored echo ---------------------------------------------------

function __osint_info;  set_color cyan;   echo "[i] $argv"; set_color normal; end
function __osint_ok;    set_color green;  echo "[+] $argv"; set_color normal; end
function __osint_warn;  set_color yellow; echo "[!] $argv" >&2; set_color normal; end
function __osint_err;   set_color red;    echo "[x] $argv" >&2; set_color normal; end

# ==============================================================================
# CORE COMMANDS
# ==============================================================================

function osint-help --description "List OSINT abbreviations and helper functions"
    set_color --bold; echo "OSINT toolkit — fish integration"; set_color normal
    echo
    set_color --bold; echo "Functions:"; set_color normal
    printf "  %-18s %s\n" osint-help    "this message"
    printf "  %-18s %s\n" osint-doctor  "check installed tools and paths"
    printf "  %-18s %s\n" osint-update  "git pull every installed tool"
    printf "  %-18s %s\n" osint-report  "show last install report"
    printf "  %-18s %s\n" osint-logs    "tail the install logs"
    printf "  %-18s %s\n" osint-reinstall "re-run the installer on failed tools"
    printf "  %-18s %s\n" osint-search  "run Sherlock + Maigret against a username"
    printf "  %-18s %s\n" osint-domain  "quick subdomain enum (sublist3r + subfinder + assetfinder + amass)"
    printf "  %-18s %s\n" osint-email   "holehe + h8mail against an email"
    printf "  %-18s %s\n" osint-phone   "phoneinfoga + ignorant against a phone number"
    printf "  %-18s %s\n" osint-github  "trufflehog secrets scan on a repo URL or path"
    printf "  %-18s %s\n" osint-web     "SpiderFoot web UI on http://127.0.0.1:5001"
    echo
    set_color --bold; echo "Abbreviations (type + space to expand):"; set_color normal
    printf "  %-10s -> %s\n" sh       "sherlock"
    printf "  %-10s -> %s\n" mg       "maigret"
    printf "  %-10s -> %s\n" bb       "blackbird"
    printf "  %-10s -> %s\n" th       "theharvester"
    printf "  %-10s -> %s\n" sm       "sqlmap"
    printf "  %-10s -> %s\n" sf       "spiderfoot"
    printf "  %-10s -> %s\n" rn       "recon-ng"
    printf "  %-10s -> %s\n" pi       "phoneinfoga"
    printf "  %-10s -> %s\n" nm       "nmap -sV -sC"
    printf "  %-10s -> %s\n" nuc      "nuclei"
    printf "  %-10s -> %s\n" wb       "waybackurls"
    printf "  %-10s -> %s\n" gau      "gau"
    printf "  %-10s -> %s\n" kt       "katana"
    printf "  %-10s -> %s\n" ass      "assetfinder --subs-only"
    printf "  %-10s -> %s\n" sub      "subfinder -silent -d"
    echo
    set_color --bold; echo "Reinstall the whole toolkit:"; set_color normal
    echo "  bash $__osint_home/Pen15/scripts/install_osint_tools.sh"
end

function osint-doctor --description "Show which OSINT tools are found on PATH"
    set -l tools sherlock maigret blackbird social-analyzer \
                 theharvester holehe h8mail mosint \
                 phoneinfoga ignorant \
                 sublist3r dnsrecon amass subfinder assetfinder \
                 photon pdhttpx waybackurls gau katana \
                 sqlmap nuclei nikto \
                 spiderfoot recon-ng ghunt \
                 osintgram toutatis snscrape \
                 trufflehog gitleaks

    printf "%-18s %-10s %s\n" TOOL STATUS PATH
    printf "%-18s %-10s %s\n" ──── ────── ────
    set -l missing 0
    for t in $tools
        set -l loc (command -v $t 2>/dev/null)
        if test -n "$loc"
            set_color green
            printf "%-18s %-10s %s\n" $t OK $loc
            set_color normal
        else
            set_color red
            printf "%-18s %-10s %s\n" $t MISSING "-"
            set_color normal
            set missing (math $missing + 1)
        end
    end
    echo
    if test $missing -gt 0
        __osint_warn "$missing tool(s) missing. Re-run:"
        echo "  bash $__osint_home/Pen15/scripts/install_osint_tools.sh"
    else
        __osint_ok "all tools present"
    end
end

function osint-report --description "Show last install report"
    if test -f "$OSINT_REPORT"
        cat "$OSINT_REPORT"
    else
        __osint_warn "no report at $OSINT_REPORT — run the installer first"
    end
end

function osint-logs --description "Tail install logs; pass a tool id to filter"
    if test (count $argv) -gt 0
        set -l f "$OSINT_LOG/$argv[1].log"
        if test -f "$f"
            tail -n 200 -f "$f"
        else
            __osint_err "no log for '$argv[1]' at $f"
            return 1
        end
    else
        ls -1t "$OSINT_LOG"/*.log 2>/dev/null | head -20
    end
end

function osint-update --description "Pull latest for every installed tool"
    set -l script "$__osint_home/Pen15/scripts/install_osint_tools.sh"
    if not test -f "$script"
        __osint_err "installer not found at $script"
        return 1
    end
    bash "$script" --update
end

function osint-reinstall --description "Re-attempt only tools that failed last time"
    set -l failed
    for f in $OSINT_ROOT/.state/*.fail
        if test -f "$f"
            set failed $failed (basename "$f" .fail)
        end
    end
    if test (count $failed) -eq 0
        __osint_ok "no failed tools to reinstall"
        return 0
    end
    set -l joined (string join , $failed)
    __osint_info "reinstalling: $joined"
    bash "$__osint_home/Pen15/scripts/install_osint_tools.sh" --only $joined
end

# ==============================================================================
# QUICK-USE COMBO FUNCTIONS
# ==============================================================================

function osint-search --description "Sherlock + Maigret against a username"
    if test (count $argv) -eq 0
        __osint_err "usage: osint-search <username>"
        return 1
    end
    set -l user $argv[1]
    set -l outdir "$OSINT_ROOT/results/$user"
    mkdir -p "$outdir"
    __osint_info "results dir: $outdir"

    if command -v sherlock >/dev/null 2>&1
        __osint_info "running sherlock..."
        sherlock --print-found --folderoutput "$outdir/sherlock" $user
    else
        __osint_warn "sherlock not installed"
    end

    if command -v maigret >/dev/null 2>&1
        __osint_info "running maigret..."
        maigret --folderoutput "$outdir/maigret" --html $user
    else
        __osint_warn "maigret not installed"
    end

    __osint_ok "done — see $outdir"
end

function osint-domain --description "Subdomain enum via sublist3r/subfinder/assetfinder/amass"
    if test (count $argv) -eq 0
        __osint_err "usage: osint-domain <domain>"
        return 1
    end
    set -l dom $argv[1]
    set -l out "$OSINT_ROOT/results/$dom"
    mkdir -p "$out"
    __osint_info "target: $dom  output: $out"

    for combo in \
            "sublist3r|sublist3r -d $dom -o $out/sublist3r.txt" \
            "subfinder|subfinder -silent -d $dom -o $out/subfinder.txt" \
            "assetfinder|assetfinder --subs-only $dom > $out/assetfinder.txt" \
            "amass|amass enum -passive -d $dom -o $out/amass.txt"
        set -l tool (string split "|" $combo)[1]
        set -l cmd  (string split -m 1 "|" $combo)[2]
        if command -v $tool >/dev/null 2>&1
            __osint_info "$tool"
            eval $cmd
        else
            __osint_warn "$tool not installed"
        end
    end

    if test -d "$out"
        cat "$out"/*.txt 2>/dev/null | sort -u > "$out/all-subdomains.txt"
        __osint_ok "merged unique list: $out/all-subdomains.txt ("(wc -l <"$out/all-subdomains.txt")" hosts)"
    end
end

function osint-email --description "holehe + h8mail against an email address"
    if test (count $argv) -eq 0
        __osint_err "usage: osint-email <email>"
        return 1
    end
    set -l email $argv[1]
    set -l out "$OSINT_ROOT/results/$email"
    mkdir -p "$out"

    if command -v holehe >/dev/null 2>&1
        __osint_info "holehe"
        holehe --only-used $email | tee "$out/holehe.txt"
    else
        __osint_warn "holehe not installed"
    end

    if command -v h8mail >/dev/null 2>&1
        __osint_info "h8mail"
        h8mail -t $email -o "$out/h8mail.csv"
    else
        __osint_warn "h8mail not installed"
    end
end

function osint-phone --description "phoneinfoga + ignorant against a phone number (E.164, e.g. +14155552671)"
    if test (count $argv) -eq 0
        __osint_err "usage: osint-phone <+E164>"
        return 1
    end
    set -l num $argv[1]
    set -l out "$OSINT_ROOT/results/phone-$num"
    mkdir -p "$out"

    if command -v phoneinfoga >/dev/null 2>&1
        __osint_info "phoneinfoga"
        phoneinfoga scan -n $num | tee "$out/phoneinfoga.txt"
    else
        __osint_warn "phoneinfoga not installed"
    end

    if command -v ignorant >/dev/null 2>&1
        # ignorant expects country_code + number split
        set -l cc (string sub -s 2 -l 1 $num)
        set -l rest (string sub -s 3 $num)
        __osint_info "ignorant"
        ignorant $cc $rest | tee "$out/ignorant.txt"
    else
        __osint_warn "ignorant not installed"
    end
end

function osint-github --description "trufflehog secrets scan on a github URL or local git dir"
    if test (count $argv) -eq 0
        __osint_err "usage: osint-github <repo-url-or-dir>"
        return 1
    end
    if not command -v trufflehog >/dev/null 2>&1
        __osint_err "trufflehog not installed"
        return 1
    end
    set -l target $argv[1]
    if string match -q 'http*' $target; or string match -q 'git@*' $target
        trufflehog git $target --only-verified
    else if test -d $target/.git
        trufflehog git file://$target --only-verified
    else
        trufflehog filesystem $target --only-verified
    end
end

function osint-web --description "Launch SpiderFoot web UI on 127.0.0.1:5001"
    if not command -v spiderfoot >/dev/null 2>&1
        __osint_err "spiderfoot not installed"
        return 1
    end
    __osint_info "SpiderFoot UI -> http://127.0.0.1:5001  (Ctrl-C to stop)"
    spiderfoot -l 127.0.0.1:5001
end

# ==============================================================================
# ABBREVIATIONS
# ==============================================================================

if status --is-interactive
    abbr -a -g sh   sherlock
    abbr -a -g mg   maigret
    abbr -a -g bb   blackbird
    abbr -a -g th   theharvester
    abbr -a -g sm   sqlmap
    abbr -a -g sf   spiderfoot
    abbr -a -g rn   recon-ng
    abbr -a -g pi   phoneinfoga
    abbr -a -g nm   'nmap -sV -sC'
    abbr -a -g nuc  nuclei
    abbr -a -g wb   waybackurls
    abbr -a -g kt   katana
    abbr -a -g ass  'assetfinder --subs-only'
    abbr -a -g sub  'subfinder -silent -d'
end
