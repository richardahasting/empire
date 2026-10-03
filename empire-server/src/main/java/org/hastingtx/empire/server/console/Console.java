package org.hastingtx.empire.server.console;

import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.SectorTypeCfg;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.engine.view.CountryView.SectorView;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.game.GameService;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The text command line. Coordinates are player-relative (capital = 0,0) as in the
 * original. Command verbs are parsed into the same Command records the panels use and go
 * through the same executor; query verbs render text.
 */
@Service
public class Console {
    private final GameService games;
    private final org.hastingtx.empire.server.macro.MacroRepository macros;
    private final org.hastingtx.empire.server.persistence.Json json;
    public Console(GameService games, org.hastingtx.empire.server.macro.MacroRepository macros, org.hastingtx.empire.server.persistence.Json json) { this.games = games; this.macros = macros; this.json = json; }

    public record Reply(String output, boolean accepted, String error, CountryView view) {}

    public Reply run(long gameId, Account a, String line) {
        String[] words = line.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return new Reply("", true, null, null);
        CountryView v = games.view(gameId, a);
        GameConfig cfg = games.get(gameId).cfg;
        String verb = words[0].toLowerCase(Locale.ROOT);
        // a trailing "check" asks what an order would do, without doing it (issues #272, #273); deliver has its own
        // probe, and in a message or a country's name the word is just a word
        boolean check = words.length > 1 && words[words.length - 1].equalsIgnoreCase("check") && !NOT_CHECKED.contains(verb);
        String[] t = check ? Arrays.copyOf(words, words.length - 1) : words;
        Run run = new Run(gameId, a, v, check);
        try {
            return switch (verb) {
                case "help", "?" -> new Reply(HELP, true, null, null);
                case "map" -> new Reply(map(v, cfg), true, null, null);
                case "census", "cen" -> new Reply(t.length > 1 && t[1].toLowerCase(Locale.ROOT).startsWith("res") ? censusResources(v, cfg) : census(v, cfg, games.lastNotes(gameId, a, json), games.recentEvents(gameId, a, LOSSES, LOSS_WINDOW)), true, null, null);
                case "food" -> new Reply(food(games.foodReport(gameId, a)), true, null, null);
                case "break" -> { most(t, 1, "break"); yield cmd(run, new Command.BreakSanctuary()); }
                case "des", "designate" -> { need(t, 3, "des SECTOR type"); most(t, 3, "des SECTOR type"); yield many(run, cfg, t[1], at -> new Command.Designate(at, t[2])); }
                case "thresh", "threshold" -> {
                    need(t, 4, "thresh SECTOR commodity amount"); most(t, 4, "thresh SECTOR commodity amount");
                    double n = Double.parseDouble(t[3]);
                    boolean mixed = SectorSelector.isMixed(t[1]);
                    List<Coord> targets = SectorSelector.expand(v, cfg, t[1]);
                    // the ack says what each kind of sector actually got, not just that something was applied (issue #146)
                    yield many(run, cfg, t[1], at -> new Command.Threshold(at, t[2], mixed ? SectorSelector.massThreshold(v, cfg, at, t[2], n) : n), mixed ? SectorSelector.effectiveNote(v, cfg, targets, t[2], n) : null);
                }
                case "demob", "demobilize", "demobilise" -> {
                    need(t, 3, "demob SECTOR N | demob SECTOR all | demob SECTOR keep N");
                    boolean keep = t[2].equalsIgnoreCase("keep") || t[2].equalsIgnoreCase("all");
                    if (t[2].equalsIgnoreCase("keep")) need(t, 4, "demob SECTOR keep N");
                    most(t, keep && !t[2].equalsIgnoreCase("all") ? 4 : 3, "demob SECTOR N | demob SECTOR all | demob SECTOR keep N");
                    double n = t[2].equalsIgnoreCase("all") ? 0 : Double.parseDouble(keep ? t[3] : t[2]);
                    yield many(run, cfg, t[1], at -> new Command.Demobilize(at, n, keep));
                }
                case "enl", "enlist" -> {
                    need(t, 3, "enlist SECTOR N | enlist SECTOR -N (up to N in each)"); most(t, 3, "enlist SECTOR N | enlist SECTOR -N (up to N in each)");
                    double n = Double.parseDouble(t[2]);
                    yield many(run, cfg, t[1], at -> new Command.Enlist(at, Math.abs(n), n < 0));   // KNOWN enli.c: a negative number is a quota
                }
                case "dist", "distribute" -> { need(t, 3, "dist SECTOR cx,cy|none"); most(t, 3, "dist SECTOR cx,cy|none"); Coord ctr = t[2].equalsIgnoreCase("none") ? null : abs(v, t[2]); yield many(run, cfg, t[1], at -> new Command.Distribute(at, ctr)); }
                case "ships", "fleet" -> new Reply(fleet(v, cfg), true, null, null);
                case "contacts", "radar" -> new Reply(contacts(v), true, null, null);
                case "build" -> {
                    need(t, 3, "build HARBOUR SHIPCLASS [name] | build HEADQUARTERS UNITCLASS");
                    // a land unit class builds a unit in a headquarters (issue #247); anything else is a ship
                    if (cfg.units().land() != null && cfg.units().land().hasClass(t[2])) yield cmd(run, new Command.BuildUnit(abs(v, t[1]), t[2]));
                    // a plane class builds a plane on an airfield (issue #262)
                    if (cfg.units().planes() != null && cfg.units().planes().planeClass(t[2]) != null) yield cmd(run, new Command.BuildPlane(abs(v, t[1]), t[2]));
                    // a warhead class builds a warhead in a nuclear plant (issue #71)
                    if (cfg.units().nukes() != null && cfg.units().nukes().nukeClass(t[2]) != null) yield cmd(run, new Command.BuildNuke(abs(v, t[1]), t[2]));
                    yield cmd(run, new Command.BuildShip(abs(v, t[1]), t[2], t.length > 3 ? String.join(" ", Arrays.copyOfRange(t, 3, t.length)) : null));
                }
                case "sail" -> { need(t, 3, "sail SHIP x,y | sail SHIP hold"); most(t, 3, "sail SHIP x,y | sail SHIP hold"); yield cmd(run, new Command.Sail(Long.parseLong(t[1].replace("#", "")), t[2].equalsIgnoreCase("hold") ? null : abs(v, t[2]))); }
                case "load" -> { need(t, 4, "load SHIP COMMODITY N"); most(t, 4, "load SHIP COMMODITY N"); yield cmd(run, new Command.Load(Long.parseLong(t[1].replace("#", "")), t[2], Double.parseDouble(t[3]))); }
                case "unload" -> { need(t, 4, "unload SHIP COMMODITY N"); most(t, 4, "unload SHIP COMMODITY N"); yield cmd(run, new Command.Unload(Long.parseLong(t[1].replace("#", "")), t[2], Double.parseDouble(t[3]))); }
                case "lane" -> {
                    need(t, 3, "lane SHIP x,y x2,y2 [COMMODITY ...] | lane SHIP none");
                    long id = Long.parseLong(t[1].replace("#", ""));
                    if (t[2].equalsIgnoreCase("none")) yield cmd(run, new Command.Lane(id, null, null, List.of()));
                    need(t, 4, "lane SHIP x,y x2,y2 [COMMODITY ...]");
                    yield cmd(run, new Command.Lane(id, abs(v, t[2]), abs(v, t[3]), t.length > 4 ? List.of(Arrays.copyOfRange(t, 4, t.length)) : List.of()));
                }
                case "declare" -> {
                    need(t, 3, "declare war COUNTRY");
                    if (!t[1].equalsIgnoreCase("war")) yield new Reply("declare war COUNTRY", false, null, null);
                    int on = games.countryNamed(gameId, rest(line, 2));
                    if (on < 0) yield new Reply("no country called " + rest(line, 2) + " in this game", false, null, null);
                    yield cmd(run, new Command.DeclareWar(on));
                }
                case "peace" -> {
                    need(t, 2, "peace COUNTRY");
                    int with = games.countryNamed(gameId, rest(line, 1));
                    if (with < 0) yield new Reply("no country called " + rest(line, 1) + " in this game", false, null, null);
                    yield cmd(run, new Command.OfferPeace(with));
                }
                case "tel", "telegram" -> {
                    need(t, 3, "telegram COUNTRY \"what you want to say\"");
                    int to = games.countryNamed(gameId, t[1]);
                    if (to < 0) yield new Reply("no country called " + t[1] + " in this game", false, null, null);
                    yield cmd(run, new Command.Telegram(to, rest(line, 2)));
                }
                case "announce" -> {
                    need(t, 2, "announce \"what you want everyone to hear\"");
                    yield cmd(run, new Command.Announce(rest(line, 1)));
                }
                case "fish" -> { need(t, 2, "fish SHIP [x,y] | fish SHIP off"); most(t, 3, "fish SHIP [x,y] | fish SHIP off"); long id = Long.parseLong(t[1].replace("#", "")); boolean off = t.length > 2 && t[2].equalsIgnoreCase("off"); yield cmd(run, new Command.Fish(id, !off && t.length > 2 ? abs(v, t[2]) : null, off)); }
                case "patrol", "blockade", "interdict", "search", "escort" -> {
                    need(t, 2, verb + " SHIP ... | " + verb + " SHIP off");
                    long id = Long.parseLong(t[1].replace("#", ""));
                    if (t.length > 2 && t[2].equalsIgnoreCase("off")) yield cmd(run, new Command.Mission(id, verb, List.of(), 0, true));
                    if (verb.equals("escort")) { need(t, 3, "escort SHIP OTHER_SHIP"); yield cmd(run, new Command.Mission(id, verb, List.of(), Long.parseLong(t[2].replace("#", "")), false)); }
                    List<Coord> pts = new ArrayList<>();
                    for (int i = 2; i < t.length; i++) pts.add(abs(v, t[i]));
                    yield cmd(run, new Command.Mission(id, verb, pts, 0, false));
                }
                case "anti" -> { need(t, 2, "anti SECTOR"); most(t, 2, "anti SECTOR"); yield many(run, cfg, t[1], Command.Anti::new); }
                case "unrest" -> new Reply(unrest(v), true, null, null);
                case "attack", "att" -> {
                    String usage = "attack x,y [N from x2,y2 ...] [unit U ...]";
                    need(t, 3, usage);
                    List<Command.Attack.Party> parties = new ArrayList<>();
                    List<Long> units = new ArrayList<>();
                    for (int k = 2; k < t.length; ) {
                        if (t[k].equalsIgnoreCase("unit") && k + 1 < t.length) { units.add(Long.parseLong(t[k + 1].replace("#", ""))); k += 2; }
                        else if (k + 2 < t.length && t[k + 1].equalsIgnoreCase("from")) { parties.add(new Command.Attack.Party(abs(v, t[k + 2]), Double.parseDouble(t[k]))); k += 3; }
                        else throw new IllegalArgumentException("usage: " + usage);
                    }
                    yield cmd(run, new Command.Attack(abs(v, t[1]), parties, units));
                }
                case "army", "units" -> new Reply(army(v), true, null, null);
                case "market", "mark" -> { most(t, 2, "market [COMMODITY | all]"); yield new Reply(market(v, t.length > 1 ? t[1].toLowerCase(Locale.ROOT) : null), true, null, null); }
                case "sell" -> {
                    String usage = "sell COMMODITY SECTOR N PRICE   (N negative: all but that many; PRICE a unit)";
                    need(t, 5, usage); most(t, 5, usage);
                    double n = Double.parseDouble(t[3]), price = Double.parseDouble(t[4].replace("$", ""));
                    yield cmd(run, new Command.Sell(abs(v, t[2]), t[1], n, price));
                }
                case "buy" -> {
                    String usage = "buy LOT PRICE SECTOR   (PRICE a unit; SECTOR a harbour or warehouse of yours)";
                    need(t, 4, usage); most(t, 4, usage);
                    yield cmd(run, new Command.Buy(Long.parseLong(t[1].replace("#", "")), Double.parseDouble(t[2].replace("$", "")), abs(v, t[3])));
                }
                case "set" -> {
                    String usage = "set ship|plane|unit IDS PRICE   (IDS like 3 or 3,5,7; whole dollars; 0 takes them off)";
                    need(t, 4, usage); most(t, 4, usage);
                    List<Long> ids = new ArrayList<>();
                    for (String id : t[2].split(",")) if (!id.isBlank()) ids.add(Long.parseLong(id.replace("#", "").trim()));
                    yield cmd(run, new Command.SetPrice(t[1], ids, Double.parseDouble(t[3].replace("$", ""))));
                }
                case "trade" -> {
                    if (t.length == 1) yield new Reply(trades(v), true, null, null);
                    String usage = "trade | trade LOT PRICE [SECTOR]   (a plane goes to SECTOR, an airfield; a unit to a headquarters; a ship changes hands where she lies)";
                    need(t, 3, usage); most(t, 4, usage);
                    yield cmd(run, new Command.Trade(Long.parseLong(t[1].replaceFirst("^[Tt#]", "")), Double.parseDouble(t[2].replace("$", "")), t.length > 3 ? abs(v, t[3]) : null));
                }
                case "reset" -> { String usage = "reset LOT PRICE   (lower; 0 takes the lot back)"; need(t, 3, usage); most(t, 3, usage); yield cmd(run, new Command.ResetLot(Long.parseLong(t[1].replace("#", "")), Double.parseDouble(t[2].replace("$", "")))); }
                case "air", "planes" -> new Reply(air(v), true, null, null);
                case "nukes", "nuke" -> new Reply(nukes(v), true, null, null);
                case "arm" -> {
                    String usage = "arm PLANE NUKE [airburst]   (a warhead in the plane's sector onto it; arm PLANE 0 airburst re-sets the one aboard)";
                    need(t, 3, usage); most(t, 4, usage);
                    if (t.length == 4 && !t[3].toLowerCase(Locale.ROOT).startsWith("air")) throw new IllegalArgumentException("usage: " + usage);
                    yield cmd(run, new Command.Arm(Long.parseLong(t[1].replace("#", "")), Long.parseLong(t[2].replace("#", "")), t.length == 4));
                }
                case "disarm" -> { String usage = "disarm PLANE   (its warhead off, into its sector)"; need(t, 2, usage); most(t, 2, usage); yield cmd(run, new Command.Disarm(Long.parseLong(t[1].replace("#", "")))); }
                case "march", "mar" -> { need(t, 3, "march UNIT x,y"); most(t, 3, "march UNIT x,y"); yield cmd(run, new Command.March(Long.parseLong(t[1].replace("#", "")), abs(v, t[2]))); }
                case "bomb" -> {
                    String usage = "bomb PLANE x,y [strategic] [escort E,E...]";
                    need(t, 3, usage);
                    boolean strategic = t.length > 3 && t[3].toLowerCase(Locale.ROOT).startsWith("s");
                    List<Long> escorts = escorts(t, strategic ? 4 : 3, usage);
                    yield cmd(run, new Command.Bomb(Long.parseLong(t[1].replace("#", "")), abs(v, t[2]), !strategic, escorts));
                }
                case "fly" -> {
                    String usage = "fly PLANES x,y [COMMODITY] [escort E,E]   (one way to an airfield of yours; transports carry their load twice over)";
                    need(t, 3, usage);
                    boolean what = t.length > 3 && !t[3].equalsIgnoreCase("escort");
                    yield cmd(run, new Command.Fly(ids(t[1]), abs(v, t[2]), what ? t[3] : null, escorts(t, what ? 4 : 3, usage)));
                }
                case "drop" -> {
                    String usage = "drop PLANES x,y COMMODITY [escort E,E]   (transports drop on a sector of yours and fly home)";
                    need(t, 4, usage);
                    yield cmd(run, new Command.Drop(ids(t[1]), abs(v, t[2]), t[3], escorts(t, 4, usage)));
                }
                case "paradrop", "para" -> {
                    String usage = "paradrop PLANES x,y [escort E,E]   (transports drop their field's soldiers on a sector not yours)";
                    need(t, 3, usage);
                    yield cmd(run, new Command.Paradrop(ids(t[1]), abs(v, t[2]), escorts(t, 3, usage)));
                }
                case "launch" -> {
                    String usage = "launch MISSILE x,y | launch MISSILE ship N | launch SATELLITE x,y [geo]   (a missile once, and it is spent; a satellite into orbit)";
                    need(t, 3, usage);
                    long id = Long.parseLong(t[1].replace("#", ""));
                    if (t[2].equalsIgnoreCase("ship")) { need(t, 4, usage); most(t, 4, usage); yield cmd(run, new Command.Launch(id, null, Long.parseLong(t[3].replace("#", "")))); }
                    most(t, 4, usage);
                    if (t.length == 4 && !t[3].toLowerCase(Locale.ROOT).startsWith("geo")) throw new IllegalArgumentException("usage: " + usage);
                    yield cmd(run, new Command.Launch(id, abs(v, t[2]), 0, t.length == 4));
                }
                case "satellite" -> { String usage = "satellite N   (what a satellite of yours in orbit sees)"; need(t, 2, usage); most(t, 2, usage); yield cmd(run, new Command.Satellite(Long.parseLong(t[1].replace("#", "")))); }
                case "lay" -> { String usage = "lay SHIP N   (a minelayer at sea lays N mines from her shells)"; need(t, 3, usage); most(t, 3, usage); yield cmd(run, new Command.Lay(Long.parseLong(t[1].replace("#", "")), Double.parseDouble(t[2]))); }
                case "lmine" -> { String usage = "lmine UNIT N   (an engineer lays N land mines in its sector: a shell and a mobility point each)"; need(t, 3, usage); most(t, 3, usage); yield cmd(run, new Command.LandMine(Long.parseLong(t[1].replace("#", "")), Double.parseDouble(t[2]))); }
                case "sweep" -> {
                    String usage = "sweep PLANES x,y [escort E,E]   (mine-sweeping planes fly over the sea and back, sweeping as they go)";
                    need(t, 3, usage);
                    yield cmd(run, new Command.SweepAir(ids(t[1]), abs(v, t[2]), escorts(t, 3, usage)));
                }
                case "mission" -> {
                    String usage = "mission PLANE air x,y [RADIUS] | mission PLANE off   (air defence: a fighter rises over any sector within RADIUS of x,y)";
                    need(t, 3, usage);
                    long id = Long.parseLong(t[1].replace("#", ""));
                    if (t[2].equalsIgnoreCase("off")) { most(t, 3, usage); yield cmd(run, new Command.AirMission(id, null, 0, true)); }
                    if (!t[2].toLowerCase(Locale.ROOT).startsWith("a")) throw new IllegalArgumentException("usage: " + usage);
                    need(t, 4, usage); most(t, 5, usage);
                    yield cmd(run, new Command.AirMission(id, abs(v, t[3]), t.length > 4 ? Double.parseDouble(t[4]) : 0, false));
                }
                case "recon" -> { String usage = "recon PLANE x,y [escort E,E...]"; need(t, 3, usage); yield cmd(run, new Command.Recon(Long.parseLong(t[1].replace("#", "")), abs(v, t[2]), escorts(t, 3, usage))); }
                case "work" -> { need(t, 2, "work UNIT [mobility]"); most(t, 3, "work UNIT [mobility]"); yield cmd(run, new Command.Work(Long.parseLong(t[1].replace("#", "")), t.length > 2 ? Double.parseDouble(t[2]) : 0)); }
                case "ufire" -> { need(t, 3, "ufire UNIT x,y"); most(t, 3, "ufire UNIT x,y"); yield cmd(run, new Command.UnitFire(Long.parseLong(t[1].replace("#", "")), abs(v, t[2]))); }
                case "sabotage" -> { need(t, 2, "sabotage UNIT"); most(t, 2, "sabotage UNIT"); yield cmd(run, new Command.Sabotage(Long.parseLong(t[1].replace("#", "")))); }
                case "incite" -> { need(t, 2, "incite UNIT"); most(t, 2, "incite UNIT"); yield cmd(run, new Command.Incite(Long.parseLong(t[1].replace("#", "")))); }
                case "board" -> { need(t, 3, "board UNIT SHIP"); most(t, 3, "board UNIT SHIP"); yield cmd(run, new Command.Board(Long.parseLong(t[1].replace("#", "")), Long.parseLong(t[2].replace("#", "")))); }
                case "ashore" -> { need(t, 2, "ashore UNIT"); most(t, 2, "ashore UNIT"); yield cmd(run, new Command.Board(Long.parseLong(t[1].replace("#", "")), 0)); }
                case "lload", "lunload" -> { need(t, 4, verb + " UNIT COMMODITY N"); most(t, 4, verb + " UNIT COMMODITY N"); yield cmd(run, new Command.LoadUnit(Long.parseLong(t[1].replace("#", "")), t[2], Double.parseDouble(t[3]), verb.equals("lunload"))); }
                case "land" -> { need(t, 3, "land SHIP x,y"); most(t, 3, "land SHIP x,y"); yield cmd(run, new Command.Land(Long.parseLong(t[1].replace("#", "")), abs(v, t[2]))); }
                case "fire" -> { need(t, 3, "fire SHIP x,y [CLASS]"); most(t, 4, "fire SHIP x,y [CLASS]"); yield cmd(run, new Command.Fire(Long.parseLong(t[1].replace("#", "")), abs(v, t[2]), t.length > 3 ? t[3] : null)); }
                case "supply" -> { need(t, 2, "supply SHIP [x,y] | supply SHIP off"); most(t, 3, "supply SHIP [x,y] | supply SHIP off"); long id = Long.parseLong(t[1].replace("#", "")); boolean off = t.length > 2 && t[2].equalsIgnoreCase("off"); yield cmd(run, new Command.Supply(id, !off && t.length > 2 ? abs(v, t[2]) : null, off)); }
                case "manifest", "man" -> new Reply(t.length > 1 ? manifest(v, Long.parseLong(t[1].replace("#", ""))) : manifests(v), true, null, null);
                case "history", "log" -> { need(t, 2, "history SHIP [UPDATES]"); most(t, 3, "history SHIP [UPDATES]"); yield new Reply(history(Long.parseLong(t[1].replace("#", "")), games.shipHistory(gameId, a, Long.parseLong(t[1].replace("#", "")), t.length > 2 ? Integer.parseInt(t[2]) : 5)), true, null, null); }
                case "mine" -> { need(t, 2, "mine SHIP [x,y] | mine SHIP off"); most(t, 3, "mine SHIP [x,y] | mine SHIP off"); long id = Long.parseLong(t[1].replace("#", "")); boolean off = t.length > 2 && t[2].equalsIgnoreCase("off"); yield cmd(run, new Command.Mine(id, !off && t.length > 2 ? abs(v, t[2]) : null, off)); }
                case "scrap" -> { need(t, 2, "scrap SHIP"); most(t, 2, "scrap SHIP"); yield cmd(run, new Command.Scrap(Long.parseLong(t[1].replace("#", "")))); }
                case "macro", "macros" -> {
                    var mine = macros.list(a.id());
                    if (t.length >= 3 && t[1].equalsIgnoreCase("run")) {
                        need(t, 4, "macro run N SECTOR");
                        int slot = Integer.parseInt(t[2]);
                        var m = macros.find(a.id(), slot).orElseThrow(() -> new IllegalArgumentException("no macro in slot " + slot));
                        boolean mixed = SectorSelector.isMixed(t[3]);
                        List<Command> cmds = new ArrayList<>();
                        for (Coord at : SectorSelector.expand(v, cfg, t[3])) cmds.addAll(org.hastingtx.empire.server.macro.Macros.expand(m.steps(), v, cfg, at, mixed));
                        String note = "macro " + slot + " '" + m.name() + "'";
                        yield check ? preview(run, cmds, note) : reply(games.commandAll(gameId, a, cmds, "console", note));
                    }
                    if (mine.isEmpty()) yield new Reply("no macros yet — record one from a sector's right-click menu", true, null, null);
                    StringBuilder sb = new StringBuilder();
                    for (var m : mine) sb.append(String.format("%2d  %-20s %s%n", m.slot(), m.name(), org.hastingtx.empire.server.macro.Macros.describe(m.steps())));
                    yield new Reply(sb.toString(), true, null, null);
                }
                case "deliver", "del" -> {
                    need(t, 4, "deliver COMMODITY SECTOR DIR N   (DIR e ne nw w sw se, or none to clear; add 'check' to look without ordering)");
                    // a dry run (playtest game 82, issue #155): the only way to learn what lay in a
                    // direction used to be to write a standing order there and read the reply
                    if (t[t.length - 1].equalsIgnoreCase("check")) {
                        need(t, 5, "deliver COMMODITY SECTOR DIR [N] check"); most(t, 6, "deliver COMMODITY SECTOR DIR [N] check");
                        int pd = org.hastingtx.empire.engine.geo.Hex.parseDir(t[3]);
                        if (pd < 0) throw new IllegalArgumentException(org.hastingtx.empire.engine.geo.Hex.dirError(t[3]));
                        yield new Reply(probeDeliver(v, abs(v, t[2]), pd, t[1]), true, null, null);
                    }
                    boolean clear = t[3].equalsIgnoreCase("none") || t[3].equalsIgnoreCase("off");
                    if (!clear) need(t, 5, "deliver COMMODITY SECTOR DIR N"); most(t, 5, "deliver COMMODITY SECTOR DIR N");
                    int dir = clear ? -1 : org.hastingtx.empire.engine.geo.Hex.parseDir(t[3]);
                    if (!clear && dir < 0) throw new IllegalArgumentException(org.hastingtx.empire.engine.geo.Hex.dirError(t[3]));
                    double thr = clear ? 0 : Double.parseDouble(t[4]);
                    yield many(run, cfg, t[2], at -> new Command.Deliver(at, t[1], clear ? null : dir, thr));
                }
                case "move" -> { need(t, 5, "move commodity from_x,y to_x,y qty"); most(t, 5, "move commodity from_x,y to_x,y qty"); yield cmd(run, new Command.Move(abs(v, t[2]), abs(v, t[3]), t[1], Double.parseDouble(t[4]))); }
                case "rail" -> { need(t, 3, "rail SECTOR LEVEL"); most(t, 3, "rail SECTOR LEVEL"); double lvl = Double.parseDouble(t[2]); yield many(run, cfg, t[1], at -> new Command.BuildRail(at, lvl)); }
                case "railship", "train" -> { need(t, 5, "railship COMMODITY from_x,y to_x,y qty"); most(t, 5, "railship COMMODITY from_x,y to_x,y qty"); yield cmd(run, new Command.RailShip(abs(v, t[2]), abs(v, t[3]), t[1], Double.parseDouble(t[4]))); }
                case "raillane" -> {
                    need(t, 3, "raillane x,y x2,y2 [COMMODITY ...] | raillane x,y x2,y2 none");
                    Coord from = abs(v, t[1]), to = abs(v, t[2]);
                    boolean off = t.length > 3 && t[3].equalsIgnoreCase("none");
                    yield cmd(run, new Command.RailLane(from, to, off || t.length <= 3 ? List.of() : List.of(Arrays.copyOfRange(t, 3, t.length)), off));
                }
                case "road" -> { need(t, 3, "road SECTOR LEVEL"); most(t, 3, "road SECTOR LEVEL"); double lvl = Double.parseDouble(t[2]); yield many(run, cfg, t[1], at -> new Command.BuildRoad(at, lvl)); }
                case "adjacent", "adj" -> {
                    need(t, 2, "adjacent SECTOR"); most(t, 2, "adjacent SECTOR");
                    yield new Reply(adjacent(v, abs(v, t[1])), true, null, null);
                }
                case "expl", "explore" -> { need(t, 4, "expl from_x,y to_x,y civs"); most(t, 4, "expl from_x,y to_x,y civs"); yield cmd(run, new Command.Explore(abs(v, t[1]), abs(v, t[2]), Double.parseDouble(t[3]))); }
                default -> new Reply("", false, "unknown command '" + verb + "' (try help)", null);
            };
        } catch (IllegalArgumentException e) {
            return new Reply("", false, e.getMessage(), null);
        }
    }

