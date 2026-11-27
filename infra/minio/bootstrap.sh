#!/bin/sh
set -eu

client() { mc --config-dir /tmp/tcm-minio-client "$@"; }
client alias set provision http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
client mb --ignore-existing provision/tcm-learning-sources >/dev/null
client admin user add provision "$TCM_MINIO_APP_ACCESS_KEY" "$TCM_MINIO_APP_SECRET_KEY" >/dev/null
client admin policy create provision tcm-source-access /bootstrap/source-policy.json >/dev/null
client admin policy attach provision tcm-source-access --user "$TCM_MINIO_APP_ACCESS_KEY" >/dev/null
client alias set application http://127.0.0.1:9000 "$TCM_MINIO_APP_ACCESS_KEY" "$TCM_MINIO_APP_SECRET_KEY" >/dev/null

# Touch only the probe created by this invocation, never existing user objects.
probe=".healthcheck/probe-$$"
trap 'client rm "application/tcm-learning-sources/$probe" >/dev/null 2>&1 || true' EXIT
printf '%s' 'tcm-minio-private-probe' | client pipe "application/tcm-learning-sources/$probe" >/dev/null
test "$(client cat "application/tcm-learning-sources/$probe")" = 'tcm-minio-private-probe'
status="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:9000/tcm-learning-sources/$probe")"
test "$status" = '403'
client rm "application/tcm-learning-sources/$probe" >/dev/null
trap - EXIT
printf '%s\n' 'Private bucket and application write/read/delete verified; anonymous object access denied (403).'
