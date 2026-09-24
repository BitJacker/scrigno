#!/data/data/com.termux/files/usr/bin/bash
# Scrigno - download and install the Android app from Termux.
#
# Public repository:
#   curl -fsSL https://raw.githubusercontent.com/BitJacker/iniziare/HEAD/scripts/termux-install.sh | bash
#
# Private repository (log in once with the GitHub CLI):
#   pkg install -y gh && gh auth login
#   gh api -H "Accept: application/vnd.github.raw" repos/BitJacker/iniziare/contents/scripts/termux-install.sh | bash
#
# Options, as environment variables:
#   SCRIGNO_REPO   owner/name of the repository (default: BitJacker/iniziare)
#   SCRIGNO_TAG    release to install: "nightly" or a version like "v0.1.0"
#                  (default: the latest stable release, or "nightly" if there is none yet)
#   GITHUB_TOKEN   token with read access, for a private repository without the GitHub CLI
#   SCRIGNO_URL    a folder URL that contains Scrigno.apk and SHA256SUMS.txt, to install from
#                  somewhere else than GitHub (for example a copy on your own server)
#
# The APK is saved in ~/Scrigno.apk (and copied to Download/ if storage access was granted with
# termux-setup-storage), its SHA-256 checksum is verified, then the Android installer opens.
set -euo pipefail

REPO="${SCRIGNO_REPO:-BitJacker/iniziare}"
TAG="${SCRIGNO_TAG:-}"
APK="Scrigno.apk"
SUMS="SHA256SUMS.txt"
WORK="${TMPDIR:-$HOME}/scrigno-download"
OUT="$HOME/$APK"

say() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

if ! have curl; then
    say "Installing curl..."
    pkg install -y curl
fi

rm -rf "$WORK"
mkdir -p "$WORK"

# --- Download strategies -------------------------------------------------------------------

# 1. GitHub CLI, already logged in: works for public and private repositories.
download_with_gh() {
    local tag="$1"
    if [ -n "$tag" ]; then
        gh release download "$tag" -R "$REPO" -p "$APK" -p "$SUMS" -D "$WORK" --clobber
    else
        gh release download -R "$REPO" -p "$APK" -p "$SUMS" -D "$WORK" --clobber 2>/dev/null ||
            gh release download nightly -R "$REPO" -p "$APK" -p "$SUMS" -D "$WORK" --clobber
    fi
}

# 2. A token in GITHUB_TOKEN: GitHub API (private repositories).
api() {
    curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/$REPO/$1"
}

download_with_token() {
    local tag="$1" json id name
    have jq || pkg install -y jq
    if [ -n "$tag" ]; then
        json="$(api "releases/tags/$tag")"
    else
        json="$(api "releases/latest" 2>/dev/null || api "releases/tags/nightly")"
    fi
    for name in "$APK" "$SUMS"; do
        id="$(printf '%s' "$json" | jq -r --arg n "$name" '.assets[] | select(.name == $n) | .id')"
        [ -n "$id" ] || fail "$name not found in the release"
        curl -fL --retry 3 -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/octet-stream" \
            -o "$WORK/$name" "https://api.github.com/repos/$REPO/releases/assets/$id"
    done
}

# 3. Nothing: public download links.
download_public() {
    local tag="$1" base name
    if [ -n "$tag" ]; then
        base="https://github.com/$REPO/releases/download/$tag"
    else
        base="https://github.com/$REPO/releases/latest/download"
        if ! curl -fsL -r 0-0 -o /dev/null "$base/$APK"; then
            base="https://github.com/$REPO/releases/download/nightly"
        fi
    fi
    for name in "$APK" "$SUMS"; do
        curl -fL --retry 3 -o "$WORK/$name" "$base/$name" || fail "Cannot download $base/$name.
If the repository is private, log in first:  pkg install -y gh && gh auth login
(or set GITHUB_TOKEN), then run this script again."
    done
}

# 4. A mirror chosen by the user.
download_from_url() {
    local name
    for name in "$APK" "$SUMS"; do
        curl -fL --retry 3 -o "$WORK/$name" "${SCRIGNO_URL%/}/$name" || fail "Cannot download ${SCRIGNO_URL%/}/$name"
    done
}

if [ -n "${SCRIGNO_URL:-}" ]; then
    say "Downloading Scrigno from $SCRIGNO_URL ..."
    download_from_url
elif have gh && gh auth status >/dev/null 2>&1; then
    say "Downloading Scrigno from github.com/$REPO (GitHub CLI) ..."
    download_with_gh "$TAG"
elif [ -n "${GITHUB_TOKEN:-}" ]; then
    say "Downloading Scrigno from github.com/$REPO (token) ..."
    download_with_token "$TAG"
else
    say "Downloading Scrigno from github.com/$REPO ..."
    download_public "$TAG"
fi

# --- Checks ---------------------------------------------------------------------------------

[ -s "$WORK/$APK" ] || fail "The downloaded file is empty."
[ "$(head -c 2 "$WORK/$APK")" = "PK" ] || fail "The downloaded file is not an APK."
say "Checking the SHA-256 checksum..."
(cd "$WORK" && grep " $APK\$" "$SUMS" | sha256sum -c -) || fail "Checksum mismatch: do not install this file."

mv -f "$WORK/$APK" "$OUT"
rm -rf "$WORK"
say "Saved in $OUT"
if [ -d "$HOME/storage/downloads" ]; then
    cp -f "$OUT" "$HOME/storage/downloads/$APK"
    say "Also copied to the Download folder of the phone."
fi

# --- Install --------------------------------------------------------------------------------

say "Opening the Android installer..."
echo "   If Android asks, allow Termux to install unknown apps, then tap Install."
if have termux-open; then
    termux-open --content-type application/vnd.android.package-archive "$OUT" ||
        echo "Could not open the installer: open $APK from the Download folder with a file manager."
else
    echo "termux-open is missing (pkg install termux-tools): open $APK from a file manager."
fi
