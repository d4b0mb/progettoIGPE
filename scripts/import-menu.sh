#!/bin/sh
# Loads a menu file into an existing restaurant. The file has one dish per line, fields separated by |:
#   name | course | section | price in cents | allergens (comma separated) | description
# Lines starting with # are comments. Existing dishes with the same name are left as they are.
# Usage: API_URL=http://localhost:8080 EMAIL=owner@... PASSWORD=... sh scripts/import-menu.sh scripts/menus/duodecim-pizze.txt
set -eu

FILE="${1:?Usage: import-menu.sh <menu file>}"
API="${API_URL:-http://localhost:8080}"
EMAIL="${EMAIL:?Set EMAIL to the owner's email}"
PASSWORD="${PASSWORD:?Set PASSWORD to the owner's password}"

field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -n 1; }
# Escapes backslashes and double quotes so a value can sit inside JSON.
esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

TOKEN=$(curl -sf -X POST "$API/api/v1/auth/login" -H "Content-Type: application/json" \
  -d "{\"email\":\"$(esc "$EMAIL")\",\"password\":\"$(esc "$PASSWORD")\"}" | field accessToken)
[ -n "$TOKEN" ] || { echo "Could not sign in as $EMAIL" >&2; exit 1; }

added=0
skipped=0
while IFS='|' read -r name course section price allergens description; do
  case "$name" in ''|'#'*) continue ;; esac
  list=""
  if [ -n "$allergens" ]; then
    list=$(printf '%s' "$allergens" | sed 's/[^,][^,]*/"&"/g')
  fi
  status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/v1/menu/items" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d "{\"name\":\"$(esc "$name")\",\"course\":\"$course\",\"section\":\"$(esc "$section")\",\"priceCents\":$price,\"allergens\":[$list],\"description\":\"$(esc "$description")\"}")
  case "$status" in
    201) added=$((added + 1)) ;;
    409) skipped=$((skipped + 1)) ;;
    *) echo "Could not add \"$name\" (HTTP $status)" >&2; exit 1 ;;
  esac
done < "$FILE"

echo "Menu imported: $added added, $skipped already there."