    /** Everything after the first {@code n} words, unquoted: what somebody actually wants to say. */
    private static String rest(String line, int n) {
        String[] parts = line.trim().split("\\s+", n + 1);
        String body = parts.length > n ? parts[n] : "";
        body = body.strip();
        if (body.length() >= 2 && body.startsWith("\"") && body.endsWith("\"")) body = body.substring(1, body.length() - 1);
        return body;
    }

    /** Who is typing, what they see, and whether they only want to look (a trailing {@code check}). */
    private record Run(long gameId, Account a, CountryView v, boolean check) {}

    private Reply cmd(Run r, Command c) { return r.check() ? preview(r, List.of(c), null) : reply(games.command(r.gameId(), r.a(), c, "console")); }

    /** The same verb on one sector or many: SECTOR is x,y · * · *:TYPE · x1:x2,y1:y2 (see {@link SectorSelector}). */
    private Reply many(Run r, GameConfig cfg, String sel, java.util.function.Function<Coord, Command> f) { return many(r, cfg, sel, f, null); }

    private Reply many(Run r, GameConfig cfg, String sel, java.util.function.Function<Coord, Command> f, String note) {
        List<Command> cmds = SectorSelector.expand(r.v(), cfg, sel).stream().map(f).toList();
        if (cmds.size() == 1) return cmd(r, cmds.get(0));
        return r.check() ? preview(r, cmds, note) : reply(games.commandAll(r.gameId(), r.a(), cmds, "console", note));
    }

