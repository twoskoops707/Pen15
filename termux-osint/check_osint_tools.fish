#!/data/data/com.termux/files/usr/bin/fish
# Termux OSINT Toolkit — verify installed tools
# Usage: fish check_osint_tools.fish

set -l script_dir (dirname (status -f))
set -gx OSINT_SCRIPT_DIR $script_dir
source "$script_dir/lib_osint.fish"

set -gx OSINT_CONFIG_DIR "$HOME/.termux-osint"

function check_cmd
    set -l name $argv[1]
    set -l hint $argv[2]
    if command -v $name >/dev/null
        echo "  [OK]   $name"
        return 0
    else if test -f "$PREFIX/bin/$name"
        echo "  [OK]   $name (linked)"
        return 0
    end
    echo "  [FAIL] $name — $hint"
    return 1
end

function check_path
    set -l label $argv[1]
    set -l path $argv[2]
    if test -f $path; or test -d $path
        echo "  [OK]   $label ($path)"
        return 0
    end
    echo "  [FAIL] $label — missing at $path"
    return 1
end

set -l ok 0
set -l fail 0

echo ""
echo "=== Termux OSINT Tool Check ==="
echo "Arch: "(uname -m)" | Prefix: $PREFIX"
echo ""

echo "--- Username / Social ---"
check_cmd sherlock "pip install sherlock-project"; or set fail (math $fail + 1)
check_cmd maigret "pip install maigret"; or set fail (math $fail + 1)
check_cmd blackbird "git clone blackbird"; or set fail (math $fail + 1)
check_cmd socialscan "pip install socialscan"; or set fail (math $fail + 1)

echo ""
echo "--- Email ---"
check_cmd holehe "pip install holehe"; or set fail (math $fail + 1)
check_cmd theHarvester "run install_osint_tools.fish"; or set fail (math $fail + 1)
check_cmd h8mail "pip install h8mail"; or set fail (math $fail + 1)

echo ""
echo "--- Domain / DNS ---"
check_cmd sublist3r "pip install sublist3r"; or set fail (math $fail + 1)
check_cmd amass "GitHub release binary"; or set fail (math $fail + 1)
check_cmd photon "git clone Photon"; or set fail (math $fail + 1)
check_cmd cloud_enum "git clone cloud_enum"; or set fail (math $fail + 1)
check_cmd whois "pkg install whois"; or set fail (math $fail + 1)
check_cmd dig "pkg install dnsutils"; or set fail (math $fail + 1)
check_cmd nmap "pkg install nmap"; or set fail (math $fail + 1)

echo ""
echo "--- Frameworks ---"
check_cmd recon-ng "git clone recon-ng"; or set fail (math $fail + 1)
check_path spiderfoot "$HOME/spiderfoot/sf.py"; or set fail (math $fail + 1)
check_cmd sf "SpiderFoot wrapper"; or set fail (math $fail + 1)
check_cmd bbot "pipx install bbot (may need proot-distro)"; or set fail (math $fail + 1)

echo ""
echo "--- Phone / Web / SQL ---"
check_cmd phoneinfoga "GitHub release ARM64"; or set fail (math $fail + 1)
check_cmd sqlmap "pip install sqlmap"; or set fail (math $fail + 1)
check_cmd nikto "git clone nikto"; or set fail (math $fail + 1)
check_cmd shodan "pip install shodan"; or set fail (math $fail + 1)
check_cmd ghunt "git clone GHunt"; or set fail (math $fail + 1)

echo ""
echo "--- Config ---"
check_path termux-osint-config "$OSINT_CONFIG_DIR"; or set fail (math $fail + 1)

echo ""
set ok (math 22 - $fail)
echo "Result: $ok OK, $fail missing/warn"
echo ""
if test $fail -gt 0
    echo "Fix: cd ~/termux-osint && bash nuke_osint.sh --yes --reinstall"
    echo "Log: cat $OSINT_CONFIG_DIR/osint-install.log"
end
echo ""

exit $fail
