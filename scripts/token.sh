#!/usr/bin/env bash
set -euo pipefail
user=${1:-alice}
password=${DEMO_PASSWORD:-${user}-local-only}
curl -fsS http://localhost:8180/realms/shop/protocol/openid-connect/token   -d grant_type=password -d client_id=shop --data-urlencode "username=$user" --data-urlencode "password=$password" |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