    /**
     * The orders a {@code check} may preview. None has dice in it: a fight's rolls are seeded from the world, so a
     * previewed attack, shot, bombing or spy mission would show exactly how the real one comes out. Moving ships and
     * units is left out too — a preview would scout for free.
     */
    private static final Set<String> NOT_CHECKED = Set.of("deliver", "del", "tel", "telegram", "announce", "declare", "peace");

    static final Set<String> PREVIEWABLE = Set.of("designate", "threshold", "demobilize", "enlist", "distribute", "move", "explore",
            "build_road", "build_rail", "rail_ship", "rail_lane", "build_ship", "build_unit", "build_plane", "load", "unload", "lload", "lunload",
            "lane", "fish", "mine", "supply", "scrap", "sell", "buy", "reset_lot", "set_price", "trade", "air_defence", "lay", "lmine");
    // fly, drop and paradrop are not here: the fighters that rise against them roll dice

    /**
     * What an order would do, run against the world as it stands and thrown away (issues #272, #273): the
     * executor's own answer, what it would cost, and every sector, ship and unit of yours it would change.
     * Nothing is saved, logged or charged. An order that cannot be previewed is refused — never carried out.
     */
    private Reply preview(Run r, List<Command> cmds, String note) {
        for (Command c : cmds)
            if (!PREVIEWABLE.contains(c.verb())) return new Reply("", false, "'check' cannot preview " + c.verb() + " — only orders with no chance in them; nothing was done", null);
        GameService.Preview p = games.preview(r.gameId(), r.a(), cmds, note);
        GameService.Outcome o = p.outcome();
        StringBuilder out = new StringBuilder("CHECK — nothing was done and nothing was charged. ");
        if (!o.accepted()) return new Reply(out.append("It would be REFUSED: ").append(o.error()).toString(), true, null, null);
        // explore's answer reads the new land's fertility, which stays hidden until somebody settles it: a free
        // preview would be a free survey, so a checked explore says only that it would go, and what it would move
        boolean surveys = cmds.stream().anyMatch(c -> c instanceof Command.Explore);
        out.append("It would be accepted (").append(o.btuSpent()).append(" BTU)");
        if (o.info() != null && !surveys) out.append(": ").append(o.info());
        String changed = changes(p.before(), o.view());
        out.append(changed.isEmpty() ? "\n  (it would change nothing you can see)" : "\n" + changed);
        return new Reply(out.toString(), true, null, null);
    }

