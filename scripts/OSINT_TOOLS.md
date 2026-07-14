# OSINT Toolkit for Termux (ARM Android) + Fish Shell

A self-contained installer and fish-shell integration that pulls the main
OSINT tools from GitHub, wires them up so they “just work” in your fish
prompt on a Samsung / other ARM Android phone running Termux, and either
auto-recovers common failures or hands you a copy-pasteable error report.

---

## Contents

- `scripts/install_osint_tools.sh` — the bash installer (run once)
- `scripts/osint.fish`             — fish shell integration (auto-loads every session)
- this file                        — tool list + install / troubleshooting reference

---

## Legal — please read

These tools are for **authorized OSINT and penetration testing only** — on
systems you own or have explicit written permission to assess. You are
solely responsible for how you use them. Unauthorized use is a criminal
offense in most jurisdictions.

---

## Requirements

| Item | Notes |
|------|-------|
| Termux (from **F-Droid**, not Play Store) | Play Store version is EOL and broken |
| ~3 GB free storage | Go toolchain + Rust + Python venvs + repos |
| A charge (or plugged in) | First run compiles Rust wheels; can take 30–60 min |
| Reasonable Wi-Fi | Installer retries with backoff, but repeated failures need a network |

Optional but nice:

- `pkg install termux-api` — lets tools raise Android notifications
- `pkg install openssh` — for `ssh`/`scp` to move loot off the phone

---

## Install

From your Termux prompt (bash or fish — both work to *invoke* the installer):

```bash
# 1. get the script (adjust the path if you cloned Pen15 elsewhere)
cd ~
git clone https://github.com/twoskoops707/Pen15.git   # or `git pull` if you already have it
cd Pen15

# 2. run the installer  (bash — this is not a fish script)
bash scripts/install_osint_tools.sh
```

Then wire fish up:

```bash
mkdir -p ~/.config/fish/conf.d
cp scripts/osint.fish ~/.config/fish/conf.d/osint.fish
exec fish            # reload
osint-help           # see everything you now have
osint-doctor         # verify every tool is on $PATH
```

That’s the whole install.

### Install only a subset

```bash
bash scripts/install_osint_tools.sh --list                    # show tool ids + categories
bash scripts/install_osint_tools.sh --only sherlock,sqlmap    # just these two
bash scripts/install_osint_tools.sh --category social         # everything in the social category
bash scripts/install_osint_tools.sh --skip spiderfoot,nuclei  # everything except these
```

Categories: `social`, `email`, `phone`, `domain`, `web`, `vuln`, `framework`,
`google`, `secrets`.

### Update or re-attempt failures

```bash
bash scripts/install_osint_tools.sh --update       # git pull every installed tool
osint-reinstall                                    # from fish — re-run only failed ids
bash scripts/install_osint_tools.sh --report       # show last install report
```

### Remove one tool

```bash
bash scripts/install_osint_tools.sh --uninstall sherlock
```

---

## What gets installed

Every tool below is cloned from GitHub (or installed via `pipx` / `go install`)
into `$HOME/osint-tools/` and has a launcher shim placed in `$PREFIX/bin/`
so it is on your `$PATH` in fish immediately.

### Username / social-media

| ID | Repo | What it does |
|----|------|--------------|
| `sherlock`         | `sherlock-project/sherlock`     | Hunt a username across 400+ social sites |
| `maigret`          | `soxoj/maigret` (via pipx)      | Modern Sherlock alternative — 2500+ sites, dossier output |
| `blackbird`        | `p1ngul1n0/blackbird`           | Username & email OSINT with clean CLI/JSON output |
| `social-analyzer`  | `qeeqbox/social-analyzer` (npm) | 1000+ sites, false-positive filtering, image analysis |

### Email / breach

| ID | Repo | What it does |
|----|------|--------------|
| `theharvester` | `laramies/theHarvester`     | Emails, subdomains, hosts, employee names from public sources |
| `holehe`       | `megadose/holehe` (pipx)    | Check if an email is used on 120+ sites (no login triggered) |
| `h8mail`       | `khast3x/h8mail` (pipx)     | Email OSINT + breach hunting (HIBP, Snusbase, Dehashed, …) |
| `mosint`       | `alpkeskin/mosint` (Go)     | Automated email OSINT tool — parallel checks |

### Phone number

| ID | Repo | What it does |
|----|------|--------------|
| `phoneinfoga` | `sundowndev/phoneinfoga` (Go) | Phone number OSINT — carrier, region, footprint scanners |
| `ignorant`    | `megadose/ignorant` (pipx)    | Check if a phone number is registered on sites (silent) |

### Domain / subdomain / DNS

