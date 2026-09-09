#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
base_url=${BASE_URL:-http://localhost:8080}
token=$(./scripts/token.sh alice)
mode=${1:-SUCCESS}
shipping=${2:-SUCCESS}
body=$(python3 -c 'import json,sys; print(json.dumps(dict(sku="DEMO",quantity=1,amount=19.99,currency="USD",paymentMode=sys.argv[1],shippingMode=sys.argv[2])))' "$mode" "$shipping")
response=$(curl -fsS "$base_url/api/orders" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d "$body")
printf '%s
' "$response"
id=$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["orderId"])')
# BFF details expose immediate command state while projection catches up.
curl -fsS "$base_url/api/orders/$id" -H "Authorization: Bearer $token"
printf '
Live feed (Ctrl-C to disconnect; use Last-Event-ID to resume):
'
curl -N --retry 3 --retry-delay 1 --retry-all-errors "$base_url/api/orders/$id/events" -H "Authorization: Bearer $token" -H 'Last-Event-ID: 0'
