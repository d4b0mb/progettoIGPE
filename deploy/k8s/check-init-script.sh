#!/bin/sh
# Fails if the database init script copy used by Kubernetes has drifted from the original.
#
# kustomize cannot read files outside its own folder, so deploy/k8s/base/db-init/01-app-role.sh
# is a COPY of infra/postgres/01-app-role.sh. Edit both together, then run:
#
#   sh deploy/k8s/check-init-script.sh
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
ORIGINAL="$HERE/../../infra/postgres/01-app-role.sh"
COPY="$HERE/base/db-init/01-app-role.sh"

[ -f "$ORIGINAL" ] || { echo "missing: $ORIGINAL" >&2; exit 2; }
[ -f "$COPY" ] || { echo "missing: $COPY" >&2; exit 2; }

if cmp -s "$ORIGINAL" "$COPY"; then
  echo "OK: deploy/k8s/base/db-init/01-app-role.sh matches infra/postgres/01-app-role.sh"
else
  echo "DIFFERENT: the Kubernetes copy no longer matches infra/postgres/01-app-role.sh" >&2
  echo "  original: $ORIGINAL" >&2
  echo "  copy:     $COPY" >&2
  echo "Make them identical (edit one, copy it over the other)." >&2
  exit 1
fi
