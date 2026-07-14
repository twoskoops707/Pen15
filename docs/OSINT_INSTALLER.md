# Termux OSINT Toolkit Installer

`scripts/termux_osint_installer.sh` installs the main OSINT tools into a
Termux environment on an ARM64 Android phone (Samsung Galaxy, etc.) and wires
them up so they run from the **fish** shell. Most of these tools are not in the
`pkg`/`apt` repositories, so they are cloned from GitHub and given launcher
shims.

## Why a script (and why bash)

- Most modern OSINT tools were removed from, or never existed in, the Termux
  package repos. They live on GitHub and need to be cloned + built.
- ARM64 Termux builds frequently trip over the same handful of missing headers
  and toolchain problems (`lxml`, `cryptography`/Rust, OpenSSL, libffi, PEP 668,
  a half-configured `dpkg`, flaky mobile network). The script detects these and
  **fixes them automatically, then retries**.
- If a tool still can't be installed, the script writes a **copy-paste bug
  report** (and copies it to your clipboard when `termux-api` is present) so you
  can send it back to your assistant for a targeted fix.
- It is written in bash because bash is far more robust for this kind of error
  handling than fish. It then configures fish for you.

## Quick start

From a fish (or bash) prompt in Termux:

```bash
# Grab the script (or copy it onto the phone), then:
bash termux_osint_installer.sh          # install everything
exec fish                                # reload PATH so the tools are visible
```

The first run also installs a fish function, so afterwards you can just type:

```fish
osint-install            # re-run / resume
osint-install --check    # what's installed?
osint-install --update   # update everything
```

## Options

| Option | Effect |
| --- | --- |
| *(none)* | Install every known tool. |
| `--minimal` | Only the headline tools: `sherlock theharvester sqlmap spiderfoot sublist3r recon-ng`. |
| `--only a,b,c` | Install only these tool ids. |
| `--skip a,b` | Skip these tool ids. |
| `--no-go` | Skip the heavier Go-based tools. |
| `--update` | Update tools that are already installed. |
| `--check`, `-c` | Report which tools are installed and exit. |
| `--list` | List all known tools. |
| `--report` | Reprint the last install report. |
| `--yes`, `-y` | Non-interactive. |
| `--help`, `-h` | Help. |

Examples:

```bash
bash termux_osint_installer.sh --minimal
bash termux_osint_installer.sh --only sherlock,maigret,holehe
bash termux_osint_installer.sh --no-go
```

## What gets installed

Headline tools (the ones most people mean by "the main OSINT tools"):

| Tool | What it does | Method |
| --- | --- | --- |
| **Sherlock** | Hunt usernames across 400+ social networks | pipx |
| **theHarvester** | Emails, subdomains, hosts & names from public sources | pipx (git) |
| **sqlmap** | Automatic SQL injection & DB takeover | git clone |
| **SpiderFoot** | Automated OSINT recon engine (CLI + web UI) | git clone + venv |
| **Sublist3r** | Fast subdomain enumeration | pipx (git) |
| **Recon-ng** | Full web reconnaissance framework | git clone + venv |

People / account OSINT: **holehe**, **maigret**, **socialscan**, **h8mail**,
**toutatis**, **GHunt**.

Domain / infra OSINT: **dnsrecon**, **dnstwist**, **wafw00f**, **Photon**.

Go-based recon (skipped with `--no-go`): **subfinder**, **httpx**, **nuclei**,
**assetfinder**, **waybackurls**, **gau**, **amass**.

Run `bash termux_osint_installer.sh --list` for the live list.

## Where things go

| Path | Contents |
| --- | --- |
| `~/.osint/repos/<tool>` | GitHub clones |
| `~/.osint/venvs/<tool>` | Per-tool virtualenvs (isolated deps) |
| `~/.osint/pipx` | pipx-managed tools |
| `~/.local/bin`, `~/go/bin`, `$PREFIX/bin` | Launcher shims (added to fish PATH) |
| `~/.pen15/logs/osint-install-*.log` | Full install log |
| `~/.pen15/logs/osint-report.txt` | Latest per-tool report |
| `~/.pen15/logs/osint-bugreport-*.md` | Bug report to send back on failure |

## Self-healing details

On each step the script captures output and, on failure, matches it against
known ARM64/Termux problems before retrying once:

- **PEP 668 "externally-managed-environment"** → use isolated venvs / pipx, or
  `--break-system-packages` as a fallback.
- **`lxml` / libxml2** → `pkg install libxml2 libxslt`.
- **`cryptography` / Rust / maturin** → `pkg install rust`.
- **OpenSSL / libffi / Pillow-jpeg headers** → install the matching `-dev` libs.
- **Missing C compiler** → `pkg install clang make binutils pkg-config`.
- **Broken `dpkg`/`apt` lock** → `dpkg --configure -a` + `apt --fix-broken install`.
- **DNS / mirror / "Unable to locate package"** → refresh repos (and suggest
  `termux-change-repo`).
- **Flaky network** → exponential-backoff retries (4s → 8s → 16s → 32s).
- **No space left** → stop that tool cleanly with a clear message.

## When a tool still fails

The script prints a summary like:

```
Summary: 21 ok, 1 auto-fixed, 1 failed.
```

For any failure it writes `~/.pen15/logs/osint-bugreport-*.md` containing the
environment details and the last ~40 lines of the failing command. If
`termux-api` is installed it is also copied to your clipboard. Paste that file
back to your assistant to get a fix — it contains diagnostics only, no secrets.

## Notes

- **GHunt** needs Google account cookies to do anything useful — install only;
  authenticate separately per its docs.
- Some Go tools are large; use `--no-go` on low-storage devices.
- Authorized testing / research only.
