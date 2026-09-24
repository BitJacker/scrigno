#!/usr/bin/env bash
# Runs the protocol contract tests (core/src/test/.../RemoteStorageContract.kt) against the real
# servers started by start-test-servers.sh: OpenSSH, vsftpd (FTP and FTPS), Samba, Apache WebDAV
# (HTTP and HTTPS). Fails if any test fails or is skipped.
#
#   GRADLE      Gradle command (default: ./gradlew)
#   CORE_TEST   test task of the core module (default: :core:test)
set -euo pipefail

GRADLE="${GRADLE:-./gradlew}"
CORE_TEST="${CORE_TEST:-:core:test}"
USER_NAME="${SCRIGNO_TEST_USER:-scrigno}"
PASSWORD="${SCRIGNO_TEST_PASSWORD:-Scr1gno-test-pw}"
RESULTS_DIR="${RESULTS_DIR:-core/build/test-results/test}"
HOST="${SCRIGNO_TEST_HOST:-127.0.0.1}"

# protocol port share basePath allowSelfSigned
SERVERS=(
    "SFTP    22   -      /srv/scrigno false"
    "FTP     21   -      /srv/scrigno false"
    "FTPS    21   -      /srv/scrigno true"
    "SMB     445  photos -            false"
    "WEBDAV  8080 -      /dav         false"
    "WEBDAVS 8443 -      /dav         true"
)

failed=()
for server in "${SERVERS[@]}"; do
    read -r protocol port share base selfsigned <<<"$server"
    [ "$share" = "-" ] && share=""
    [ "$base" = "-" ] && base=""
    echo
    echo "=== $protocol on $HOST:$port"
    if ! $GRADLE -q "$CORE_TEST" --rerun --tests '*ExternalServerTest' \
        -Dscrigno.it.protocol="$protocol" -Dscrigno.it.host="$HOST" -Dscrigno.it.port="$port" \
        -Dscrigno.it.share="$share" -Dscrigno.it.basePath="$base" -Dscrigno.it.allowSelfSigned="$selfsigned" \
        -Dscrigno.it.user="$USER_NAME" -Dscrigno.it.password="$PASSWORD"; then
        failed+=("$protocol")
        continue
    fi
    report="$(ls "$RESULTS_DIR"/*ExternalServerTest.xml)"
    summary="$(grep -o '<testsuite [^>]*>' "$report" | grep -o 'tests="[0-9]*"\|skipped="[0-9]*"\|failures="[0-9]*"\|errors="[0-9]*"' | tr '\n' ' ')"
    echo "$summary"
    if ! grep -q 'skipped="0"' "$report" || grep -q 'tests="0"' "$report"; then
        echo "Tests were skipped for $protocol"
        failed+=("$protocol")
    fi
done

echo
if [ "${#failed[@]}" -gt 0 ]; then
    echo "FAILED: ${failed[*]}"
    exit 1
fi
echo "All protocols passed against real servers."