    /** Every sector, ship and unit of yours that differs between two views of the same country, one line each. */
    static String changes(CountryView before, CountryView after) {
        List<String> lines = new ArrayList<>();
        Map<Coord, SectorView> now = new HashMap<>();
        for (SectorView s : after.sectors()) now.put(s.at(), s);
        for (SectorView b : before.sectors()) {
            SectorView n = now.get(b.at());
            if (n == null || (b.owner() != before.countryId() && n.owner() != after.countryId())) continue;
            List<String> d = new ArrayList<>();
            boolean wasMine = b.owner() == before.countryId();
            if (b.owner() != n.owner()) d.add(n.owner() == after.countryId() ? "becomes yours" : "is no longer yours");
            if (!wasMine) {   // what a neighbour's view showed of it is not a baseline: only what would arrive
                amounts(d, "", Map.of(), n.stock());
                if (!d.isEmpty()) lines.add("  " + rel(b.relative()) + ": " + String.join("; ", d));
                continue;
            }
            if (!Objects.equals(b.designation(), n.designation())) d.add(b.designation() + " → " + n.designation());
            if (Math.round(b.efficiency()) != Math.round(n.efficiency())) d.add("efficiency " + Math.round(b.efficiency()) + " → " + Math.round(n.efficiency()));
            if (b.roadTarget() != n.roadTarget()) d.add("road target " + fmtQ(b.roadTarget()) + " → " + fmtQ(n.roadTarget()));
            if (b.railTarget() != n.railTarget()) d.add("rail target " + fmtQ(b.railTarget()) + " → " + fmtQ(n.railTarget()));
            amounts(d, "", b.stock(), n.stock());
            amounts(d, "threshold ", b.thresholds(), n.thresholds());
            amounts(d, "in transit ", b.held(), n.held());
            if (!Objects.equals(b.distCenter(), n.distCenter())) d.add("centre " + centre(before, b.distCenter()) + " → " + centre(before, n.distCenter()));
            Set<String> orders = new TreeSet<>();
            if (b.deliveries() != null) orders.addAll(b.deliveries().keySet());
            if (n.deliveries() != null) orders.addAll(n.deliveries().keySet());
            for (String c : orders) {
                var was = b.deliveries() == null ? null : b.deliveries().get(c);
                var is = n.deliveries() == null ? null : n.deliveries().get(c);
                if (!Objects.equals(was, is)) d.add("deliver " + c + " " + delivery(was) + " → " + delivery(is));
            }
            if (!d.isEmpty()) lines.add("  " + rel(b.relative()) + ": " + String.join("; ", d));
        }
        Map<Long, CountryView.ShipView> ships = new HashMap<>();
        for (var s : before.ships()) ships.put(s.id(), s);
        for (var s : after.ships()) {
            var b = ships.remove(s.id());
            List<String> d = new ArrayList<>();
            if (b == null) d.add("new " + s.cls() + " at " + rel(s.relative()));
            else {
                amounts(d, "", b.stock(), s.stock());
                if (!Objects.equals(b.mission(), s.mission())) d.add("mission " + (b.mission() == null ? "none" : b.mission()) + " → " + (s.mission() == null ? "none" : s.mission()));
                if (!Objects.equals(b.homeRelative(), s.homeRelative())) d.add("home " + where(b.homeRelative()) + " → " + where(s.homeRelative()));
                if (!Objects.equals(b.routeRelative(), s.routeRelative())) d.add("route " + route(b.routeRelative()) + " → " + route(s.routeRelative()));
                if (!Objects.equals(b.lane(), s.lane())) d.add("lane " + lane(b.lane()) + " → " + lane(s.lane()));
                if (!Objects.equals(b.destRelative(), s.destRelative())) d.add("bound for " + where(b.destRelative()) + " → " + where(s.destRelative()));
            }
            if (!d.isEmpty()) lines.add("  ship #" + s.id() + ": " + String.join("; ", d));
        }
        for (var s : ships.values()) lines.add("  ship #" + s.id() + ": gone");
        Map<Long, CountryView.UnitView> units = new HashMap<>();
        for (var u : before.units()) units.put(u.id(), u);
        for (var u : after.units()) {
            var b = units.remove(u.id());
            List<String> d = new ArrayList<>();
            if (b == null) d.add("new " + u.cls() + " at " + rel(u.relative()));
            else amounts(d, "", b.stock(), u.stock());
            if (!d.isEmpty()) lines.add("  unit #" + u.id() + ": " + String.join("; ", d));
        }
        List<CountryView.RailLaneView> railWas = before.railLanes() == null ? List.of() : before.railLanes(), railIs = after.railLanes() == null ? List.of() : after.railLanes();
        for (var l : railIs) if (!railWas.contains(l)) lines.add("  new rail lane " + rel(l.fromRelative()) + " → " + rel(l.toRelative()) + (l.cargo().isEmpty() ? " (to its thresholds)" : " carrying " + String.join(", ", l.cargo())));
        for (var l : railWas) if (!railIs.contains(l)) lines.add("  rail lane " + rel(l.fromRelative()) + " → " + rel(l.toRelative()) + " stops");
        List<CountryView.TrainView> trainsWas = before.trains() == null ? List.of() : before.trains(), trainsIs = after.trains() == null ? List.of() : after.trains();
        for (var t : trainsIs) if (!trainsWas.contains(t)) lines.add("  a train of " + fmtQ(t.qty()) + " " + t.commodity() + " at " + rel(t.relative()) + ", bound for " + where(t.destRelative()));
        int planes = after.planes().size() - before.planes().size();
        if (planes > 0) lines.add("  " + planes + " new plane" + (planes == 1 ? "" : "s"));
        if (lines.size() > 12) { int more = lines.size() - 12; lines = new ArrayList<>(lines.subList(0, 12)); lines.add("  … and " + more + " more"); }
        return String.join("\n", lines);
    }

    private static void amounts(List<String> d, String label, Map<String, Double> was, Map<String, Double> is) {
        Set<String> keys = new TreeSet<>();
        if (was != null) keys.addAll(was.keySet());
        if (is != null) keys.addAll(is.keySet());
        for (String k : keys) {
            Double a = was == null ? null : was.get(k), b = is == null ? null : is.get(k);
            double x = a == null || a.isNaN() ? 0 : a, y = b == null || b.isNaN() ? 0 : b;
            boolean setA = a != null && !a.isNaN(), setB = b != null && !b.isNaN();
            if (Math.abs(x - y) < 1e-9 && setA == setB) continue;
            if (label.isEmpty()) d.add(k + " " + fmtQ(x) + " → " + fmtQ(y));
            else d.add(label + k + " " + (setA ? fmtQ(x) : "none") + " → " + (setB ? fmtQ(y) : "none"));
        }
    }

    private static String where(Coord rel) { return rel == null ? "nowhere" : rel(rel); }
    private static String route(List<Coord> pts) { return pts == null || pts.isEmpty() ? "none" : String.join(" → ", pts.stream().map(Console::rel).toList()); }
    private static String lane(CountryView.LaneView l) { return l == null ? "none" : rel(l.fromRelative()) + " ⇄ " + rel(l.toRelative()) + (l.cargo().isEmpty() ? "" : " carrying " + String.join(", ", l.cargo())); }
    private static String centre(CountryView v, Coord c) { return c == null ? "none" : rel(rel(v, c)); }
    private static String delivery(CountryView.Delivery d) { return d == null ? "none" : d.dir() + " above " + fmtQ(d.threshold()); }