| ID | Repo | What it does |
|----|------|--------------|
| `sublist3r`    | `aboul3la/Sublist3r`                    | Passive subdomain enum via search engines |
| `dnsrecon`     | `darkoperator/dnsrecon`                 | DNS enumeration (zone transfer, brute, reverse) |
| `amass`        | `owasp-amass/amass` (Go)                | OWASP Amass — attack-surface mapping |
| `subfinder`    | `projectdiscovery/subfinder` (Go)       | Fast passive subdomain discovery |
| `assetfinder`  | `tomnomnom/assetfinder` (Go)            | Find related domains and subdomains |

### Web / HTTP / crawl

| ID | Repo | What it does |
|----|------|--------------|
| `photon`      | `s0md3v/Photon`                       | Fast crawler for URLs, emails, endpoints, secrets |
| `pdhttpx`     | `projectdiscovery/httpx` (Go)         | Multi-purpose HTTP prober (shim renamed to `pdhttpx` to avoid clash with `httpie`) |
| `waybackurls` | `tomnomnom/waybackurls` (Go)          | Pull known URLs from the Wayback Machine |
| `gau`         | `lc/gau` (Go)                         | Fetch known URLs from AlienVault OTX / Wayback / URLScan / Common Crawl |
| `katana`      | `projectdiscovery/katana` (Go)        | Next-gen crawler / spider |

### Vulnerability / injection

| ID | Repo | What it does |
|----|------|--------------|
| `sqlmap` | `sqlmapproject/sqlmap`               | Automated SQL injection (what you called “SQL gate”) |
| `nuclei` | `projectdiscovery/nuclei` (Go)       | Template-based vulnerability scanner |
| `nikto`  | `sullo/nikto` (Perl)                 | Classic web server vulnerability scanner |

### Frameworks

| ID | Repo | What it does |
|----|------|--------------|
| `spiderfoot` | `smicallef/spiderfoot`      | 200+ module OSINT framework with a web UI (`osint-web` launches it) |
| `recon-ng`   | `lanmaster53/recon-ng`      | Modular reconnaissance framework — think MSF for OSINT |

### Google / search-engine OSINT

| ID | Repo | What it does |
|----|------|--------------|
| `ghunt` | `mxrch/GHunt` (pipx) | OSINT on Google accounts / IDs |

### Instagram / social scraping

| ID | Repo | What it does |
|----|------|--------------|
| `osintgram` | `Datalux/Osintgram`      | Instagram OSINT — followers, photos, hashtags (needs a burner account) |
| `toutatis`  | `megadose/toutatis` (pipx) | Extract obfuscated info from Instagram accounts |
| `snscrape`  | `JustAnotherArchivist/snscrape` (pipx) | Scrape Twitter / Reddit / Telegram / Mastodon without their APIs |

### Git / secrets

| ID | Repo | What it does |
|----|------|--------------|
| `trufflehog` | `trufflesecurity/trufflehog` (Go) | Find and verify leaked secrets in git repos |
| `gitleaks`   | `gitleaks/gitleaks` (Go)          | Detect secrets committed to git — fast, low-noise |

**Total: 31 tools.** All are cloned from GitHub (or installed from source via
Go / pipx) because — as you noted — most of these have been kicked out of
the Termux `pkg` and Debian `apt` repos.

---

## Fish shell — what you get

Everything below lives in `~/.config/fish/conf.d/osint.fish` and auto-loads.

### Functions

| Function | Purpose |
|----------|---------|
| `osint-help`      | Reference card (functions + abbreviations) |
| `osint-doctor`    | Show which tools resolve on `$PATH` and which don’t |
| `osint-update`    | Runs the installer with `--update` |
| `osint-report`    | Prints the last install report |
| `osint-logs [id]` | List / tail per-tool install logs |
| `osint-reinstall` | Re-runs the installer only on tools that failed last time |
| `osint-search  <user>`      | Sherlock + Maigret against one username, results merged |
| `osint-domain  <domain>`    | Sublist3r + Subfinder + Assetfinder + Amass, merged and deduped |
| `osint-email   <email>`     | Holehe + H8mail against one address |
| `osint-phone   <+E164>`     | PhoneInfoga + Ignorant against one number |
| `osint-github  <url or dir>` | TruffleHog secrets scan (verified-only) |
| `osint-web`                 | Launches SpiderFoot web UI on `http://127.0.0.1:5001` |

### Abbreviations (type + space to expand)

`sh` → `sherlock` &nbsp; · &nbsp; `mg` → `maigret` &nbsp; · &nbsp;
`bb` → `blackbird` &nbsp; · &nbsp; `th` → `theharvester` &nbsp; · &nbsp;
`sm` → `sqlmap` &nbsp; · &nbsp; `sf` → `spiderfoot` &nbsp; · &nbsp;
`rn` → `recon-ng` &nbsp; · &nbsp; `pi` → `phoneinfoga` &nbsp; · &nbsp;
`nm` → `nmap -sV -sC` &nbsp; · &nbsp; `nuc` → `nuclei` &nbsp; · &nbsp;
`wb` → `waybackurls` &nbsp; · &nbsp; `kt` → `katana` &nbsp; · &nbsp;
`ass` → `assetfinder --subs-only` &nbsp; · &nbsp; `sub` → `subfinder -silent -d`

