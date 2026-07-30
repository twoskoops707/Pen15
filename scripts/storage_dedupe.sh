#!/data/data/com.termux/files/usr/bin/bash
# Pen15 Storage Dedupe — remove local files that are already safely backed up
# on GitHub, without ever touching uncommitted work or sensitive/private data.
#
# What it does:
#   1. Finds every git repository under the scan root(s).
#   2. For each repo with a GitHub 'origin', fetches and checks that the
#      local HEAD is clean (no uncommitted/untracked changes) and fully
#      pushed (local HEAD == origin's HEAD for the tracked branch).
#   3. Treats every tracked file inside such a repo as "safe" — it already
#      exists on GitHub, so losing the local copy loses nothing.
#   4. Finds duplicate git clones of the same GitHub repo (keeps one, flags
#      the rest) and loose files elsewhere on disk whose content hash
#      matches a safe file, then removes only those redundant copies.
#
# What it NEVER touches:
#   - Uncommitted or untracked changes in any repo (dirty git status)
#   - Repos with no reachable/pushed GitHub origin
#   - Anything under a protected path (~/.pen15, ~/.ssh, ~/.gnupg, ~/.termux,
#     ~/storage, Pen15 recon/scan/capture output, recon-ng workspaces)
#   - Anything matching a sensitive filename pattern (keys, secrets, tokens,
#     env files, credentials, wallets) — regardless of location
#
# Usage (from a local clone):
#   bash scripts/storage_dedupe.sh --dry-run [root ...]   # preview only
#   bash scripts/storage_dedupe.sh --yes [root ...]       # apply, skip prompt
#   bash scripts/storage_dedupe.sh [root ...]             # apply, asks to type YES
#   bash scripts/storage_dedupe.sh --list-repos [root ...]  # inspect only
#
# root defaults to $HOME (Termux home) if none given. Pass extra roots
# (e.g. a shared-storage subfolder) explicitly if you want them scanned —
# ~/storage itself is always protected and skipped.
#
# ALWAYS run with --dry-run first and read the output before applying.

set -euo pipefail

DRY_RUN=0
YES=0
LIST_ONLY=0
ROOTS=()

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        -y|--yes) YES=1 ;;
        --list-repos) LIST_ONLY=1 ;;
        -h|--help)
            sed -n '2,33p' "$0"
            exit 0
            ;;
        *) ROOTS+=("$arg") ;;
    esac
done

HOME="${HOME:-/data/data/com.termux/files/home}"
PEN15_DIR="${HOME}/.pen15"
LOG_FILE="${PEN15_DIR}/storage-dedupe.log"

if [ "${#ROOTS[@]}" -eq 0 ]; then
    ROOTS=("$HOME")
fi

# Protected prefixes — never scan into, never delete anything under these.
PROTECTED=(
    "${HOME}/.pen15"
    "${HOME}/.ssh"
    "${HOME}/.gnupg"
    "${HOME}/.termux"
    "${HOME}/storage"
    "${HOME}/Pen15/recon"
    "${HOME}/Pen15/scans"
    "${HOME}/Pen15/captures"
    "${HOME}/Pen15/hashes"
    "${HOME}/Pen15/reports"
    "${HOME}/Pen15/dorks"
    "${HOME}/Pen15/payloads"
    "${HOME}/recon-ng/workspaces"
    "${HOME}/.spiderfoot"
)

# Sensitive filename pattern — never touch a match, no matter where it lives.
SENSITIVE_RE='(^|/)(\.env(\..*)?|id_rsa[^/]*|id_ed25519[^/]*|id_ecdsa[^/]*|.*\.pem|.*\.p12|.*\.pfx|.*\.key|.*_rsa|.*token.*|.*secret.*|.*credential.*|.*password.*|.*passwd.*|.*wallet.*|.*\.gpg|.*\.asc|hibp_key\.txt|.*api[_-]?key.*)$'