    /** Plane numbers, by commas: {@code 3,5,#7}. */
    static List<Long> ids(String s) {
        List<Long> out = new ArrayList<>();
        for (String id : s.split(",")) {
            if (id.isBlank()) continue;
            try { out.add(Long.parseLong(id.replace("#", "").trim())); } catch (NumberFormatException e) { throw new IllegalArgumentException("'" + id + "' is not a plane number"); }
        }
        return out;
    }

    /** {@code escort E,E ...} from word {@code k} on (issue #71): plane numbers, by commas or spaces; nothing else may follow. */
    static List<Long> escorts(String[] t, int k, String usage) {
        if (t.length <= k) return List.of();
        if (!t[k].equalsIgnoreCase("escort") || t.length == k + 1) throw new IllegalArgumentException("usage: " + usage);
        List<Long> out = new ArrayList<>();
        for (int i = k + 1; i < t.length; i++)
            for (String id : t[i].split(",")) {
                if (id.isBlank()) continue;
                try { out.add(Long.parseLong(id.replace("#", "").trim())); } catch (NumberFormatException e) { throw new IllegalArgumentException("usage: " + usage + " — '" + id + "' is not a plane number"); }
            }
        return out;
    }

    /** The words after the last one a verb reads are a mistake, not a comment (issue #272): refuse them rather than ignore them. */
    private static void most(String[] t, int n, String usage) {
        if (t.length > n) throw new IllegalArgumentException("usage: " + usage + " — did not expect '" + String.join(" ", Arrays.copyOfRange(t, n, t.length)) + "'");
    }

    private static Reply reply(GameService.Outcome o) {
        return new Reply(o.accepted() ? (o.info() != null ? o.info() + " (" + o.btuSpent() + " BTU)" : "ok (" + o.btuSpent() + " BTU)") : "", o.accepted(), o.error(), o.view());
    }

    private static void need(String[] t, int n, String usage) { if (t.length < n) throw new IllegalArgumentException("usage: " + usage); }

    /** "x,y" relative to the capital -> absolute, using the view's own relative table. */
    /** Step one hex, respecting the wrap the view describes; null if it falls off an unwrapped edge. */
    private static Coord stepInView(CountryView v, Coord from, int dir) {
        Coord raw = org.hastingtx.empire.engine.geo.Hex.stepRaw(from, dir);
        int x = raw.x(), y = raw.y();
        if (v.wrapX()) x = Math.floorMod(x, v.width()); else if (x < 0 || x >= v.width()) return null;
        if (v.wrapY()) y = Math.floorMod(y, v.height()); else if (y < 0 || y >= v.height()) return null;
        return new Coord(x, y);
    }

    private static SectorView inView(CountryView v, Coord at) {
        if (at == null) return null;
        for (SectorView sv : v.sectors()) if (sv.at().equals(at)) return sv;
        return null;
    }

    /** One word for what a hex is to this player. Adjacent hexes are always in view, so this is honest. */
    private static String describe(CountryView v, SectorView sv) {
        if (sv == null) return "unknown (not in view)";
        if (!sv.terrain().equals("ocean") && sv.owner() == v.countryId()) return "yours: " + sv.designation() + (sv.full() ? "" : "");
        if (sv.terrain().equals("ocean")) return "sea";
        if (sv.sanctuary()) return "sanctuary of " + (sv.ownerName() == null ? "somebody" : sv.ownerName());
        if (sv.owner() >= 0 && sv.ownerName() != null) return sv.ownerName() + "'s " + sv.terrain();
        if (sv.owner() >= 0) return "somebody's " + sv.terrain();
        return "unowned " + sv.terrain() + (sv.remembered() ? " (as remembered)" : "");
    }

