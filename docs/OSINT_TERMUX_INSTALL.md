# OSINT Toolkit Installer for Termux (fish shell)

`scripts/install_osint_termux.sh` installs the main OSINT tools — plus every
build dependency they need — into a Termux environment on an ARM Android phone,
and wires them into the **fish** shell so they are on `PATH` in every new
terminal.

Most of these tools were pulled from (or never shipped in) the Termux `pkg`/apt
repositories, so the script clones them from GitHub and creates launcher
wrappers on `PATH`.

## What it installs

| Category | Tools |
|----------|-------|
| Username OSINT | Sherlock, Maigret, socialscan |
| Email OSINT | theHarvester, holehe, h8mail |
| Domain / recon | SpiderFoot, Recon-ng, Sublist3r, dnsrecon, Photon, subfinder, amass, httpx, nuclei |
| Web / SQL injection | sqlmap (the "SQL gate") |
| Phone OSINT | PhoneInfoga |
| Metadata | exiftool |
| Core utilities | nmap, whois, dig, curl, wget, jq, git |

Build dependencies handled automatically: `python`, `pip`, `pipx`, `rust`,
`clang`, `make`, `golang`, and the native libraries (`libxml2`, `libxslt`,
`libjpeg-turbo`, `openssl`, `libffi`, …) plus the prebuilt Termux Python wheels
(`python-lxml`, `python-cryptography`, `python-numpy`, `python-pandas`,
`python-pillow`) that let the fragile packages install without compiling from
source.

## How to run it

Copy the script to your phone (or `git clone` this repo in Termux), then from
your fish prompt:

```fish
bash scripts/install_osint_termux.sh
```

A full run takes a while (the Go tools and any source builds are the slow part).
When it finishes, **start a new fish session** so the updated `PATH` is picked
up, then verify:

```fish
bash scripts/install_osint_termux.sh --doctor
```

### Options

| Option | Effect |
|--------|--------|
| *(none)* | Full install (default) |
| `--doctor` | Only report which tools are present/missing |
| `--no-go` | Skip the Go tools (subfinder/httpx/nuclei/amass/PhoneInfoga) — much faster |
| `--report` | Reprint the last error report |
| `--help` | Show usage |

## Self-healing and error reporting

The script never uses `set -e`, so one failing tool cannot abort the rest of
the toolkit. Each step is wrapped so that on failure it:

1. Tries known fixes — refresh the package index, add missing build deps, retry
   `pip` with `--break-system-packages` (PEP-668 lockout), fall back from
   `pipx` to `pip --user`, or re-clone a corrupt git checkout.
2. Retries with exponential backoff.
3. Records the outcome (installed / skipped / failed).

At the end it prints a summary and, if anything failed unrecoverably, writes a
report you can send back for a targeted fix:

```
~/.pen15/osint/last_report.txt
```

View it any time with `bash scripts/install_osint_termux.sh --report`, or share
it from the phone with `termux-share ~/.pen15/osint/last_report.txt`. Full logs
of every run are kept in `~/.pen15/osint/`.

## fish integration details

The script writes `~/.config/fish/conf.d/osint.fish`, which fish auto-loads on
start. It adds `$PREFIX/bin`, `~/.local/bin`, and `~/go/bin` to `fish_user_paths`
and sets `$OSINT_HOME` to `~/osint` (where the cloned repos live). Re-running the
installer rewrites this file, so it stays idempotent.

Cloned tools get executable wrappers in `$PREFIX/bin` (e.g. `sqlmap`,
`spiderfoot`, `recon-ng`, `theHarvester`), so you can run them by name from any
shell.
