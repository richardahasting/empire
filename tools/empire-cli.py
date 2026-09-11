#!/usr/bin/env python3
"""
Empire terminal client. The original was played over telnet; this is the same idea over the
game's own console API, so every command you type here is the same command the web page sends.

    empire-cli.py                      # https://hastingtx.org/empire
    empire-cli.py --url http://127.0.0.1:8020/empire

Sign-in is by magic link: the client asks for your email, the server mails you a link, you
paste the token (or the whole link) back. The session token is kept in ~/.config/empire/.
Taking a seat asks for a country name — seats are handed out numbered (emp1, emp2, ...) and
are named by whoever claims them.
Inside the prompt: help, map, census, des, thresh, dist, move, expl, road, rail, railship,
and the client-side verbs  games | game N | update | schedule 15m | view | projection | quit.
"""
import argparse, json, os, sys, urllib.request, urllib.error, urllib.parse
try:
    import readline  # noqa: F401  (history and line editing on the prompt)
except ImportError:
    pass

CONF = os.path.expanduser("~/.config/empire")
SESSION = os.path.join(CONF, "session")

class Api:
    def __init__(self, base, token=None):
        self.base = base.rstrip("/") + "/api"; self.token = token
    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("content-type", "application/json")
        if self.token: req.add_header("authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                text = r.read().decode(); return json.loads(text) if text else None
        except urllib.error.HTTPError as e:
            text = e.read().decode()
            try: msg = json.loads(text).get("error", text)
            except Exception: msg = text
            raise RuntimeError(f"{e.code}: {msg}")
    def get(self, path): return self.call("GET", path)
    def post(self, path, body=None): return self.call("POST", path, body or {})

def sign_in(api):
    email = input("email: ").strip()
    name = input("name (first time only, else Enter): ").strip()
    api.post("/auth/request-link", {"email": email, "name": name})
    print("A sign-in link is on its way to", email)
    raw = input("paste the link or the token: ").strip()
    token = urllib.parse.parse_qs(urllib.parse.urlparse(raw).query).get("token", [raw])[0] if "token=" in raw else raw
    r = api.post("/auth/verify", {"token": token})
    api.token = r["token"]
    os.makedirs(CONF, exist_ok=True)
    with open(SESSION, "w") as f: f.write(api.token)
    os.chmod(SESSION, 0o600)
    print("signed in as", r["account"]["name"])

def pick_game(api):
    games = api.get("/games")
    if not games: print("no games; ask the deity to create one"); return None
    for g in games:
        seats = ", ".join(f"{c['name']}{' (you)' if c['id'] == g.get('myCountry') else ' (open)' if not c['taken'] else ''}" for c in g["countries"])
        print(f"  {g['id']:>3}  {g['name']:<24} {g['preset']:<9} update {g['updateNumber']:<5} {g['status']:<8} {seats}")
    mine = [g for g in games if g.get("myCountry") is not None]
    if len(mine) == 1: return mine[0]
    n = input("game id: ").strip()
    g = next((g for g in games if str(g["id"]) == n), None)
    if g is None: print("no game with id", repr(n))
    return g

def show(view):
    owned = [s for s in view["sectors"] if s["full"]]
    civ = sum(s["stock"].get("civ", 0) for s in owned); food = sum(s["stock"].get("food", 0) for s in owned)
    lv = view["levels"]
    print(f"{view['name']}: update {view['updateNumber']}  ${view['cash']:.0f}  {view['btu']:.0f} BTU  {len(owned)} sectors  civ {civ:.0f}  food {food:.0f}  "
          f"tech {lv['tech']:.1f} res {lv['research']:.1f} edu {lv['education']:.1f} hap {lv['happiness']:.1f}{'  [sanctuary]' if view['inSanctuary'] else ''}")

def main():
    ap = argparse.ArgumentParser(description="Empire terminal client")
    ap.add_argument("--url", default="https://hastingtx.org/empire"); ap.add_argument("--game", type=int)
    a = ap.parse_args()
    token = open(SESSION).read().strip() if os.path.exists(SESSION) else None
    api = Api(a.url, token)
    me = None
    if token:
        try: me = api.get("/me")
        except RuntimeError: api.token = None
    if not api.token: sign_in(api); me = api.get("/me")
    print(f"Empire — {me['name']} <{me['email']}>{' · deity' if me.get('admin') else ''}")
    game = None
    if a.game: game = api.get(f"/games/{a.game}")
    tries = 0
    while game is None and tries < 3: game = pick_game(api); tries += 1
    if game is None: return
    if game.get("myCountry") is None:
        open_seats = [c for c in game["countries"] if not c["taken"]]
        if not open_seats: print("no open country in that game"); return
        print("open seats:", ", ".join(f"{c['id']}={c['name']}" for c in open_seats))
        cid = input("take seat (id): ").strip()
        # a seat arrives called emp7 and the server will not hand it over unnamed (issue #119)
        name = ""
        while not name:
            name = input("name your country: ").strip()
        game = api.post(f"/games/{game['id']}/join", {"countryId": int(cid), "name": name})
        if game.get("status") == "setup":
            waiting = sum(1 for c in game["countries"] if not c["taken"])
            print(f"seated. the game starts when the last seat is taken — {waiting} still open")
        else:
            print("seated. the game is running")
    gid = game["id"]
    show(api.get(f"/games/{gid}/view"))
    print("type help for verbs; games, game N, update, schedule 15m, view, projection, quit are client-side")
    while True:
        try: line = input("empire> ").strip()
        except (EOFError, KeyboardInterrupt): print(); break
        if not line: continue
        verb, *rest = line.split()
        try:
            if verb in ("quit", "exit"): break
            elif verb == "games": pick_game(api)
            elif verb == "game" and rest: gid = int(rest[0]); show(api.get(f"/games/{gid}/view"))
            elif verb == "view": show(api.get(f"/games/{gid}/view"))
            elif verb == "projection":
                p = api.get(f"/games/{gid}/projection")
                print(f"next update: ${p['cashAfter'] - p['cashNow']:+.0f}  civ {p['civAfter'] - p['civNow']:+.0f}  food {p['foodAfter'] - p['foodNow']:+.0f}  "
                      f"starving sectors {p['starvingSectors']}  spoiling {p['spoilingSectors']}  stalled shipments {p['flowsHeld']}")
            elif verb == "update":
                r = api.post(f"/admin/games/{gid}/update"); print(f"update {r['updateNumber']} ran ({r['events']} events, {r['flows']} flows)"); show(api.get(f"/games/{gid}/view"))
            elif verb == "schedule" and rest:
                g = api.post(f"/admin/games/{gid}/schedule", {"interval": rest[0]}); print("interval", g["intervalSeconds"], "s; next", g.get("nextUpdateAt"))
            else:
                r = api.post(f"/games/{gid}/console", {"line": line})
                if r.get("error"): print("!", r["error"])
                elif r.get("output"): print(r["output"].rstrip())
                else: print("ok")
        except RuntimeError as e:
            print("!", e)

if __name__ == "__main__":
    main()