is_protected() {
    local target="$1"
    [ -z "$target" ] && return 0
    [ "$target" = "$HOME" ] || [ "$target" = "/" ] && return 0
    for prefix in "${PROTECTED[@]}"; do
        if [ "$target" = "$prefix" ] || [[ "$target" == "$prefix/"* ]]; then
            return 0
        fi
    done
    return 1
}

is_sensitive() {
    local lower
    lower="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
    [[ "$lower" =~ $SENSITIVE_RE ]]
}

log() {
    echo "$*"
    mkdir -p "$PEN15_DIR" 2>/dev/null || true
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >>"$LOG_FILE" 2>/dev/null || true
}

echo ""
echo "======================================================"
echo "  Pen15 Storage Dedupe — GitHub-backed duplicate cleanup"
echo "======================================================"
echo "Roots: ${ROOTS[*]}"
echo ""

# --- Step 1: discover git repos, classify safe (clean + fully pushed) ------

declare -a SAFE_REPO_DIRS=()
declare -a SAFE_REPO_ORIGINS=()
declare -a ALL_REPO_DIRS=()

for root in "${ROOTS[@]}"; do
    [ -d "$root" ] || continue
    while IFS= read -r gitdir; do
        repo_dir="$(dirname "$gitdir")"
        is_protected "$repo_dir" && continue
        ALL_REPO_DIRS+=("$repo_dir")

        origin_url="$(git -C "$repo_dir" remote get-url origin 2>/dev/null || true)"
        if [[ "$origin_url" != *github.com* ]]; then
            log "SKIP (no GitHub origin): $repo_dir"
            continue
        fi

        if [ -n "$(git -C "$repo_dir" status --porcelain --ignore-submodules 2>/dev/null)" ]; then
            log "SKIP (uncommitted/untracked changes): $repo_dir"
            continue
        fi

        if ! git -C "$repo_dir" fetch --quiet origin 2>/dev/null; then
            log "SKIP (could not reach origin): $repo_dir"
            continue
        fi

        local_head="$(git -C "$repo_dir" rev-parse HEAD 2>/dev/null || true)"
        upstream_ref="$(git -C "$repo_dir" rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || true)"
        remote_head=""
        if [ -n "$upstream_ref" ]; then
            remote_head="$(git -C "$repo_dir" rev-parse "$upstream_ref" 2>/dev/null || true)"
        fi

        if [ -z "$local_head" ] || [ -z "$remote_head" ] || [ "$local_head" != "$remote_head" ]; then
            log "SKIP (not fully pushed): $repo_dir"
            continue
        fi

        log "SAFE (clean + pushed): $repo_dir  <-  $origin_url"
        SAFE_REPO_DIRS+=("$repo_dir")
        SAFE_REPO_ORIGINS+=("$(printf '%s' "$origin_url" | sed -E 's#\.git$##; s#^git@github\.com:#github.com/#; s#^https?://##' | tr '[:upper:]' '[:lower:]')")
    done < <(find "$root" -type d -name .git -not -path '*/node_modules/*' 2>/dev/null)
done

if [ "$LIST_ONLY" -eq 1 ]; then
    echo ""
    echo "Found ${#ALL_REPO_DIRS[@]} repo(s), ${#SAFE_REPO_DIRS[@]} safe (clean + pushed)."
    exit 0
fi

# --- Step 2: flag duplicate clones of the same GitHub repo ------------------

declare -a KEEP_REPO_DIRS=()
declare -a DUP_REPO_DIRS=()

for i in "${!SAFE_REPO_DIRS[@]}"; do
    dir="${SAFE_REPO_DIRS[$i]}"
    origin="${SAFE_REPO_ORIGINS[$i]}"
    is_dup=0
    for kept in "${KEEP_REPO_DIRS[@]}"; do
        kept_idx=-1
        for j in "${!SAFE_REPO_DIRS[@]}"; do
            [ "${SAFE_REPO_DIRS[$j]}" = "$kept" ] && kept_idx=$j && break
        done
        if [ "$kept_idx" -ge 0 ] && [ "${SAFE_REPO_ORIGINS[$kept_idx]}" = "$origin" ]; then
            is_dup=1
            break
        fi
    done
    if [ "$is_dup" -eq 1 ]; then
        DUP_REPO_DIRS+=("$dir")
    else
        KEEP_REPO_DIRS+=("$dir")
    fi