    /**
     * What lies each way from a sector (playtest game 82, issue #154). Before this the only way to
     * learn adjacency was to explore with one civilian — claiming a starve shell — or to write a
     * delivery order and read the refusal. This writes nothing and costs nothing.
     */
    static String adjacent(CountryView v, Coord at) {
        SectorView here = inView(v, at);
        StringBuilder out = new StringBuilder("around " + rel(rel(v, at)) + (here == null ? "" : " (" + describe(v, here) + ")") + ":\n");
        for (int d = 0; d < 6; d++) {
            Coord n = stepInView(v, at, d);
            SectorView sv = inView(v, n);
            out.append("  ").append(String.format("%-3s", org.hastingtx.empire.engine.geo.Hex.dirName(d))).append(' ')
               .append(n == null ? "—  (edge of the world)" : rel(rel(v, n)) + "  " + describe(v, sv));
            if (sv != null && sv.resources() != null && sv.owner() < 0 && !sv.terrain().equals("ocean"))
                out.append("  fert ").append(sv.resources().fertility()).append(" min ").append(sv.resources().minerals());
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    /** A delivery order, described but not made (issue #155). */
    static String probeDeliver(CountryView v, Coord from, int dir, String commodity) {
        SectorView src = inView(v, from);
        if (src == null || src.owner() != v.countryId()) return "you do not own " + rel(rel(v, from)) + "; nothing to deliver from";
        Coord n = stepInView(v, from, dir);
        SectorView sv = inView(v, n);
        String what = describe(v, sv);
        boolean would = sv != null && !sv.terrain().equals("ocean") && sv.owner() == v.countryId();
        StringBuilder out = new StringBuilder(org.hastingtx.empire.engine.geo.Hex.dirName(dir) + " of " + rel(rel(v, from)) + " is "
                + (n == null ? "the edge of the world" : rel(rel(v, n)) + ", " + what) + ". ");
        out.append(would ? "A " + commodity + " order that way would deliver." : "An order that way would be REFUSED — nothing would move.");
        if (src.deliveries() != null && src.deliveries().containsKey(commodity)) {
            var cur = src.deliveries().get(commodity);
            out.append(" Note: ").append(rel(rel(v, from))).append(" already delivers ").append(commodity).append(' ').append(cur.dir())
               .append(" above ").append(fmtQ(cur.threshold())).append("; a new order would replace it.");
        }
        out.append(" Nothing was written.");
        return out.toString();
    }

    /** The same fold as CountryView.relative, from the view's own dimensions — there is no World here. */
    static Coord rel(CountryView v, Coord abs) {
        int dx = abs.x() - v.capital().x(), dy = abs.y() - v.capital().y();
        if (v.wrapX()) { if (dx > v.width() / 2) dx -= v.width(); else if (dx < -v.width() / 2) dx += v.width(); }
        if (v.wrapY()) { if (dy > v.height() / 2) dy -= v.height(); else if (dy < -v.height() / 2) dy += v.height(); }
        return new Coord(dx, dy);
    }
    private static String fmtQ(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }

    static Coord abs(CountryView v, String s) {
        String[] p = s.split(",");
        if (p.length != 2) throw new IllegalArgumentException("coordinates look like x,y — got '" + s + "'");
        int rx, ry;
        try { rx = Integer.parseInt(p[0].trim()); ry = Integer.parseInt(p[1].trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("coordinates look like x,y — got '" + s + "'"); }
        for (SectorView sv : v.sectors()) if (sv.relative().x() == rx && sv.relative().y() == ry) return sv.at();
        // not in view: extrapolate from the capital (the executor will reject if out of bounds or unowned)
        int x = v.capital().x() + rx, y = v.capital().y() + ry;
        // off an edge that does not wrap: most likely absolute coordinates, copied from somewhere that printed them
        // (issue #271: "sail 136 63,56" said "out of bounds: 126,113") — say how coordinates work, and what was meant
        if ((!v.wrapX() && (x < 0 || x >= v.width())) || (!v.wrapY() && (y < 0 || y >= v.height()))) {
            int ax = rx - v.capital().x(), ay = ry - v.capital().y();
            boolean onMap = rx >= 0 && rx < v.width() && ry >= 0 && ry < v.height();
            throw new IllegalArgumentException(s + " is off the map: coordinates are counted from your capital, which is 0,0"
                    + (onMap ? " — if " + s + " was a map position, it is " + ax + "," + ay + " from your capital" : ""));
        }
        return new Coord(x, y);
    }

    static String map(CountryView v, GameConfig cfg) {
        if (v.sectors().isEmpty()) return "(nothing visible)";
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        Map<Coord, SectorView> byRel = new HashMap<>();
        for (SectorView s : v.sectors()) { Coord r = s.relative(); byRel.put(r, s); minX = Math.min(minX, r.x()); maxX = Math.max(maxX, r.x()); minY = Math.min(minY, r.y()); maxY = Math.max(maxY, r.y()); }
        StringBuilder sb = new StringBuilder();
        // two header rows: sign/tens and units, one column per x
        sb.append("     ");
        for (int x = minX; x <= maxX; x++) { int a = Math.abs(x); sb.append(x < 0 && a < 10 ? '-' : a >= 10 ? (char) ('0' + (a / 10) % 10) : ' ').append(' '); }
        sb.append("\n     ");
        for (int x = minX; x <= maxX; x++) sb.append((char) ('0' + Math.abs(x) % 10)).append(' ');
        sb.append('\n');
        for (int y = minY; y <= maxY; y++) {
            sb.append(String.format("%4d ", y));
            if (((v.capital().y() + y) & 1) == 1) sb.append(' ');   // odd rows of the absolute grid are shifted
            for (int x = minX; x <= maxX; x++) {
                SectorView s = byRel.get(new Coord(x, y));
                char g;
                if (s == null) g = ' ';
                else if (s.full()) g = cfg.sectorType(s.designation()).glyph().charAt(0);
                else if (s.terrain().equals("ocean")) g = '.';
                else if (s.owner() >= 0) g = s.sanctuary() ? 's' : '?';
                else g = '-';
                sb.append(g).append(' ');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** One line per ship, relative coordinates. */
    /**
     * The fleet, one line a hull. Says what standing mission each is on and which its class
     * <em>can</em> run (issue #162): a player who did not know {@code mine} existed sat driving a
     * miner by hand, and the listing was the natural place to have told them.
     */
    static String fleet(CountryView v, GameConfig cfg) {
        if (v.ships().isEmpty()) return "no ships — build one in a harbour: build x,y fishing_boat";
        StringBuilder sb = new StringBuilder(String.format("%-4s %-24s %-8s %5s %5s %-11s %-8s %-18s %-14s %s%n", "id", "class", "at", "eff", "speed", "load/hold", "on", "going", "can", "last update"));
        for (var s : v.ships()) {
            String on = s.lane() != null ? "lane" : s.mission() != null && !s.mission().isBlank() ? s.mission() : "—";
            String going = s.lane() != null ? "lane " + rel(s.lane().fromRelative()) + (s.lane().outbound() ? " → " : " ← ") + rel(s.lane().toRelative()) : "rescue".equals(s.mission()) ? "rescuing #" + s.ward() + (s.destRelative() != null ? " → " + rel(s.destRelative()) : "") : "tender".equals(shipRole(cfg, s.cls())) && (s.mission() == null || s.mission().isBlank()) && s.lane() == null ? "on call" + (s.destRelative() != null ? " → " + rel(s.destRelative()) : "") : s.mission() != null && java.util.Set.of("patrol", "search", "escort", "blockade", "interdict").contains(s.mission()) ? s.mission() + (s.ward() > 0 ? " #" + s.ward() : "") + (s.destRelative() != null ? " → " + rel(s.destRelative()) : "") : "supply".equals(s.mission()) ? "supply" + (s.destRelative() != null ? " → " + rel(s.destRelative()) : s.docked() ? ", in harbour" : "") : s.mission() != null && !s.mission().isBlank() ? ("fish".equals(s.mission()) ? "fishing" : "mining") + " from " + rel(s.homeRelative()) + (s.destRelative() != null ? " → " + rel(s.destRelative()) : "") : s.destRelative() != null ? "to " + rel(s.destRelative()) : s.docked() ? "in harbour" : "holding";
            sb.append(String.format("%-4d %-24s %-8s %5.0f %5d %-11s %-8s %-18s %-14s %s%n", s.id(), s.cls() + (s.name() == null || s.name().isBlank() ? "" : " " + s.name()), rel(s.relative()), s.efficiency(), s.hexesPerUpdate(), (int) s.load() + "/" + (int) s.hold(), on, going, missionsOf(cfg, s.cls()), s.note()));
        }
        for (var s : v.ships()) if (!s.markedBy().isEmpty())
            sb.append("ship #").append(s.id()).append(" fired on ").append(String.join(", ", s.markedBy())).append(" in peacetime: they may shoot her on sight for now\n");
        for (var s : v.ships()) if (s.handLeg())
            sb.append("ship #").append(s.id()).append(" is sailing by hand; her ").append(s.lane() != null ? "lane" : s.mission()).append(" resumes when she arrives\n");
        sb.append("on: the standing mission the hull is running; can: the ones its class may be given (plus sail, always)\n");
        return sb.toString();
    }

    /**
     * The standing missions a class can be given — decided by the same facts the engine checks when
     * the order arrives (a fishing rate, a mining rate, something it may carry), not by the role
     * label, so the listing cannot promise what the command would then refuse.
     */
    static String missionsOf(GameConfig cfg, String clsId) {
        var ships = cfg.units() == null ? null : cfg.units().ships();
        if (ships == null || ships.classes() == null) return "sail";
        for (var c : ships.classes()) {
            if (!c.id().equals(clsId)) continue;
            List<String> can = new ArrayList<>();
            if (c.fishingRateOr0() > 0) can.add("fish");
            if (c.miningRateOr0() > 0) can.add("mine");
            if (!c.carriesOrEmpty().isEmpty() && !c.military()) { can.add("lane"); can.add("supply"); }
            if (c.tender()) can.add("answers distress calls");
            if (c.landing()) can.add("land");
            if (c.armed()) { can.add("fire"); can.add("patrol"); can.add("search"); can.add("escort"); can.add("blockade"); can.add("interdict"); }
            return can.isEmpty() ? "sail only" : String.join(", ", can);
        }
        return "sail";
    }
    private static String rel(Coord c) { return c.x() + "," + c.y(); }
    private static String shipRole(GameConfig cfg, String clsId) {
        var ships = cfg.units() == null ? null : cfg.units().ships();
        return ships == null || !ships.hasClass(clsId) ? "" : ships.shipClass(clsId).role();
    }

    /** A ship's logbook, newest update first, a numbered line for each thing she did (issue #67). */
    static String history(long ship, List<org.hastingtx.empire.server.persistence.LogRepository.ShipLogEntry> book) {
        if (book.isEmpty()) return "ship #" + ship + " has no log yet — it is written at each update";
        StringBuilder sb = new StringBuilder();
        for (var e : book) {
            sb.append("update ").append(e.updateNumber()).append('\n');
            for (int i = 0; i < e.lines().size(); i++) sb.append(String.format("  %d. %s%n", i + 1, e.lines().get(i)));
        }
        return sb.toString();
    }

    /** Your land units (issue #247): where, how fit, soldiers and supplies, mobility, strength in attack and defence. */
    static String army(CountryView v) {
        if (v.units().isEmpty()) return "no land units — designate a headquarters and build one there (build x,y infantry)";
        StringBuilder sb = new StringBuilder(String.format("%-5s %-10s %-8s %4s %5s %5s %5s %4s %5s  %s%n", "unit", "class", "at", "eff", "mil", "food", "mob", "att", "def", "carries"));
        for (var u : v.units()) {
            String rest = String.join(", ", u.stock().entrySet().stream().filter(e -> !e.getKey().equals("mil") && !e.getKey().equals("food")).map(e -> String.format("%.0f %s", e.getValue(), e.getKey())).toList());
            sb.append(String.format("#%-4d %-10s %-8s %3.0f%% %5.0f %5.0f %5.0f %4.0f %5.0f  %s%s%n", u.id(), u.cls(), (u.ship() != 0 ? "@#" + u.ship() : rel(u.relative())), u.efficiency(), u.stock().getOrDefault("mil", 0.0),
                    u.stock().getOrDefault("food", 0.0), u.mobility(), u.attack(), u.defense(), rest, u.note() == null || u.note().isBlank() ? "" : " · " + u.note()));
        }
        return sb.append("att/def: mil × strength × efficiency; @#N = aboard ship N. march UNIT x,y · lload UNIT mil N · board UNIT SHIP · ashore UNIT · attack x,y unit UNIT · a spy: sabotage UNIT · incite UNIT").toString();
    }

    /** Your planes (issue #262): where each sits, its condition, what it carries and how far it strikes. */
    /** Your warheads (issue #71): where each is and what it is armed on. */
    static String nukes(CountryView v) {
        if (v.nukes() == null || v.nukes().isEmpty()) return "no warheads — build one in a nuclear plant (build x,y fission_10kt)";
        StringBuilder sb = new StringBuilder(String.format("%-5s %-14s %-8s %6s %5s %6s  %s%n", "nuke", "class", "at", "weight", "blast", "damage", "armed"));
        for (var n : v.nukes())
            sb.append(String.format("#%-4d %-14s %-8s %6.0f %5d %6.0f  %s%n", n.id(), n.name(), rel(n.relative()), n.weight(), n.blast(), n.damage(),
                    n.plane() == 0 ? "stored" : "on plane #" + n.plane() + (n.airburst() ? ", airburst" : ", groundburst")));
        return sb.append("arm PLANE NUKE [airburst] · disarm PLANE · then bomb or launch it as usual").toString();
    }

    static String air(CountryView v) {
        StringBuilder over = new StringBuilder();
        for (var o : v.overhead() == null ? List.<CountryView.OverheadView>of() : v.overhead())
            over.append(over.isEmpty() ? "satellites overhead: " : "; ").append(o.ownerName()).append("'s ").append(o.name()).append(" over ").append(rel(o.relative()));
        if (!over.isEmpty()) over.append(String.format("%n"));
        if (v.planes().isEmpty()) return over + "no planes — designate an airfield and build one there (build x,y bomber)";
        StringBuilder sb = new StringBuilder(String.format("%-5s %-16s %-8s %4s %5s %5s %6s %-9s  %s%n", "plane", "class", "at", "eff", "bombs", "acc", "strike", "air", "last"));
        for (var p : v.planes())
            sb.append(String.format("#%-4d %-16s %-8s %3.0f%% %5.0f %4.0f%% %6.0f %-9s  %s%n", p.id(), p.cls(), rel(p.relative()), p.efficiency(),
                    p.load(), p.accuracy(), Math.floor(p.reach()), p.intercept() ? "fighter" : p.escort() ? "escort" : "",
                    (p.orbit() != null ? (p.orbit().equals("geosync") ? "geostationary" : "in orbit") + (p.ready() ? "; " : ", reports next update; ") : "")
                    + (p.nuke() != 0 ? "warhead #" + p.nuke() + " aboard; " : "") + (p.aboard() != 0 ? "aboard ship #" + p.aboard() + "; " : "") + (p.opRelative() == null ? "" : "air defence within " + p.radius() + " of " + rel(p.opRelative()) + "; ") + (p.note() == null ? "" : p.note())));
        sb.insert(0, over);
        return sb.append("strike: hexes there and back again. fighter: rises against raids at war, and escorts. bomb PLANE x,y [strategic] [escort E,E] · recon PLANE x,y [escort E,E] · mission PLANE air x,y [RADIUS] · fly / drop / paradrop PLANES x,y … · launch PLANE x,y · satellite PLANE").toString();
    }

    /**
     * The market (issue #141; KNOWN mark.c): with nothing named, the cheapest lot of each commodity; with a commodity,
     * every lot of it; with {@code all}, every lot. Your own lots and bids show their sectors.
     */
    static String market(CountryView v, String which) {
        List<CountryView.LotView> lots = v.market() == null ? List.of() : v.market();
        if (lots.isEmpty()) return "the market is empty — sell COMMODITY SECTOR N PRICE puts a lot up from a harbour or warehouse";
        List<CountryView.LotView> show = new ArrayList<>();
        if (which == null) {
            Map<String, CountryView.LotView> cheapest = new TreeMap<>();
            for (var l : lots) cheapest.merge(l.commodity(), l, (a, b) -> b.price() < a.price() ? b : a);
            show.addAll(cheapest.values());
        } else for (var l : lots) if (which.equals("all") || l.commodity().equals(which)) show.add(l);
        if (show.isEmpty()) return "no " + which + " on the market";
        StringBuilder sb = new StringBuilder(String.format("%-4s %-6s %10s %10s %-14s %-14s %5s  %s%n", "lot", "what", "amount", "price", "seller", "high bid", "sells", "yours"));
        for (var l : show)
            sb.append(String.format("%-4d %-6s %10.0f %10.2f %-14s %-14s %5s  %s%n", l.id(), l.commodity(), l.amount(), l.price(), l.seller(), l.bidder() == null ? "-" : l.bidder(),
                    l.bidder() == null ? "-" : "in " + l.updatesLeft(), l.yours() ? "from " + rel(l.fromRelative()) : l.yourBid() ? "your bid, to " + rel(l.destRelative()) : ""));
        return sb.append(which == null ? "the cheapest of each; market COMMODITY or market all for every lot. " : "")
                 .append("buy LOT PRICE SECTOR bids a unit's price; a lot sells to its high bidder when its time is up").toString();
    }

    /** Ships, planes and units for sale (issue #141; KNOWN trdsub.c's trade report). Public. */
    static String trades(CountryView v) {
        List<CountryView.TradeView> lots = v.trades() == null ? List.of() : v.trades();
        if (lots.isEmpty()) return "nothing for sale — set ship|plane|unit IDS PRICE puts something up";
        StringBuilder sb = new StringBuilder(String.format("%-5s %10s %-14s %-14s %5s  %s%n", "lot", "price", "seller", "high bid", "sells", "what"));
        for (var l : lots) {
            String cargo = l.cargo().isEmpty() ? "" : " [" + String.join(" ", l.cargo().entrySet().stream().map(e -> e.getKey() + ":" + Math.round(e.getValue())).toList()) + "]";
            sb.append(String.format("T%-4d %10.0f %-14s %-14s %5s  %s #%d %s, tech %.0f, %.0f%%%s%s%n", l.id(), l.price(), l.seller(), l.bidder() == null ? "-" : l.bidder(),
                    l.bidder() == null ? "-" : "in " + l.updatesLeft(), l.kind(), l.item(), l.cls().replace('_', ' '), l.tech(), l.efficiency(), cargo,
                    l.yours() ? " (yours)" : l.yourBid() ? " (your bid" + (l.destRelative() == null ? ")" : ", to " + rel(l.destRelative()) + ")") : ""));
        }
        return sb.append("trade LOT PRICE [SECTOR] bids, in whole dollars, more than the price; a lot sells to its high bidder when its time is up").toString();
    }

    /** Sectors with unrest (issue #72): disloyal, not all at work, occupied, or with guerrillas; and the happiness they want. */
    static String unrest(CountryView v) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (var s : v.sectors()) {
            var u = s.unrest();
            if (u == null || (u.loyalty() == 0 && u.work() == 100 && u.peopleOf() == null && u.che() == 0)) continue;
            if (n++ == 0) sb.append(String.format("%-8s %-20s %7s %5s %4s  %s%n", "sect", "des", "loyalty", "work", "che", "people of"));
            sb.append(String.format("%-8s %-20s %7d %4d%% %4d  %s%n", rel(s.relative()), s.designation(), u.loyalty(), u.work(), u.che(), u.peopleOf() == null ? "" : u.peopleOf()));
        }
        if (n == 0) return "no unrest: every sector is loyal and at work";
        return sb.append(n).append(n == 1 ? " sector" : " sectors").append(" (loyalty 0 is loyal; above ").append(v.sectors().stream().map(CountryView.SectorView::unrest).filter(java.util.Objects::nonNull).findFirst().map(CountryView.UnrestView::disloyalAbove).orElse(65))
                .append(" civilians stop working and may revolt; anti SECTOR sends the garrison after guerrillas)").toString();
    }

    /** One ship's running manifest (issue #244): what she has caught, mined, cruised and delivered since she was built. */
    static String manifest(CountryView v, long ship) {
        var s = v.ships().stream().filter(x -> x.id() == ship).findFirst().orElse(null);
        if (s == null) return "no ship #" + ship + " of yours";
        StringBuilder sb = new StringBuilder("ship #" + s.id() + " " + s.cls() + (s.name() == null || s.name().isBlank() ? "" : " \"" + s.name() + "\"") + ", since she was built:\n");
        if (s.manifest().isEmpty()) return sb.append("  nothing yet").toString();
        for (var e : s.manifest().entrySet()) sb.append(String.format("  %-22s %,14.0f%n", e.getKey(), e.getValue()));
        return sb.toString();
    }

    /** Every ship's manifest on a line, busiest first. */
    static String manifests(CountryView v) {
        if (v.ships().isEmpty()) return "no ships";
        StringBuilder sb = new StringBuilder();
        v.ships().stream().sorted(java.util.Comparator.comparingDouble((CountryView.ShipView s) -> -s.manifest().values().stream().mapToDouble(Double::doubleValue).sum()).thenComparingLong(CountryView.ShipView::id))
                .forEach(s -> sb.append(String.format("#%-4d %-24s %s%n", s.id(), s.cls(), s.manifest().isEmpty() ? "nothing yet"
                        : String.join(" · ", s.manifest().entrySet().stream().map(e -> String.format("%s %,.0f", e.getKey(), e.getValue())).toList()))));
        return sb.toString();
    }

    /** What radar and the lookouts have: where a ship was last seen, not where it is now (issue #75). */
    static String contacts(CountryView v) {
        if (v.contacts().isEmpty()) return "no contacts — nothing of anyone else's is on the plot";
        StringBuilder sb = new StringBuilder(String.format("%-8s %-10s %-24s %-9s %s%n", "at", "country", "class", "band", "seen"));
        for (var c : v.contacts())
            sb.append(String.format("%-8s %-10s %-24s %-9s %s%n", rel(c.relative()), c.ownerName() == null ? "?" : c.ownerName(),
                    c.cls() == null ? "?" : c.cls(), c.band(), c.age() == 0 ? "this update" : c.age() + (c.age() == 1 ? " update ago" : " updates ago")));
        return sb.toString();
    }

    /**
     * One line per owned sector (issue #156): the stocks, then {@code days} — how many updates the
     * food lasts at what the people here eat, ∞ when they live off the land — and any standing
     * delivery orders, so a self-starving pipe or a stalled road shows in the table and not only in
     * the ack that set it.
     */
    static String census(CountryView v, GameConfig cfg) { return census(v, cfg, Map.of(), List.of()); }

    /**
     * The events that cost a country sectors, for the census to say so (issue #268), over a window an hourly look
     * still catches: game 82's partisans took a sector every few updates, and a player checks every dozen or so.
     */
    private static final Set<String> LOSSES = Set.of("partisans", "revolt");
    static final int LOSS_WINDOW = 24;

    /**
     * {@code notes}: the last update's notes for your sectors, by relative "x,y" — where the reasons a sector stalled
     * are. {@code losses}: what revolts and partisans did to you over the last {@link #LOSS_WINDOW} updates, already worded.
     */
    static String census(CountryView v, GameConfig cfg, Map<String, List<String>> notes, List<String> losses) {
        // pet, gun and shell too (issue #196): a fleet runs on them, and they were only in the view's JSON
        StringBuilder sb = new StringBuilder(String.format("%-8s %-3s %-4s %4s %4s %6s %5s %6s %6s %6s %6s %6s %6s %5s %5s %5s  %s%n", "sect", "des", "eff", "mob", "road", "civ", "cap", "mil", "food", "iron", "lcm", "hcm", "pet", "gun", "shell", "days", "deliver"));
        double[] totals = new double[4];   // pet, gun, shell, oil across the country
        List<String> stalled = new ArrayList<>();
        double popScale = cfg.economy().population().maxPopResearchCurve().eval(v.levels().research());
        for (SectorView s : v.sectors()) {
            if (!s.full()) continue;
            var st = cfg.hasSectorType(s.designation()) ? cfg.sectorType(s.designation()) : null;
            double cap = st == null ? 0 : st.maxPopulation() * popScale;
            double eats = org.hastingtx.empire.engine.update.FoodMath.eatsPerUpdate(cfg, s.stock().getOrDefault("civ", 0.0), s.stock().getOrDefault("mil", 0.0), s.stock().getOrDefault("uw", 0.0),
                    s.resources() == null ? 0 : s.resources().fertility(), !"ocean".equals(s.terrain()));
            double food = s.stock().getOrDefault("food", 0.0);
            String days = eats <= 0 ? "∞" : food / eats >= 100 ? "99+" : String.format("%.0f", food / eats);
            StringBuilder del = new StringBuilder();
            s.deliveries().forEach((c, d) -> del.append(del.isEmpty() ? "" : " ").append(c).append("→").append(d.dir()).append(">").append(Math.round(d.threshold())));
            double pet = s.stock().getOrDefault("pet", 0.0), gun = s.stock().getOrDefault("gun", 0.0), shell = s.stock().getOrDefault("shell", 0.0);
            totals[0] += pet; totals[1] += gun; totals[2] += shell; totals[3] += s.stock().getOrDefault("oil", 0.0);
            sb.append(String.format("%-8s %-3s %4.0f %4.0f %4.0f %6.0f %5.0f %6.0f %6.0f %6.0f %6.0f %6.0f %6.0f %5.0f %5.0f %5s  %s%n", s.relative().x() + "," + s.relative().y(), glyph(s), s.efficiency(), s.mobility(), s.roadLevel(),
                    s.stock().getOrDefault("civ", 0.0), cap, s.stock().getOrDefault("mil", 0.0), food, s.stock().getOrDefault("iron", 0.0), s.stock().getOrDefault("lcm", 0.0), s.stock().getOrDefault("hcm", 0.0), pet, gun, shell, days, del));
            String r = shortForOnePoint(cfg, s, true), l = shortForOnePoint(cfg, s, false);
            if (r != null) stalled.add(rel(s.relative()) + " road→" + Math.round(s.roadTarget()) + " " + r);
            if (l != null) stalled.add(rel(s.relative()) + " rail→" + Math.round(s.railTarget()) + " " + l);
        }
        // a standing order that cannot lay a point looks exactly like a finished one; say which are waiting (issue #150)
        if (!stalled.isEmpty()) sb.append("waiting for materials: ").append(String.join("; ", stalled)).append('\n');
        // a sector that vanished between updates, and why (issue #268): partisans in a sector with no soldiers
        if (!losses.isEmpty())
            sb.append("in the last ").append(LOSS_WINDOW).append(" updates: ").append(String.join("; ", losses.subList(Math.max(0, losses.size() - 10), losses.size()))).append(losses.size() > 10 ? " (and " + (losses.size() - 10) + " before)" : "")
              .append(" — guerrillas grow where there are no soldiers").append(cfg.economy().enlist() == null ? "" : "; enlist SECTOR -N puts a garrison in").append('\n');
        // your land mines (issue #71): only you know where they are
        List<String> mined = new ArrayList<>();
        for (SectorView s : v.sectors()) if (s.full() && s.mines() > 0) mined.add(rel(s.relative()) + " " + s.mines());
        if (!mined.isEmpty()) sb.append("land mines: ").append(String.join("; ", mined)).append('\n');
        // a sector short of efficiency, and why (issue #275): a well-stocked city sat at 0% with nothing on this page to say so
        List<String> held = heldBack(v, notes);
        if (!held.isEmpty()) {
            sb.append("held back: ").append(String.join("; ", held.subList(0, Math.min(15, held.size()))));
            if (held.size() > 15) sb.append("; … and ").append(held.size() - 15).append(" more");
            sb.append('\n');
        }
        sb.append(String.format("country: pet %.0f · gun %.0f · shell %.0f · oil %.0f%n", totals[0], totals[1], totals[2], totals[3]));
        // how far each radar station sees (Richard 2026-09-15: "A radar should note the radius of its vision")
        List<String> radars = new ArrayList<>();
        for (SectorView s : v.sectors()) if (s.full() && s.radarRange() >= 1) radars.add(rel(s.relative()) + " sees " + (int) Math.floor(s.radarRange()) + " hexes");
        if (!radars.isEmpty()) sb.append("radar: ").append(String.join("; ", radars)).append('\n');
        sb.append("cap: the population ceiling here").append(popScale == 1.0 ? "" : String.format(" (research %.0f → ×%.2f of the flat cap)", v.levels().research(), popScale))
          .append("; days: updates the food lasts at what the people here eat (∞ = they live off the land); census res for the ground; food for basins and deficits\n");
        return sb.toString();
    }

    /**
     * Each sector of yours below 100% with a reason it is not growing, one entry each: what the last update found it
     * short of, and any unrest — disloyal, not all at work, guerrillas, a conquered people (issue #275).
     */
    static List<String> heldBack(CountryView v, Map<String, List<String>> notes) {
        List<String> out = new ArrayList<>();
        for (SectorView s : v.sectors()) {
            if (!s.full() || s.owner() != v.countryId() || s.efficiency() >= 100) continue;
            List<String> why = new ArrayList<>();
            for (String line : notes.getOrDefault(rel(s.relative()), List.of())) if (line.startsWith("shortage: ")) why.add("short of " + line.substring("shortage: ".length()));
            var u = s.unrest();
            if (u != null) {
                if (u.loyalty() > u.disloyalAbove()) why.add("disloyal (loyalty " + u.loyalty() + ", above " + u.disloyalAbove() + ")");
                if (u.work() < 100) why.add(u.work() + "% at work");
                if (u.che() > 0) why.add(u.che() + " guerrillas");
                if (u.peopleOf() != null) why.add("people of " + u.peopleOf());
            }
            if (!why.isEmpty()) out.add(rel(s.relative()) + " " + glyph(s) + " " + Math.round(s.efficiency()) + "%: " + String.join(", ", why));
        }
        return out;
    }

    /** The ground under each owned sector (issue #156): the five endowments, and which gated designations it is poor for. */
    static String censusResources(CountryView v, GameConfig cfg) {
        double poor = cfg.economy().poorGroundBelowOrDefault();
        StringBuilder sb = new StringBuilder(String.format("%-8s %-3s %-10s %4s %4s %4s %4s %4s  %s%n", "sect", "des", "terrain", "fert", "min", "gold", "oil", "uran", "poor ground for"));
        for (SectorView s : v.sectors()) {
            if (!s.full() || s.resources() == null) continue;
            var r = s.resources();
            List<String> poorFor = new ArrayList<>();
            for (var t : cfg.economy().sectorTypes()) {
                if (t.resourceGate() == null || t.produces().isEmpty() || t.hasFlag("no_designate")) continue;
                if (t.terrainRequired() != null && !t.terrainRequired().contains(s.terrain())) continue;
                int val = switch (t.resourceGate()) { case "fertility" -> r.fertility(); case "minerals" -> r.minerals(); case "gold" -> r.gold(); case "oil" -> r.oil(); case "uranium" -> r.uranium(); default -> 100; };
                if (val < poor) poorFor.add(t.id());
            }
            sb.append(String.format("%-8s %-3s %-10s %4d %4d %4d %4d %4d  %s%n", rel(s.relative()), glyph(s), s.terrain(), r.fertility(), r.minerals(), r.gold(), r.oil(), r.uranium(), String.join(", ", poorFor)));
        }
        sb.append("poor ground: a gated designation below ").append(Math.round(poor)).append(" makes that percent of what a hex at 100 would\n");
        return sb.toString();
    }

    /** Basins and deficits, then a deliver hint for each deficit (issue #160). */
    static String food(org.hastingtx.empire.engine.update.FoodReport.Result r) {
        StringBuilder sb = new StringBuilder();
        if (r.deficits().isEmpty()) sb.append("no sector loses food next update\n");
        else {
            sb.append("DEFICIT (losing food next update; worst first)\n");
            for (var l : r.deficits())
                sb.append(String.format("  %-8s %-18s food %6.0f → %6.0f  eats %5.0f  %s%n", rel(l.relative()), l.designation(), l.foodNow(), l.foodAfter(), l.eats(),
                        l.starving() ? "STARVING next update" : Double.isNaN(l.updatesLeft()) ? "" : String.format("~%.0f more updates", l.updatesLeft())));
        }
        if (r.basins().isEmpty()) sb.append("no sector gains food next update — nothing to move; grow some (agribusiness on fertile ground, or fish)\n");
        else {
            sb.append("BASINS (gaining food next update; biggest first)\n");
            for (var l : r.basins()) sb.append(String.format("  %-8s %-18s food %6.0f → %6.0f  (+%.0f)%n", rel(l.relative()), l.designation(), l.foodNow(), l.foodAfter(), l.delta()));
        }
        if (!r.hints().isEmpty()) {
            sb.append("HINTS — nearest basin to each deficit, and the first hop toward it\n");
            for (var h : r.hints())
                sb.append(String.format("  %-8s ← %-8s %d hex%s: deliver food %s %s N   (or move food %s %s N, or dist %s to a warehouse that has some)%n",
                        rel(h.deficit()), rel(h.basin()), h.distance(), h.distance() == 1 ? "" : "es", rel(h.basin()), h.dir(), rel(h.basin()), rel(h.deficit()), rel(h.deficit())));
        }
        return sb.toString();
    }

    /** "needs 3 more lcm" when a sector's road (or rail) order is above its level and one point is not affordable from its own stock; null otherwise. */
    static String shortForOnePoint(GameConfig cfg, SectorView s, boolean road) {
        double target = road ? s.roadTarget() : s.railTarget(), level = road ? s.roadLevel() : s.railLevel();
        if (target <= level + 1e-9) return null;
        var per = road ? cfg.infrastructure().road().buildMaterialsPerPoint() : cfg.infrastructure().rail().buildMaterialsPerPoint();
        var mults = road ? cfg.infrastructure().road().costMultiplierByTerrain() : cfg.infrastructure().rail().costMultiplierByTerrain();
        Double mult = mults == null ? null : mults.get(s.terrain());
        double m = mult == null ? 1 : mult;
        StringBuilder need = new StringBuilder();
        for (var e : per.entrySet()) {
            if (e.getKey().equals("cash") || e.getValue() * m <= 0) continue;
            double avail = s.stock().getOrDefault(e.getKey(), 0.0), want = e.getValue() * m;
            if (avail < want) need.append(need.isEmpty() ? "needs " : " and ").append((long) Math.ceil(want - avail)).append(" more ").append(e.getKey());
        }
        return need.isEmpty() ? null : need.toString();
    }

    private static String glyph(SectorView s) { return s.designation().length() > 3 ? s.designation().substring(0, 3) : s.designation(); }

    /**
     * The command syntax, read from the player guide rather than held here (issue #118). The guide's
     * {@code commands.md} owns the text and is packaged onto the classpath at {@code help/}; this
     * prints the first fenced block from it, which is the quick reference. One source, two
     * presentations — a command added to one cannot go missing from the other.
     */
    static final String HELP = loadHelp();

    private static String loadHelp() {
        try (java.io.InputStream in = Console.class.getClassLoader().getResourceAsStream("help/commands.md")) {
            if (in == null) return "the command reference is missing from this build";
            String md = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int open = md.indexOf("```text");
            if (open < 0) return "the command reference has no quick-reference block";
            int from = md.indexOf('\n', open) + 1;
            int to = md.indexOf("```", from);
            return to < 0 ? md.substring(from) : md.substring(from, to).stripTrailing();
        } catch (java.io.IOException e) {
            return "the command reference could not be read: " + e.getMessage();
        }
    }
}
