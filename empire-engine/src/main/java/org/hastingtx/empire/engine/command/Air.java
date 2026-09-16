package org.hastingtx.empire.engine.command;

import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.config.UnitsCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.steps.UnrestStep;

import java.util.Map;

/**
 * Planes (issue #262, #71 slice 3a), as the original flew them: built on an airfield (KNOWN sect.config {@code *},
 * commands/buil.c), flown as a sortie that takes its petrol and its bombs off that field (KNOWN subs/plnsub.c
 * {@code pln_equip}), shot at by the flak of what it flies against (KNOWN subs/aircombat.c), and bombing by
 * {@code pln_damage}.
 *
 * <p>A plane's range is the round trip, so it strikes at half of it. It comes home to its own field the same turn —
 * standing missions, escorts and interception come in the next slice.
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

        // the flak over the target, before it can drop anything
        Flak flak = flak(cfg, com, r, next, p, cls, target);
        next = flak.world();
        if (flak.shotDown()) return new CommandResult(next, null, 0, flak.story() + " — plane #" + p.id() + " was shot down over " + b.at());
        p = flak.plane();
        if (flak.aborted()) return new CommandResult(next, null, 0, flak.story() + " — plane #" + p.id() + " turned back at " + q(p.efficiency()) + "%");

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
        return new CommandResult(next, null, 0, (flak.story().isEmpty() ? "" : flak.story() + " — ")
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

        Sector target = next.sector(rc.at());
        Flak flak = flak(cfg, com, r, next, p, cls, target);
        next = flak.world();
        if (flak.shotDown()) return new CommandResult(next, null, 0, flak.story() + " — plane #" + p.id() + " was shot down over " + rc.at() + " and told you nothing");
        p = flak.plane();
        next = next.withPlane(p.withNote("flew over " + rc.at()));
        // what it saw goes onto your chart, as sight does (issue #64)
        next = remember(next, c.id(), target);
        String who = target.owned() ? next.country(target.owner()).name() : "nobody";
        String what = target.owned() && target.owner() != c.id()
                ? who + "'s " + target.designation().replace('_', ' ') + " at " + q(target.efficiency()) + "%, "
                  + q(Math.floor(target.stock().get(com.civ))) + " civilians and " + q(Math.floor(target.stock().get(com.mil))) + " soldiers"
                : target.designation().replace('_', ' ') + " at " + rc.at() + ", " + who;
        return new CommandResult(next, null, 0, (flak.story().isEmpty() ? "" : flak.story() + " — ")
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

    private static String article(String name) { return ("aeiou".indexOf(Character.toLowerCase(name.charAt(0))) >= 0 ? "an " : "a ") + name; }
}