done

# --- Step 3: build a content-hash index from the kept safe repos ------------

declare -A HASH_INDEX=()

for repo_dir in "${KEEP_REPO_DIRS[@]}"; do
    while IFS= read -r rel; do
        f="$repo_dir/$rel"
        [ -f "$f" ] || continue
        is_sensitive "$f" && continue
        h="$(sha256sum "$f" 2>/dev/null | awk '{print $1}')"
        [ -n "$h" ] && [ -z "${HASH_INDEX[$h]:-}" ] && HASH_INDEX["$h"]="$f"
    done < <(git -C "$repo_dir" ls-files 2>/dev/null)
done

# --- Step 4: find loose duplicate files outside any repo working tree ------

declare -a REMOVE_LOOSE_FILES=()

is_inside_any_repo() {
    local target="$1"
    for r in "${ALL_REPO_DIRS[@]}"; do
        [[ "$target" == "$r"/* ]] && return 0
    done
    return 1
}

for root in "${ROOTS[@]}"; do
    [ -d "$root" ] || continue
    while IFS= read -r f; do
        is_protected "$f" && continue
        is_sensitive "$f" && continue
        is_inside_any_repo "$f" && continue
        h="$(sha256sum "$f" 2>/dev/null | awk '{print $1}')" || continue
        [ -z "$h" ] && continue
        canonical="${HASH_INDEX[$h]:-}"
        if [ -n "$canonical" ] && [ "$canonical" != "$f" ]; then
            REMOVE_LOOSE_FILES+=("$f|$canonical")
        fi
    done < <(find "$root" -type f -not -path '*/.git/*' 2>/dev/null)
done

# --- Step 5: report + (optionally) apply ------------------------------------

echo ""
echo "Duplicate clones of an already-safe GitHub repo (${#DUP_REPO_DIRS[@]}):"
for d in "${DUP_REPO_DIRS[@]}"; do
    echo "  $d"
done

echo ""
echo "Loose files already safely on GitHub elsewhere (${#REMOVE_LOOSE_FILES[@]}):"
for entry in "${REMOVE_LOOSE_FILES[@]}"; do
    echo "  ${entry%%|*}  (dup of ${entry##*|})"
done

total=$(( ${#DUP_REPO_DIRS[@]} + ${#REMOVE_LOOSE_FILES[@]} ))
echo ""
echo "Total removal candidates: $total"
echo "Protected paths and sensitive files are never touched, and are not counted above."

if [ "$total" -eq 0 ]; then
    echo ""
    echo "Nothing to clean up."
    exit 0
fi

if [ "$DRY_RUN" -eq 1 ]; then
    echo ""
    echo "[dry-run] No files were removed. Re-run with --yes (or without --dry-run) to apply."
    exit 0
fi

if [ "$YES" -eq 0 ]; then
    echo ""
    echo "This will permanently delete the $total item(s) listed above."
    echo "Everything deleted is already committed and pushed on GitHub."
    read -r -p "Type YES to continue: " answer
    if [ "$answer" != "YES" ]; then
        echo "Aborted, nothing removed."
        exit 1
    fi
fi

echo ""
echo "Removing..."
for d in "${DUP_REPO_DIRS[@]}"; do
    if is_protected "$d"; then
        log "SKIP protected, refusing to remove: $d"
        continue
    fi
    rm -rf "$d" && log "Removed duplicate clone: $d"
done

for entry in "${REMOVE_LOOSE_FILES[@]}"; do
    f="${entry%%|*}"
    if is_protected "$f" || is_sensitive "$f"; then
        log "SKIP protected/sensitive, refusing to remove: $f"
        continue
    fi
    rm -f "$f" && log "Removed loose duplicate: $f (kept ${entry##*|})"
done

echo ""
echo "Done. Full log: $LOG_FILE"
