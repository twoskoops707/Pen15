# Termux ARM OSINT installer

`scripts/install_termux_osint.fish` bootstraps a Samsung/ARM64 Termux environment for fish shell users. It installs core packages, clones GitHub-hosted tools that are not reliable in Termux package repos, adds fish helpers, and writes diagnostics under `~/.pen15/logs`.

## Run it on the phone

1. Install Termux from F-Droid, not the Play Store.
2. Open Termux and install fish if it is not already installed:

   ```sh
   pkg update -y
   pkg install -y fish git curl
   ```

3. Copy this repo or just this script onto the phone.
4. Run:

   ```sh
   fish scripts/install_termux_osint.fish
   ```

5. Restart Termux, or run:

   ```fish
   source ~/.config/fish/conf.d/pen15-osint.fish
   osint-help
   ```

## What it installs

### Termux packages

- `fish`, `git`, `curl`, `wget`, `jq`, `tar`, `unzip`, `proot`, `resolv-conf`
- `python`, `pipx`, build tools, Rust, OpenSSL/libffi/XML dependencies
- `golang`
- `nmap`, `whois`, `dnsutils`, `openssl`, `termux-api`

### Python / pipx tools

- Sherlock (`sherlock-project/sherlock`)
- Maigret
- Holehe
- GHunt
- waybackpy
- dnsrecon
- socialscan
- WhatsMyName-Python

### GitHub-source tools and data

- sqlmap (`sqlmapproject/sqlmap`)
- theHarvester (`laramies/theHarvester`, via `uv`)
- SpiderFoot (`smicallef/spiderfoot`, isolated virtualenv)
- PhoneInfoga (`sundowndev/phoneinfoga`, ARM binary installer)
- SecLists
- PayloadsAllTheThings
- OSINT-Framework

### Go-based modern recon tools

- ProjectDiscovery `subfinder`, `httpx`, and `nuclei`
- OWASP Amass
- `gau`, `waybackurls`, `ffuf`, `assetfinder`
- TruffleHog
- `nuclei-templates`

## Diagnostics and hiccups

The script retries failed commands, logs all output, and writes a final report:

```fish
ls -lt ~/.pen15/logs
cat ~/.pen15/logs/osint-install-report-*.txt
```

If something fails, send back both paths shown at the end of the run:

- `~/.pen15/logs/osint-install-report-<timestamp>.txt`
- `~/.pen15/logs/osint-install-<timestamp>.log`

Common Termux hiccups the script tries to avoid:

- Missing ARM binary support: Go tools are built from source.
- Python dependency conflicts: pipx and per-tool virtualenvs are used where possible.
- Missing PATH entries: fish config adds `~/.local/bin` and `~/go/bin`.
- PhoneInfoga on Android: installed into `~/.local/bin`; if it needs chroot, run it with `termux-chroot phoneinfoga ...`.

## Usage reminders

Use these tools only for your own accounts, assets, lab targets, or engagements where you have written permission.

Examples:

```fish
sherlock USER --print-found
maigret USER
holehe email@example.com
ghunt login
ghunt email email@example.com
theHarvester -d example.com -b bing,duckduckgo,crtsh
spiderfoot -l 127.0.0.1:5001
sqlmap -u "https://target.example/page?id=1" --batch
subfinder -d example.com | httpx
amass enum -passive -d example.com
phoneinfoga scan -n +15551234567
```
