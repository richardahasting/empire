package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.model.Coord;

/**
 * Every player action, from the console, the web UI, or an agent. One record per verb.
 * {@code verb()} is the BTU cost key in economy.btu.cost_by_command.
 */
public sealed interface Command permits
        Command.BreakSanctuary, Command.Designate, Command.Threshold, Command.Distribute,
        Command.Move, Command.Explore, Command.BuildRoad, Command.BuildRail, Command.RailShip, Command.Deliver,
        Command.BuildShip, Command.Sail, Command.Load, Command.Unload, Command.Lane, Command.Scrap, Command.Fish, Command.Mine, Command.Supply, Command.Fire, Command.Mission, Command.Land, Command.Demobilize, Command.Attack, Command.Anti, Command.BuildUnit, Command.March, Command.LoadUnit, Command.Board, Command.Sabotage, Command.Incite, Command.UnitFire, Command.Work, Command.BuildPlane, Command.Bomb, Command.Recon,
        Command.RailLane, Command.Telegram, Command.Announce, Command.DeclareWar, Command.OfferPeace {

    String verb();

    record BreakSanctuary() implements Command { public String verb() { return "break_sanctuary"; } }

    record Designate(Coord sector, String type) implements Command { public String verb() { return "designate"; } }

    /**
     * Stand military down in a sector (issue #217): {@code qty} of them leave the service, or with
     * {@code keep}, all but {@code qty}. They become civilians where the sector has room; the rest go home.
     */
    record Demobilize(Coord sector, double qty, boolean keep) implements Command { public String verb() { return "demobilize"; } }

    /** Set (or clear with a negative amount) the distribution threshold for one commodity. */
    record Threshold(Coord sector, String commodity, double amount) implements Command { public String verb() { return "threshold"; } }

    /** Standing delivery order (KNOWN: deliver.c): above {@code threshold}, push {@code commodity} one hex in {@code dir} (0..5, see Hex.DIR_NAMES) every update; null dir clears. */
    record Deliver(Coord sector, String commodity, Integer dir, double threshold) implements Command { public String verb() { return "deliver"; } }

    /** Name a sector's distribution centre; null clears it. */
    record Distribute(Coord sector, Coord center) implements Command { public String verb() { return "distribute"; } }

    /** Queue a manual move, executed at the next update under the range-and-hold rule. */
    record Move(Coord from, Coord to, String commodity, double qty) implements Command { public String verb() { return "move"; } }

    /** Standing order: build this sector's road toward {@code targetLevel} (0..100) over the coming updates; 0 cancels. */
    record BuildRoad(Coord sector, double targetLevel) implements Command { public String verb() { return "build_road"; } }

    /** Standing order: lay rail in this sector toward {@code targetLevel}; 0 cancels. Needs the tech. */
    record BuildRail(Coord sector, double targetLevel) implements Command { public String verb() { return "build_rail"; } }

    /** Ship by rail between two depots. Connectivity is checked now; the train runs at the update. */
    record RailShip(Coord from, Coord to, String commodity, double qty) implements Command { public String verb() { return "rail_ship"; } }
    /**
     * A standing depot-to-depot run (issue #70). An empty {@code cargo} keeps the far end's thresholds
     * topped up; a list pushes those commodities. {@code clear} cancels the lane.
     */
    record RailLane(Coord from, Coord to, java.util.List<String> cargo, boolean clear) implements Command { public String verb() { return "rail_lane"; } }

    // ---- ships (issue #56) ----
    /** Lay a hull of {@code cls} in your harbour at {@code harbor}; materials and cash are paid now. */
    record BuildShip(Coord harbor, String cls, String name) implements Command { public String verb() { return "build_ship"; } }
    /** Sail to a sea hex or one of your harbours; the route is checked now, the voyage runs at the updates. Null dest holds. */
    record Sail(long ship, Coord dest) implements Command { public String verb() { return "sail"; } }
    /** Move goods from the harbour the ship is in into its hold. Immediate. */
    record Load(long ship, String commodity, double qty) implements Command { public String verb() { return "load"; } }
    /** Move goods from the hold into the harbour the ship is in. Immediate. */
    record Unload(long ship, String commodity, double qty) implements Command { public String verb() { return "unload"; } }
    /** Standing order: shuttle between two of your harbours carrying {@code cargo} (empty = whatever it may carry); null from clears. */
    record Lane(long ship, Coord from, Coord to, java.util.List<String> cargo) implements Command { public String verb() { return "lane"; } }
    /** Fishing mission: roam the grounds near {@code home} (null = the harbour it is in), fish, land the catch there, repeat. {@code off} clears. */
    record Fish(long ship, Coord home, boolean off) implements Command { public String verb() { return "fish"; } }

    /** Issue #112: work the nodule fields near {@code home}, mine, land the ore, repeat. */
    record Mine(long ship, Coord home, boolean off) implements Command { public String verb() { return "mine"; } }

    /** Issue #67: fill your harbours' thresholds from whichever harbour can spare it; {@code home} (null = the harbour it is in) is where she refits. */
    record Supply(long ship, Coord home, boolean off) implements Command { public String verb() { return "supply"; } }

    /** Issue #68: fire on a ship you can see at {@code at} ({@code cls} picks one when several are there). Resolves now, with return fire. */
    record Fire(long ship, Coord at, String cls) implements Command { public String verb() { return "fire"; } }

    /** Issue #193: an assault ship puts everyone aboard ashore on the unowned land sector {@code at}, next to her. */
    record Land(long ship, Coord at) implements Command { public String verb() { return "land"; } }

    /** Raise a land unit in a headquarters (issue #247; KNOWN commands/buil.c build_land). */
    record BuildUnit(Coord sector, String cls) implements Command { public String verb() { return "build_unit"; } }
    /** March a land unit through your own land (issue #247; commands/marc.c). */
    record March(long unit, Coord to) implements Command { public String verb() { return "march"; } }
    /** A land unit takes on ({@code unload} false) or puts down what its sector has (issue #247; KNOWN lload, lunload). */
    record LoadUnit(long unit, String commodity, double qty, boolean unload) implements Command { public String verb() { return unload ? "lunload" : "lload"; } }

    /** A land unit goes aboard a ship, or ashore from her ({@code ship} 0) — issue #252; KNOWN load.c, lnd_land. */
    record Board(long unit, long ship) implements Command { public String verb() { return ship == 0 ? "ashore" : "board"; } }

    /** A plane is laid down on an airfield of yours (issue #262). */
    record BuildPlane(Coord at, String cls) implements Command { public String verb() { return "build_plane"; } }

    /** A bombing sortie (issue #262); pinpoint aims at what is in the sector, strategic at the sector itself. */
    record Bomb(long plane, Coord at, boolean pinpoint) implements Command { public String verb() { return "bomb"; } }

    /** A reconnaissance sortie (issue #262). */
    record Recon(long plane, Coord at) implements Command { public String verb() { return "recon"; } }

    /** An engineer spends its mobility building the sector it stands in (issue #258); mobility 0 means all it has. */
    record Work(long unit, double mobility) implements Command { public String verb() { return "work"; } }

    /** Artillery throws a salvo at a sector within its range (issue #256). */
    record UnitFire(long unit, Coord at) implements Command { public String verb() { return "unit_fire"; } }

    /** A spy blows up what the sector it stands in was keeping (issue #254). */
    record Sabotage(long unit) implements Command { public String verb() { return "sabotage"; } }

    /** A spy turns the sector it stands in against its owner (issue #254, NEW). */
    record Incite(long unit) implements Command { public String verb() { return "incite"; } }

    /** Send a sector's garrison after the guerrillas in it (issue #72; KNOWN commands/anti.c). */
    record Anti(Coord sector) implements Command { public String verb() { return "anti"; } }

    /** Military from sectors of yours next to an enemy sector take it by force, at war (issue #236, #71 slice 1). */
    record Attack(Coord target, java.util.List<Party> parties, java.util.List<Long> units) implements Command {
        public Attack { units = units == null ? java.util.List.of() : java.util.List.copyOf(units); }
        /** Military only, no land units. */
        public Attack(Coord target, java.util.List<Party> parties) { this(target, parties, java.util.List.of()); }
        public String verb() { return "attack"; }
        /** {@code mil} soldiers from {@code from}. */
        public record Party(Coord from, double mil) {}
    }

    /**
     * Issue #68: a military mission — {@code kind} is patrol (points = the route), search, escort
     * ({@code ward} = the ship to stay with), blockade or interdict (points = the station). {@code off} clears.
     * The verb is the kind, so each can carry its own BTU cost.
     */
    record Mission(long ship, String kind, java.util.List<Coord> points, long ward, boolean off) implements Command { public String verb() { return kind; } }

    /**
     * Declare war on a country (issue #137). Unilateral to do, mutual in effect: the other side is at
     * war with you whether they like it or not, and their ships will defend themselves.
     */
    record DeclareWar(int on) implements Command { public String verb() { return "declare_war"; } }

    /**
     * Offer to stop (issue #137). Peace needs both: a war you can end alone costs nothing to start.
     * Offering when the other side has already offered is what actually ends it.
     */
    record OfferPeace(int with) implements Command { public String verb() { return "offer_peace"; } }

    /**
     * A private message to one country (issue #140). The engine validates and charges for it; the
     * message itself is not world state and is stored by the server — an update must not depend on
     * what anybody said, and a state hash must not change because somebody was rude.
     */
    record Telegram(int to, String body) implements Command { public String verb() { return "telegram"; } }

    /** The same, to everybody in the game. */
    record Announce(String body) implements Command { public String verb() { return "announce"; } }

    /** Break the ship up in harbour; the hold goes ashore. */
    record Scrap(long ship) implements Command { public String verb() { return "scrap"; } }

    /** Claim an adjacent unowned land sector by moving civilians into it. Immediate. */
    record Explore(Coord from, Coord to, double civs) implements Command { public String verb() { return "explore"; } }
}
