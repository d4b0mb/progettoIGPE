#!/bin/sh
# Creates two demo restaurants so a fresh stack has something to look at: a generic trattoria,
# and Duodecim Pizze with its real menu and look.
# Safe to run twice: a restaurant that already exists is left as it is.
# Needs only sh and curl. Usage: API_URL=http://localhost:8080 sh scripts/seed-demo.sh
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"

API="${API_URL:-http://localhost:8080}"
PASSWORD="${DEMO_PASSWORD:-Demo-Password-2026}"
export API_URL="$API" PASSWORD
OWNER_EMAIL="owner@demo.ristomanager.local"

echo "Waiting for the API at $API ..."
tries=0
until curl -sf "$API/actuator/health" >/dev/null 2>&1; do
  tries=$((tries + 1))
  [ "$tries" -gt 90 ] && { echo "The API did not become healthy in time." >&2; exit 1; }
  sleep 2
done

# Tiny helpers: JSON in, the first value of a field out (no jq needed).
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -n 1; }
post() { # token path json
  curl -sf -X POST "$API$2" -H "Content-Type: application/json" -H "Authorization: Bearer $1" -d "$3"
}

# --- Trattoria Demo: a small generic restaurant
seed_trattoria() {
  status=$(curl -s -o /tmp/signup.json -w '%{http_code}' -X POST "$API/api/v1/auth/signup" \
    -H "Content-Type: application/json" \
    -d "{\"restaurantName\":\"Trattoria Demo\",\"ownerName\":\"Marco Rossi\",\"email\":\"$OWNER_EMAIL\",\"password\":\"$PASSWORD\"}")
  if [ "$status" = "409" ]; then
    echo "Demo restaurant already exists."
    return 0
  fi
  [ "$status" = "201" ] || { echo "Sign-up failed with HTTP $status" >&2; cat /tmp/signup.json >&2; exit 1; }
  TOKEN=$(field accessToken < /tmp/signup.json)

  for role in manager waiter kitchen; do
    upper=$(echo "$role" | tr 'a-z' 'A-Z')
    post "$TOKEN" /api/v1/staff \
      "{\"displayName\":\"Demo $role\",\"email\":\"$role@demo.ristomanager.local\",\"password\":\"$PASSWORD\",\"role\":\"$upper\"}" >/dev/null
  done

  ROOM=$(post "$TOKEN" /api/v1/rooms '{"name":"Main room","widthMeters":12,"heightMeters":9}' | field id)
  table() { # x y width height shape seats
    post "$TOKEN" "/api/v1/rooms/$ROOM/tables" \
      "{\"xMeters\":$1,\"yMeters\":$2,\"widthMeters\":$3,\"heightMeters\":$4,\"shape\":\"$5\",\"seats\":$6}" | field id
  }
  T1=$(table 2.5 3 1 1 ROUND 2)
  T2=$(table 5 3 1 1 ROUND 2)
  T3=$(table 2.5 6 1.4 0.9 RECTANGLE 4)
  T4=$(table 5.5 6 1.4 0.9 RECTANGLE 4)
  T5=$(table 9 3.5 3 0.9 RECTANGLE 8)
  T6=$(table 9 6.5 1.4 0.9 RECTANGLE 6)
  post "$TOKEN" "/api/v1/rooms/$ROOM/obstacles" '{"type":"BAR","xMeters":3,"yMeters":0.6,"widthMeters":4,"heightMeters":0.8}' >/dev/null
  post "$TOKEN" "/api/v1/rooms/$ROOM/obstacles" '{"type":"ENTRANCE","xMeters":11.5,"yMeters":8.7,"widthMeters":1.5,"heightMeters":0.3}' >/dev/null
  post "$TOKEN" "/api/v1/rooms/$ROOM/obstacles" '{"type":"COLUMN","xMeters":7,"yMeters":4.8,"widthMeters":0.5,"heightMeters":0.5}' >/dev/null

  dish() { # name course price allergens-json
    post "$TOKEN" /api/v1/menu/items "{\"name\":\"$1\",\"course\":\"$2\",\"priceCents\":$3,\"allergens\":$4}" >/dev/null
  }
  dish "Acqua naturale" DRINK 250 '[]'
  dish "Vino della casa" DRINK 600 '["SULPHITES"]'
  dish "Bruschetta" STARTER 700 '["GLUTEN"]'
  dish "Caprese" STARTER 900 '["MILK"]'
  dish "Spaghetti alle vongole" FIRST 1400 '["GLUTEN","MOLLUSCS"]'
  dish "Trofie al pesto" FIRST 1200 '["GLUTEN","TREE_NUTS","MILK"]'
  dish "Tagliata di manzo" SECOND 2000 '[]'
  dish "Branzino al forno" SECOND 1900 '["FISH"]'
  dish "Patate al forno" SIDE 500 '[]'
  dish "Tiramisù" DESSERT 700 '["EGGS","MILK","GLUTEN"]'

  now=$(date +%s)
  iso() { date -u -d "@$(( now + $1 ))" +%Y-%m-%dT%H:%M:%SZ; }
  book() { # guest phone offset-seconds party table status allergens
    post "$TOKEN" /api/v1/reservations \
      "{\"guestName\":\"$1\",\"phone\":\"$2\",\"startsAt\":\"$(iso "$3")\",\"partySize\":$4,\"tableId\":\"$5\",\"status\":\"$6\",\"allergens\":$7}" >/dev/null
  }
  book "Bianchi" "+39 333 000 3333" -600 2 "$T1" ARRIVED '["TREE_NUTS"]'
  book "Rossi" "+39 333 000 1111" 3600 4 "$T3" CONFIRMED '[]'
  book "Greco" "+39 333 000 2222" 5400 6 "$T6" CONFIRMED '["GLUTEN"]'
  book "Marino" "+39 333 000 5555" 7200 2 "$T2" PENDING '[]'
  echo
  echo "Demo restaurant ready."
  echo "  Owner:    $OWNER_EMAIL"
  echo "  Manager:  manager@demo.ristomanager.local"
  echo "  Waiter:   waiter@demo.ristomanager.local"
  echo "  Kitchen:  kitchen@demo.ristomanager.local"
  echo "  Password: $PASSWORD (all four)"
}

