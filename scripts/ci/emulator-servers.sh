#!/usr/bin/env bash
# Prints the "scrignoServers" instrumentation argument for the Android tests: the servers started by
# start-test-servers.sh, as seen from the Android emulator (10.0.2.2 is the host machine).
# Format of each line: PROTOCOL host port user password share basePath allowSelfSigned ("-" = empty),
# then URL-safe Base64 so that the value survives the adb shell untouched.
set -euo pipefail

USER_NAME="${SCRIGNO_TEST_USER:-scrigno}"
PASSWORD="${SCRIGNO_TEST_PASSWORD:-Scr1gno-test-pw}"
HOST="${SCRIGNO_EMULATOR_HOST:-10.0.2.2}"

{
    echo "WEBDAV  $HOST 8080 $USER_NAME $PASSWORD -      /dav         false"
    echo "SFTP    $HOST 22   $USER_NAME $PASSWORD -      /srv/scrigno false"
    echo "FTP     $HOST 21   $USER_NAME $PASSWORD -      /srv/scrigno false"
    echo "FTPS    $HOST 21   $USER_NAME $PASSWORD -      /srv/scrigno true"
    echo "SMB     $HOST 445  $USER_NAME $PASSWORD photos -            false"
    echo "WEBDAVS $HOST 8443 $USER_NAME $PASSWORD -      /dav         true"
} | base64 -w0 | tr '+/' '-_' | tr -d '='
