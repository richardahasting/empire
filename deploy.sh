#!/bin/bash
# Build the frontend, package the server with it, restart the service, verify the public URL.
# Run on linuxserver from the repo root. Needs empire-server/.env (never committed).
#
# Phases can be run on their own:
#   ./deploy.sh            everything
#   ./deploy.sh build      frontend + jar          (no privileges)
#   ./deploy.sh restart    restart the service     (needs sudo)
#   ./deploy.sh verify     health + public URL     (no privileges)
#
# Only "restart" needs privileges, and it is one systemctl call. A session that cannot answer a
# password prompt runs `build`, restarts through the sudo MCP, then runs `verify`.
set -euo pipefail
cd "$(dirname "$0")"

SERVICE=empire
HEALTH=http://127.0.0.1:8020/empire/api/health
PUBLIC=https://hastingtx.org/empire/

build() {
  [ -f empire-server/.env ] || { echo "empire-server/.env missing (copy .env.example)"; exit 1; }
  echo "== guide";    python3 tools/build-docs.py
  echo "== frontend"; (cd empire-web && npm ci --silent && npm run build --silent)
  echo "== server";   mvn -q -B -DskipTests package
}

restart() {
  if [ ! -f /etc/systemd/system/$SERVICE.service ]; then
    echo "== installing unit"; sudo cp ops/$SERVICE.service /etc/systemd/system/$SERVICE.service
    sudo systemctl daemon-reload; sudo systemctl enable $SERVICE.service
  fi
  if ! sudo -n true 2>/dev/null && [ ! -t 0 ]; then
    echo "== cannot restart: sudo needs a password and there is no terminal to ask at." >&2
    echo "   Restart it another way, then run './deploy.sh verify':" >&2
    echo "     - from a Claude session: the sudo MCP, service_control $SERVICE restart" >&2
    echo "     - from a terminal:       sudo systemctl restart $SERVICE" >&2
    exit 2
  fi
  echo "== restart"; sudo systemctl restart $SERVICE.service
}

verify() {
  for _ in $(seq 1 60); do sleep 1; curl -sf $HEALTH >/dev/null && break; done
  curl -sf $HEALTH >/dev/null || {
    echo "server did not come up"
    if sudo -n true 2>/dev/null; then sudo journalctl -u $SERVICE -n 30 --no-pager; else echo "(journalctl -u $SERVICE for the log)"; fi
    exit 1
  }
  # A healthy process is not a working deployment. The server loads every game at startup and
  # catches per game, so a config the stored worlds cannot bind leaves it up, answering, and empty —
  # which is exactly how a deploy passed with the live game gone. Check the work came back.
  local health loaded onrecord
  health=$(curl -s $HEALTH)
  loaded=$(printf '%s' "$health" | grep -o '"games":[0-9]*' | cut -d: -f2)
  onrecord=$(printf '%s' "$health" | grep -o '"gamesOnRecord":[0-9]*' | cut -d: -f2)
  echo "== games";  echo "loaded $loaded of $onrecord on record"
  if [ -n "$onrecord" ] && [ "$loaded" != "$onrecord" ]; then
    echo "DEPLOY FAILED: $onrecord game(s) on record, $loaded loaded — something did not bind" >&2
    if sudo -n true 2>/dev/null; then sudo journalctl -u $SERVICE -n 20 --no-pager; fi
    grep -E "cannot load game" /var/log/empire.log 2>/dev/null | tail -3 >&2 || true
    exit 4
  fi

  echo "== public"; curl -s -o /dev/null -w "$PUBLIC -> %{http_code}\n" $PUBLIC
  local a; a=$(curl -s $PUBLIC | grep -oE '/empire/assets/index-[A-Za-z0-9_-]+\.js' | head -1)
  echo "served bundle: $a"
  # the jar is packaged from empire-web/dist, so a mismatch here means the browser is on a stale build
  local built; built=$(grep -oE '/empire/assets/index-[A-Za-z0-9_-]+\.js' empire-web/dist/index.html | head -1)
  if [ -n "$built" ] && [ "$a" != "$built" ]; then
    echo "WARNING: serving $a but the last build produced $built" >&2
    exit 3
  fi
}

case "${1:-all}" in
  build)   build ;;
  restart) restart ;;
  verify)  verify ;;
  all)     build; restart; verify ;;
  *)       echo "usage: $0 [build|restart|verify]" >&2; exit 64 ;;
esac