# --- Duodecim Pizze: a real pizzeria's menu, in the pizzeria look
# Safe to run again, and it finishes a restaurant that an earlier run left half done.
seed_duodecim() {
  email="owner@duodecim.ristomanager.local"
  status=$(curl -s -o /tmp/signup2.json -w '%{http_code}' -X POST "$API/api/v1/auth/signup" \
    -H "Content-Type: application/json" \
    -d "{\"restaurantName\":\"Duodecim Pizze\",\"ownerName\":\"Titolare Duodecim\",\"email\":\"$email\",\"password\":\"$PASSWORD\"}")
  if [ "$status" = "201" ]; then
    TOKEN=$(field accessToken < /tmp/signup2.json)
  elif [ "$status" = "409" ]; then
    echo "Duodecim Pizze already exists; checking that it is complete."
    TOKEN=$(curl -sf -X POST "$API/api/v1/auth/login" -H "Content-Type: application/json" \
      -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}" | field accessToken)
    [ -n "$TOKEN" ] || { echo "Duodecim exists but the demo password does not work for it; leaving it alone." >&2; return 0; }
  else
    echo "Duodecim sign-up failed with HTTP $status" >&2; cat /tmp/signup2.json >&2; exit 1
  fi

  # Staff accounts; one that already exists is simply skipped.
  for role in manager waiter kitchen; do
    upper=$(echo "$role" | tr 'a-z' 'A-Z')
    curl -s -o /dev/null -X POST "$API/api/v1/staff" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
      -d "{\"displayName\":\"Duodecim $role\",\"email\":\"$role@duodecim.ristomanager.local\",\"password\":\"$PASSWORD\",\"role\":\"$upper\"}" || true
  done

  # The look of the printed menu: green, tomato red, cream, serif titles.
  curl -sf -X PUT "$API/api/v1/restaurant/theme" -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
    -d '{"theme":"DUODECIM"}' >/dev/null

  # A placeholder room so orders can be tried straight away. Draw the real one in the floor plan screen.
  rooms=$(curl -sf "$API/api/v1/rooms" -H "Authorization: Bearer $TOKEN")
  if [ "$rooms" = "[]" ]; then
    ROOM=$(post "$TOKEN" /api/v1/rooms '{"name":"Sala","widthMeters":10,"heightMeters":7}' | field id)
    for pos in "2 2" "4.5 2" "7 2" "9 2" "2 5" "4.5 5" "7 5" "9 5"; do
      set -- $pos
      post "$TOKEN" "/api/v1/rooms/$ROOM/tables" \
        "{\"xMeters\":$1,\"yMeters\":$2,\"widthMeters\":1.2,\"heightMeters\":1.2,\"shape\":\"RECTANGLE\",\"seats\":4}" >/dev/null
    done
  fi

  # Dishes already on the menu are skipped, so this only fills what is missing.
  EMAIL="$email" sh "$HERE/import-menu.sh" "$HERE/menus/duodecim-pizze.txt"
  echo
  echo "Duodecim Pizze ready."
  echo "  Owner:    $email   (manager@, waiter@, kitchen@ + duodecim.ristomanager.local)"
  echo "  Password: $PASSWORD (all four)"
}

seed_trattoria
seed_duodecim