---

## When something breaks

Every tool is installed independently and its own log lives in
`$HOME/osint-tools/logs/<tool-id>.log`. If a tool fails, the installer
records it and keeps going — the summary at the end lists every failure
plus a copy-pasteable block like:

```
────────── FAILURE REPORT: theharvester ──────────
tool:     theharvester
command:  pip install theHarvester requirements
logfile:  /data/data/com.termux/files/home/osint-tools/logs/theharvester.log
host:     aarch64 / Linux 6.12.94+
termux:   PREFIX=/data/data/com.termux/files/usr

----- last 60 log lines -----
… stderr from the failed step …

----- copy the block above and paste it into your AI chat to get a fix -----
```

Common Termux/ARM hiccups the installer already handles automatically:

- **`cryptography` / `bcrypt` needs rustc** — installer runs `pkg install rust`
  in the bootstrap phase and sets `CARGO_BUILD_TARGET=aarch64-linux-android`.
- **`lxml` fails to build** — installer runs `pkg install libxml2 libxslt` and
  sets `LDFLAGS`/`CFLAGS` toward `$PREFIX`.
- **`pillow` fails on JPEG** — installer runs `pkg install libjpeg-turbo zlib`.
- **Go tools blow up on cold `go install`** — each is retried with exponential
  backoff (5 s → 10 s → 20 s).
- **`git clone` interrupted mid-flight** — retried with the same backoff.
- **Package `X` doesn’t exist in this Termux mirror** — installer warns and
  continues rather than aborting the whole run.

Things the installer *cannot* auto-fix and will report back to you:

- Rate-limited GitHub (`403 rate limit exceeded`) — wait an hour or set a
  personal access token in `~/.netrc`.
- Out-of-storage or out-of-RAM (large Go builds like `nuclei` need ~1 GB free).
- Tools whose upstream repo layout changed after this script was written
  (e.g. `sherlock` moved `sherlock.py` between directory layouts — the shim
  covers both known layouts, but a third rename would need a manual bump).

If you get a failure block, paste it verbatim (including the tail of the log)
back into a chat with me and I’ll patch the installer.

---

## Uninstall the whole toolkit

```bash
# per-tool
bash scripts/install_osint_tools.sh --uninstall sherlock

# nuke everything (safe — only touches OSINT_ROOT and shim files)
rm -rf ~/osint-tools ~/.local/bin/{sherlock,maigret,blackbird,theharvester,\
sqlmap,spiderfoot,sfcli,recon-ng,phoneinfoga,dnsrecon,sublist3r,photon,\
mosint,holehe,h8mail,ignorant,ghunt,osintgram,toutatis,snscrape,pdhttpx,\
waybackurls,gau,katana,amass,subfinder,assetfinder,trufflehog,gitleaks,\
nuclei,nikto,blackbird,social-analyzer}
rm -f ~/.config/fish/conf.d/osint.fish
```

---

## FAQ

**Q. My login shell is fish — do I have to invoke the installer with `bash`?**
Yes. The installer is a bash script (Termux ships bash regardless of your
login shell). Fish loads the *usage* layer at `~/.config/fish/conf.d/osint.fish`.

**Q. Can I install this on a non-Termux Linux (Kali / Ubuntu)?**
The script hard-codes `$PREFIX=/data/data/com.termux/files/usr` because that
is Termux’s layout. It will refuse to run elsewhere. On Kali most of these
tools are already in the standard repos anyway (`apt install sherlock sqlmap
recon-ng theharvester …`).

**Q. Why is `httpx` renamed to `pdhttpx`?**
Because Python’s HTTP library is also named `httpx` and Termux ships `httpie`
which registers `http`. `pdhttpx` (“ProjectDiscovery httpx”) avoids the clash.

**Q. Where are results stored?**
The combo fish functions write into `$HOME/osint-tools/results/<target>/`.
Individual tools write wherever their `-o` flag points (default: current dir).

**Q. What about `twint`?**
Twitter killed the endpoints twint used and the project is abandoned. Use
`snscrape` (installed) instead.

**Q. Anything for the deep/dark web?**
The installer installs `tor` as a package. Point tools at
`--proxy socks5h://127.0.0.1:9050` after `tor &`. Deep-web crawlers
(OnionScan, TorBot) intentionally aren’t in the default set — enable them
manually if you know what you’re doing.

---

## Verify the install without touching the phone

Both scripts are static-checked in CI-friendly ways:

```bash
bash -n scripts/install_osint_tools.sh                     # bash syntax
shellcheck -S warning scripts/install_osint_tools.sh       # zero warnings
fish -n scripts/osint.fish                                 # fish syntax
```
