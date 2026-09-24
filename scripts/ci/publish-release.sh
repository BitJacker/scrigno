#!/usr/bin/env bash
# Publishes files on a rolling pre-release (e.g. "nightly") that always follows the tip of the branch.
#
#   publish-release.sh <tag> <title> <notes> <file>...
#
# - Only the build of the newest commit publishes: an older run that finishes later does nothing
#   (GitHub also refuses tags on older commits whose workflow files differ from the branch).
# - The release is updated in place (tag moved, files replaced), never deleted first, so the
#   download links keep working even if something fails half way.
set -euo pipefail

tag="$1"; title="$2"; notes="$3"; shift 3
branch="${GITHUB_REF_NAME:?}"
sha="${GITHUB_SHA:?}"

head="$(git ls-remote origin "refs/heads/$branch" | cut -f1)"
if [ "$head" != "$sha" ]; then
    echo "The branch has moved on ($head): the newer build will publish \"$tag\"."
    exit 0
fi

if gh release view "$tag" >/dev/null 2>&1; then
    gh api -X PATCH "repos/$GITHUB_REPOSITORY/git/refs/tags/$tag" -f sha="$sha" -F force=true >/dev/null
    gh release upload "$tag" "$@" --clobber
    gh release edit "$tag" --title "$title" --notes "$notes" --prerelease
else
    gh release create "$tag" "$@" --prerelease --target "$sha" --title "$title" --notes "$notes"
fi
echo "Published \"$tag\" at ${sha::7}."
