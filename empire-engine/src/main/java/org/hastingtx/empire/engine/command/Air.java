package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.steps.UnrestStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Planes (issue #262, #71 slice 3a), as the original flew them: built on an airfield (KNOWN sect.config {@code *},
 * commands/buil.c), flown as a sortie that takes its petrol and its bombs off that field (KNOWN subs/plnsub.c
 * {@code pln_equip}), shot at by the flak of what it flies against (KNOWN subs/aircombat.c), and bombing by
 * {@code pln_damage}.
 *
 * <p>A plane's range is the round trip, so it strikes at half of it. It comes home to its own field the same turn.
 * On the way, the fighters of any country at war with you whose land it crosses rise against it, and the escorts it
 * took fight them first (issue #71, slice 3b; KNOWN aircombat.c {@code ac_encounter}, {@code ac_dog}).
 */
final class Air {
    private Air() {}

    private static String q(double v) { return Ledger.q(v); }

    /** KNOWN commands/buil.c build_plane: laid down on an airfield at a tenth of its materials and cash. */
    static CommandResult build(GameConfig cfg, Commodities com, World w, Country c, Command.BuildPlane b) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        if (pc == null) return CommandResult.fail(w, "this world has no planes");
        if (c.inSanctuary()) return CommandResult.fail(w, "break sanctuary first");
        if (b.at() == null || !w.inBounds(b.at())) return CommandResult.fail(w, "build where?");
        Sector s = w.sector(b.at());
        if (s.owner() != c.id()) return CommandResult.fail(w, b.at() + " is not yours");
        if (!cfg.sectorType(s.designation()).hasFlag("builds_planes")) return CommandResult.fail(w, b.at() + " is not an airfield; designate one first");
        UnitsCfg.PlaneClassCfg cls = pc.planeClass(b.cls());
        if (cls == null) return CommandResult.fail(w, "unknown plane class: " + b.cls());
        if (c.levels().tech() < cls.techRequired()) return CommandResult.fail(w, article(cls.name()) + " needs tech " + q(cls.techRequired()));

        double share = pc.startEfficiency() / 100.0;
        Stocks st = s.stock();
        double cash = 0;
        StringBuilder used = new StringBuilder();
        for (var e : cls.build().entrySet()) {
            double want = Math.ceil(e.getValue() * share);
            if (e.getKey().equals("cash")) {
                if (c.cash() < want) return CommandResult.fail(w, "it costs $" + q(want) + " to lay down and you have $" + q(c.cash()));
                cash = want;
            } else {
                int ci = com.index(e.getKey());
                if (st.get(ci) < want) return CommandResult.fail(w, b.at() + " has " + q(st.get(ci)) + " " + e.getKey() + "; it takes " + q(want));
                st = st.plus(ci, -want);
            }
            used.append(used.isEmpty() ? "" : ", ").append(e.getKey().equals("cash") ? "$" + q(want) : q(want) + " " + e.getKey());
        }
        Plane p = new Plane(w.nextPlaneId(), c.id(), cls.id(), b.at(), pc.startEfficiency(), c.levels().tech(), w.updateNumber(), "");
        World next = w.withSector(s.withStock(st)).withCountry(c.withCash(c.cash() - cash)).withPlane(p);
        return new CommandResult(next, null, 0, article(cls.name()) + " #" + p.id() + " is on the field at " + b.at()
                + " at " + q(pc.startEfficiency()) + "% for " + used + "; it fits out there");
    }

    /** What a sortie needs off the field, and what it costs when it flies. */
    private record Sortie(CommandResult fail, Plane plane, UnitsCfg.PlaneClassCfg cls, Sector field, Sector target, double bombs) {}

    private static Sortie ready(GameConfig cfg, Commodities com, World w, Country c, long id, Coord at, boolean bombing) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        if (pc == null) return new Sortie(CommandResult.fail(w, "this world has no planes"), null, null, null, null, 0);
        Plane p = w.plane(id);
        if (p == null || p.owner() != c.id()) return new Sortie(CommandResult.fail(w, "no plane #" + id + " of yours"), null, null, null, null, 0);
        UnitsCfg.PlaneClassCfg cls = pc.planeClass(p.cls());
        if (cls == null) return new Sortie(CommandResult.fail(w, "plane #" + id + " has no class in these rules"), null, null, null, null, 0);
        if (p.efficiency() < pc.minEfficiency()) return new Sortie(CommandResult.fail(w, "plane #" + id + " is wreckage at " + q(p.efficiency()) + "%"), null, null, null, null, 0);
        if (at == null || !w.inBounds(at)) return new Sortie(CommandResult.fail(w, "fly where?"), null, null, null, null, 0);
        Sector field = w.sector(p.at());
        if (field.owner() != c.id()) return new Sortie(CommandResult.fail(w, "plane #" + id + " is on a field that is no longer yours"), null, null, null, null, 0);
        double reach = cls.reachAt(p.tech());
        int dist = Hex.distance(w, p.at(), at);
        if (dist > reach) return new Sortie(CommandResult.fail(w, at + " is " + dist + " hexes off; " + article(cls.name())
                + " flies " + q(Math.floor(cls.rangeAt(p.tech()))) + " there and back, so it strikes at " + q(Math.floor(reach))), null, null, null, null, 0);
        int pet = com.index("pet");
        if (field.stock().get(pet) < cls.fuel()) return new Sortie(CommandResult.fail(w, p.at() + " has "
                + q(field.stock().get(pet)) + " petrol; the sortie takes " + q(cls.fuel())), null, null, null, null, 0);
        double bombs = 0;
        if (bombing) {
            if (cls.loadAt(p.tech()) < 1) return new Sortie(CommandResult.fail(w, article(cls.name()) + " carries no bombs"), null, null, null, null, 0);
            bombs = Math.min(cls.loadAt(p.tech()), Math.floor(field.stock().get(com.index("shell"))));
            if (bombs < 1) return new Sortie(CommandResult.fail(w, p.at() + " has no shells to bomb with"), null, null, null, null, 0);
        }
        return new Sortie(null, p, cls, field, w.sector(at), bombs);
    }

    /**
     * KNOWN plnsub.c pln_damage and commands/bomb.c: {@code roll(load)+1} bombs, each {@code roll(6)} and then 8 on a
     * one-in-ten roll, 5 on a hit by the plane's accuracy, else 1; doubled when the plane is the right kind for the
     * job — a tactical bomber on a pinpoint raid, a bomber on a strategic one. The sector takes it as sect_damage.
     */
    static CommandResult bomb(GameConfig cfg, Commodities com, World w, Country c, Command.Bomb b) {
        Sortie so = ready(cfg, com, w, c, b.plane(), b.at(), true);
        if (so.fail() != null) return so.fail();
        Sector target = so.target();
        if (target.owner() == c.id()) return CommandResult.fail(w, b.at() + " is yours");
        if (!target.owned()) return CommandResult.fail(w, b.at() + " belongs to nobody");
        String no = Assault.refused(cfg, w, c, target, "bomb");
        if (no != null) return CommandResult.fail(w, no);

        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        var bc = pc.bombing();
        Plane p = so.plane();
        UnitsCfg.PlaneClassCfg cls = so.cls();
        UnrestStep.R r = new UnrestStep.R(Rng.stream("bomb:" + p.id() + ">" + b.at() + ":" + w.updateNumber(), cfg.world() == null ? 0 : cfg.world().seed()));

        // off the field: petrol for the flight, shells for the bombs
        Stocks fs = so.field().stock().plus(com.index("pet"), -cls.fuel()).plus(com.index("shell"), -so.bombs());
        World next = w.withSector(so.field().withStock(fs));

        // the escorts take their petrol, and whoever is at war with you and under the flight path rises against it
        Escorts es = escorts(cfg, com, next, c, b.escorts(), p, b.at());
        if (es.fail() != null) return CommandResult.fail(w, es.fail());
        Raid raid = encounter(cfg, com, r, es.world(), c, p, es.planes(), b.at());
        next = raid.world();
        String air = raid.story();
        if (raid.lost()) return new CommandResult(next, null, 0, air);
        p = next.plane(p.id());

        // the flak over the target, before it can drop anything
        Flak flak = flak(cfg, com, r, next, p, cls, target);
        next = flak.world();
        if (flak.shotDown()) return new CommandResult(next, null, 0, join(air, flak.story()) + " — plane #" + p.id() + " was shot down over " + b.at());
        p = flak.plane();
        if (flak.aborted()) return new CommandResult(next, null, 0, join(air, flak.story()) + " — plane #" + p.id() + " turned back at " + q(p.efficiency()) + "%");

        boolean pinpoint = b.pinpoint();
        boolean tactical = cls.has("tactical");
        double aim = pinpoint ? 100 - cls.accuracyAt(p.tech())
                : bc.strategicAimBase() + (tactical ? cls.accuracyAt(p.tech()) : 100 - cls.accuracyAt(p.tech()));
        boolean effective = pinpoint == tactical;
        int bombs = (int) Math.min(so.bombs(), r.roll((int) so.bombs()));
        double dam = 0;
        for (int i = 0; i < bombs; i++) {
            dam += r.roll(bc.bombRoll());
            int hit = r.roll(100);
            dam += hit >= bc.blamChance() ? bc.blam() : hit < aim ? bc.hit() : bc.miss();
        }
        if (effective) dam *= bc.effectiveMultiple();
        int damage = (int) dam;
        next = next.withSector(Spy.damage(cfg, com, r, next.sector(target.at()), damage))
                   .withPlane(p.withNote("bombed " + target.at()));
        String before = join(air, flak.story());
        return new CommandResult(next, null, 0, (before.isEmpty() ? "" : before + " — ")
                + "plane #" + p.id() + " dropped " + bombs + (bombs == 1 ? " bomb" : " bombs") + " on " + b.at() + ": "
                + damage + "% of everything " + w.country(target.owner()).name() + " had there"
                + (p.efficiency() < 100 ? ", and came home at " + q(p.efficiency()) + "%" : ""));
    }

    /** KNOWN commands/reco.c: it flies over and you see the sector as it is now, flak and all. */
    static CommandResult recon(GameConfig cfg, Commodities com, World w, Country c, Command.Recon rc) {
        Sortie so = ready(cfg, com, w, c, rc.plane(), rc.at(), false);
        if (so.fail() != null) return so.fail();
        Plane p = so.plane();
        UnitsCfg.PlaneClassCfg cls = so.cls();
        UnrestStep.R r = new UnrestStep.R(Rng.stream("recon:" + p.id() + ">" + rc.at() + ":" + w.updateNumber(), cfg.world() == null ? 0 : cfg.world().seed()));
        World next = w.withSector(so.field().withStock(so.field().stock().plus(com.index("pet"), -cls.fuel())));
        Escorts es = escorts(cfg, com, next, c, rc.escorts(), p, rc.at());
        if (es.fail() != null) return CommandResult.fail(w, es.fail());
        Raid raid = encounter(cfg, com, r, es.world(), c, p, es.planes(), rc.at());
        next = raid.world();
        String air = raid.story();
        if (raid.lost()) return new CommandResult(next, null, 0, air + " — it told you nothing");
        p = next.plane(p.id());

        Sector target = next.sector(rc.at());
        Flak flak = flak(cfg, com, r, next, p, cls, target);
        next = flak.world();
        String before = join(air, flak.story());
        if (flak.shotDown()) return new CommandResult(next, null, 0, before + " — plane #" + p.id() + " was shot down over " + rc.at() + " and told you nothing");
        p = flak.plane();
        next = next.withPlane(p.withNote("flew over " + rc.at()));
        // what it saw goes onto your chart, as sight does (issue #64)
        next = remember(next, c.id(), target);
        String who = target.owned() ? next.country(target.owner()).name() : "nobody";
        String what = target.owned() && target.owner() != c.id()
                ? who + "'s " + target.designation().replace('_', ' ') + " at " + q(target.efficiency()) + "%, "
                  + q(Math.floor(target.stock().get(com.civ))) + " civilians and " + q(Math.floor(target.stock().get(com.mil))) + " soldiers"
                : target.designation().replace('_', ' ') + " at " + rc.at() + ", " + who;
        return new CommandResult(next, null, 0, (before.isEmpty() ? "" : before + " — ")
                + "plane #" + p.id() + " flew over " + rc.at() + ": " + what
                + (flak.aborted() ? "; it was hit and turned for home at " + q(p.efficiency()) + "%" : ""));
    }

    /** What the flight saw goes on this country's chart, the way sight does (issue #64). */
    private static World remember(World w, int owner, Sector s) {
        java.util.List<SeenSector> seen = new java.util.ArrayList<>(w.seen());
        seen.removeIf(x -> x.owner() == owner && x.at().equals(s.at()));
        seen.add(new SeenSector(owner, s.at(), s.terrain(), s.owner(), s.designation(), w.updateNumber()));
        return w.withSeen(seen);
    }

    private record Flak(World world, Plane plane, boolean shotDown, boolean aborted, String story) {}

    /**
     * KNOWN aircombat.c ac_doflak / ac_flak_dam: the sector's guns, at most {@code gun_max}, doubled for tech, fire
     * once; the damage is {@code (roll(8) + 2) ×} the table's multiplier for {@code guns − the plane's defence}. Below
     * the class minimum it is shot down; under 80% it may turn back.
     */
    private static Flak flak(GameConfig cfg, Commodities com, UnrestStep.R r, World w, Plane p, UnitsCfg.PlaneClassCfg cls, Sector target) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        var fc = pc.flak();
        if (target.owner() == p.owner() || !target.owned()) return new Flak(w, p, false, false, "");
        double guns = Math.min(fc.gunMax(), Math.floor(target.stock().get(com.index("gun"))));
        if (guns < 1) return new Flak(w, p, false, false, "");
        guns = r.roundavg(guns * fc.gunMultiple());
        double dam = (r.roll(fc.roll()) + fc.rollOffset()) * fc.multiplier(guns, cls.defenseAt(p.tech()), cls.has("tactical"));
        double eff = Math.max(0, p.efficiency() - dam);
        String story = "flak over " + target.at() + " took " + (int) dam + "%";
        if (eff < pc.minEfficiency()) return new Flak(w.withoutPlane(p.id()), p, true, false, story);
        Plane hit = p.withEfficiency(eff);
        boolean abort = eff < pc.abortBelow() && r.chance((pc.abortBelow() - eff) / 100.0);
        return new Flak(w.withPlane(hit), hit, false, abort, story);
    }

    private static String join(String a, String b) { return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; }

    // ------------------------------------------------------------------------------------- air to air (#71)

    private record Escorts(String fail, World world, List<Plane> planes) {}

    /**
     * The escorts a sortie takes (KNOWN plnsub.c pln_sel): fighters or escort planes of yours, fit to fly, on a field of
     * yours within {@code escort_reach} of the one the raid flies from, with the range to get there, on to the target and
     * back, and each taking its own petrol off its own field. A bad escort refuses the whole sortie, before anything flies.
     */
    private static Escorts escorts(GameConfig cfg, Commodities com, World w, Country c, List<Long> ids, Plane lead, Coord target) {
        if (ids.isEmpty()) return new Escorts(null, w, List.of());
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        UnitsCfg.AirCombatCfg ac = pc.airCombat();
        if (ac == null) return new Escorts("these rules have no air combat, so no escorts", w, List.of());
        int pet = com.index("pet");
        List<Plane> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int leg = Hex.distance(w, lead.at(), target);
        for (long id : ids) {
            Plane e = w.plane(id);
            if (e == null || e.owner() != c.id()) return new Escorts("no plane #" + id + " of yours to fly escort", w, List.of());
            if (id == lead.id() || !seen.add(id)) return new Escorts("plane #" + id + " is named twice", w, List.of());
            UnitsCfg.PlaneClassCfg cls = pc.planeClass(e.cls());
            if (cls == null || !(cls.has("intercept") || cls.has("escort"))) return new Escorts("plane #" + id + " cannot fly escort; fighters and escort planes can", w, List.of());
            if (e.efficiency() < ac.minEfficiency()) return new Escorts("plane #" + id + " is at " + q(e.efficiency()) + "%; an escort needs " + q(ac.minEfficiency()) + "%", w, List.of());
            Sector field = w.sector(e.at());
            if (field.owner() != c.id()) return new Escorts("plane #" + id + " is on a field that is no longer yours", w, List.of());
            int toLead = Hex.distance(w, e.at(), lead.at());
            if (toLead > ac.escortReach()) return new Escorts("plane #" + id + " is " + toLead + " hexes from " + lead.at() + "; escorts fly from within " + ac.escortReach(), w, List.of());
            if ((toLead + leg) * 2 > cls.rangeAt(e.tech())) return new Escorts("plane #" + id + " cannot fly to " + target + " and back: it flies " + q(Math.floor(cls.rangeAt(e.tech()))) + " there and back", w, List.of());
            if (field.stock().get(pet) < cls.fuel()) return new Escorts(e.at() + " has " + q(field.stock().get(pet)) + " petrol; plane #" + id + "'s sortie takes " + q(cls.fuel()), w, List.of());
            w = w.withSector(field.withStock(field.stock().plus(pet, -cls.fuel())));
            out.add(e);
        }
        return new Escorts(null, w, out);
    }

    /** What the flight out did to the raid: {@code lost} when the lead plane was shot down or turned back. */
    private record Raid(World world, boolean lost, String story) {}

    /**
     * The flight out (KNOWN aircombat.c ac_encounter): hex by hex from the field to the target. Over each sector held by
     * a country at war with you, that country's fighters rise — those fit to fly ({@code min_efficiency}), on a working
     * airfield of theirs, with the range to reach the sector and come back and their petrol on the field; newest first,
     * as many as the raid has planes and one more, and each only once a raid. Your escorts fight them first, then the
     * lead plane fights whoever is left. Flak is not here: it fires over the target, after.
     */
    private static Raid encounter(GameConfig cfg, Commodities com, UnrestStep.R r, World w, Country c, Plane lead, List<Plane> escorts, Coord target) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        UnitsCfg.AirCombatCfg ac = pc.airCombat();
        if (ac == null) return new Raid(w, false, "");
        int pet = com.index("pet");
        Map<Long, Plane> now = new HashMap<>();
        now.put(lead.id(), lead);
        for (Plane e : escorts) now.put(e.id(), e);
        List<Long> esc = new ArrayList<>(escorts.stream().map(Plane::id).toList());
        boolean leadUp = true;
        Set<Long> launched = new HashSet<>(), gone = new HashSet<>();
        List<String> story = new ArrayList<>();
        for (Coord at : flightPath(w, lead.at(), target)) {
            Sector s = w.sector(at);
            if (!s.owned() || s.owner() == c.id() || !w.atWar(c.id(), s.owner())) continue;
            int them = s.owner();
            // who rises: a snapshot of their fighters, newest first
            List<Plane> up = new ArrayList<>();
            int room = (leadUp ? 1 : 0) + esc.size() + ac.extraInterceptors();
            List<Plane> theirs = new ArrayList<>(w.planes());
            theirs.sort((a, b) -> Long.compare(b.id(), a.id()));
            for (Plane f : theirs) {
                if (up.size() >= room) break;
                if (f.owner() != them || launched.contains(f.id())) continue;
                if (w.onTheBlock(TradeLot.PLANE, f.id()) != null) continue;   // KNOWN aircombat.c:773: not one on the trading block
                UnitsCfg.PlaneClassCfg fc = pc.planeClass(f.cls());
                if (fc == null || !fc.has("intercept") || f.efficiency() < ac.minEfficiency()) continue;
                Sector field = w.sector(f.at());
                if (field.owner() != them || !cfg.sectorType(field.designation()).hasFlag("builds_planes") || field.efficiency() < ac.fieldMinEfficiency()) continue;
                if (fc.rangeAt(f.tech()) < 2 * Hex.distance(w, f.at(), at)) continue;
                if (field.stock().get(pet) < fc.fuel()) continue;
                w = w.withSector(field.withStock(field.stock().plus(pet, -fc.fuel())));
                launched.add(f.id());
                now.put(f.id(), f);
                up.add(f);
            }
            if (up.isEmpty()) continue;
            story.add(up.size() + (up.size() == 1 ? " fighter" : " fighters") + " of " + w.country(them).name() + " rose over " + at);
            List<Long> ups = new ArrayList<>(up.stream().map(Plane::id).toList());
            // the escorts first, then the lead plane against whoever is left (KNOWN ac_intercept)
            airToAir(cfg, r, now, esc, ups, gone, story);
            if (leadUp && !ups.isEmpty()) {
                List<Long> mine = new ArrayList<>(List.of(lead.id()));
                airToAir(cfg, r, now, mine, ups, gone, story);
                leadUp = !mine.isEmpty();
            }
            if (!leadUp) break;
        }
        // what came of it, on the planes and in the world
        for (Plane p : now.values()) {
            if (gone.contains(p.id()) && p.efficiency() < pc.minEfficiency()) { w = w.withoutPlane(p.id()); continue; }
            Plane was = w.plane(p.id());
            if (was == null) continue;
            String note = p.owner() == c.id() ? (p.id() == lead.id() ? was.note() : "flew escort to " + target) : "rose against a raid by " + c.name();
            w = w.withPlane(p.withNote(note));
        }
        Plane l = now.get(lead.id());
        String end = leadUp ? "" : l.efficiency() < pc.minEfficiency() ? "plane #" + lead.id() + " was shot down on the way" : "plane #" + lead.id() + " turned back at " + q(l.efficiency()) + "%";
        if (!end.isEmpty()) story.add(end);
        return new Raid(w, !leadUp, String.join("; ", story));
    }

    /**
     * KNOWN aircombat.c ac_airtoair: the two lists are paired round and round, each advancing, until both have been
     * gone through once; a plane shot down or turned back leaves its list. Each pairing is a dogfight.
     */
    private static void airToAir(GameConfig cfg, UnrestStep.R r, Map<Long, Plane> now, List<Long> raid, List<Long> ints, Set<Long> gone, List<String> story) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        int n = Math.max(raid.size(), ints.size()), ai = 0, di = 0;
        for (int i = 0; i < n && !raid.isEmpty() && !ints.isEmpty(); i++) {
            ai %= raid.size();
            di %= ints.size();
            long a = raid.get(ai), d = ints.get(di);
            Plane pa = now.get(a), pd = now.get(d);
            int[] dam = dogfight(cfg, r, pa, pc.planeClass(pa.cls()), pd, pc.planeClass(pd.cls()));
            Plane na = pa.withEfficiency(pa.efficiency() - dam[0]), nd = pd.withEfficiency(pd.efficiency() - dam[1]);
            now.put(a, na);
            now.put(d, nd);
            story.add("#" + a + " against their #" + d + ": yours lost " + dam[0] + "%, theirs " + dam[1] + "%");
            if (out(pc, r, na)) { raid.remove(ai); gone.add(a); } else ai++;
            if (out(pc, r, nd)) { ints.remove(di); gone.add(d); } else di++;
        }
    }

    /** Shot down below the minimum, or turned back under {@code abort_below} with chance (80 − efficiency)/100 (KNOWN ac_damage_plane). */
    private static boolean out(UnitsCfg.PlanesCfg pc, UnrestStep.R r, Plane p) {
        return p.efficiency() < pc.minEfficiency() || (p.efficiency() < pc.abortBelow() && r.chance((pc.abortBelow() - p.efficiency()) / 100.0));
    }

    /**
     * KNOWN aircombat.c ac_dog: the raid plane's attack (its defence if it has none) and the interceptor's defence, each
     * by efficiency and never under half the class's defence, set the odds; four rolls of 20, plus one, exchanges follow,
     * each costing one side a point, until they run out or either is down to the minimum. Returns {raid's, interceptor's}.
     */
    static int[] dogfight(GameConfig cfg, UnrestStep.R r, Plane a, UnitsCfg.PlaneClassCfg ac, Plane d, UnitsCfg.PlaneClassCfg dc) {
        UnitsCfg.PlanesCfg pc = cfg.units().planes();
        UnitsCfg.AirCombatCfg k = pc.airCombat();
        double base = ac.attackAt(a.tech()) > 0 ? ac.attackAt(a.tech()) : ac.defenseAt(a.tech());
        int att = Math.max((int) (base * a.efficiency() / 100), (int) (ac.defense() / 2));
        int def = Math.max((int) (dc.defenseAt(d.tech()) * d.efficiency() / 100), (int) (dc.defense() / 2));
        if (att < 1) { def += 1 - att; att = 1; }
        if (def < 1) { att += 1 - def; def = 1; }
        double odds = Math.max(k.oddsFloor(), (double) att / (att + def));
        int intensity = k.intensityAdd();
        for (int i = 0; i < k.intensityDice(); i++) intensity += r.roll(k.intensityDie());
        int adam = 0, ddam = 0;
        for (int i = 0; i < intensity; i++) {
            if (a.efficiency() - adam < pc.minEfficiency() || d.efficiency() - ddam < pc.minEfficiency()) break;
            if (r.chance(odds)) ddam++; else adam++;
        }
        return new int[] {adam, ddam};
    }

    /** The hexes a flight crosses, field to target: each step the neighbour nearest the target, ties to the first direction. */
    static List<Coord> flightPath(World w, Coord from, Coord to) {
        List<Coord> path = new ArrayList<>(List.of(from));
        Coord cur = from;
        for (int guard = 0; !cur.equals(to) && guard < w.width() + w.height(); guard++) {
            Coord best = null;
            int bestD = Integer.MAX_VALUE;
            for (Coord n : Hex.neighbours(w, cur)) { int dd = Hex.distance(w, n, to); if (dd < bestD) { bestD = dd; best = n; } }
            if (best == null) break;
            cur = best;
            path.add(cur);
        }
        return path;
    }

    private static String article(String name) { return ("aeiou".indexOf(Character.toLowerCase(name.charAt(0))) >= 0 ? "an " : "a ") + name; }
}
