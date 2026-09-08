#!/bin/bash
# Build the frontend, package the server with it, restart the service, verify the public URL.
# Run on linuxserver from the repo root. Needs empire-server/.env (never committed).
set -euo pipefail
cd "$(dirname "$0")"
[ -f empire-server/.env ] || { echo "empire-server/.env missing (copy .env.example)"; exit 1; }
echo "== frontend"; (cd empire-web && npm ci --silent && npm run build --silent)
echo "== server";   mvn -q -B -DskipTests package
if [ ! -f /etc/systemd/system/empire.service ]; then
  echo "== installing unit (needs sudo)"; sudo cp ops/empire.service /etc/systemd/system/empire.service; sudo systemctl daemon-reload; sudo systemctl enable empire.service
fi
echo "== restart";  sudo systemctl restart empire.service
for i in $(seq 1 60); do sleep 1; curl -sf http://127.0.0.1:8020/empire/api/health >/dev/null && break; done
curl -sf http://127.0.0.1:8020/empire/api/health >/dev/null || { echo "server did not come up"; sudo journalctl -u empire -n 30 --no-pager; exit 1; }
echo "== public";   curl -s -o /dev/null -w 'https://hastingtx.org/empire/ -> %{http_code}\n' https://hastingtx.org/empire/
A=$(curl -s https://hastingtx.org/empire/ | grep -oE '/empire/assets/index-[A-Za-z0-9_-]+\.js' | head -1)
echo "served bundle: $A"
