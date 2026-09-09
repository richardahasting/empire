package org.hastingtx.empire.engine.update.steps;

import org.hastingtx.empire.engine.config.DistributionCfg;
import org.hastingtx.empire.engine.geo.Hex;
import org.hastingtx.empire.engine.model.*;
import org.hastingtx.empire.engine.update.Ctx;
import org.hastingtx.empire.engine.update.Flow;
import org.hastingtx.empire.engine.update.Ledger;
import org.hastingtx.empire.engine.update.Rng;
import org.hastingtx.empire.engine.update.Step;

import java.util.*;

/**
 * Steps 6 and 7: plan every transfer as a path on the ownership graph against the frozen
 * snapshot, resolve contention proportionally (commodity priority, then seeded RNG, for the
 * last indivisible unit), then walk each flow hop by hop debiting mobility from whoever
 * pays for the hop: the sending sector alone ({@code mobility_debited_from: sending_sector},
 * the default — a held parcel's sender is the sector holding it) or each entered sector
 * ({@code transited_sectors}). Whatever cannot complete holds in place as a HeldParcel. No
 * sector order anywhere in here decides who wins anything.
 */
public final class FlowStep implements Step {
    public String name() { return "flow"; }

    /** One intended movement. {@code path} runs origin..dest inclusive, truncated to reach. */
    static final class Plan {
        final String kind; final int owner, commodity; final int originIdx; final Coord dest;
        final List<Coord> path; final HeldParcel fromHeld; final double requested;
        double claim;           // after contention
        int sourceKey;          // budget key: sector index for stock sources, -1-k for held parcel k
        Plan(String kind, int owner, int commodity, int originIdx, Coord dest, List<Coord> path, HeldParcel fromHeld, double requested) {
            this.kind = kind; this.owner = owner; this.commodity = commodity; this.originIdx = originIdx; this.dest = dest;
            this.path = path; this.fromHeld = fromHeld; this.requested = requested; this.claim = requested;
        }
    }

    public void run(Ctx ctx) {
        DistributionCfg dc = ctx.cfg.distribution();
        double quantum = dc.quantumOr0();
        List<Plan> plans = new ArrayList<>();

        // --- 6. plan -------------------------------------------------------------------
        // distribution
        for (int i = 0; i < ctx.led.nSectors; i++) {
            Sector s = ctx.sector(i);
            if (!s.owned() || s.distCenter() == null || s.distCenter().equals(s.at())) continue;
            Sector ctr = ctx.snap.sector(s.distCenter());
            if (ctr.owner() != s.owner()) continue;
            int ci = ctx.idx(ctr.at());
            for (int c = 0; c < ctx.com.size(); c++) {
                if (!s.hasThreshold(c)) continue;
                double post = s.stock().get(c) + ctx.led.stock[i][c];
                double thr = s.threshold(c);
                if (post < thr - 1e-9) {
                    List<Coord> path = path(ctx, ctr.at(), s.at(), s.owner(), dc);
                    if (path != null) plans.add(new Plan("distribution", s.owner(), c, ci, s.at(), truncate(ctx, path, s.owner(), dc), null, floorQ(thr - post, quantum)));
                } else if (post > thr + 1e-9) {
                    List<Coord> path = path(ctx, s.at(), ctr.at(), s.owner(), dc);
                    if (path != null) plans.add(new Plan("distribution", s.owner(), c, i, ctr.at(), truncate(ctx, path, s.owner(), dc), null, floorQ(post - thr, quantum)));
                }
            }
        }
        // deliver orders (KNOWN: deliver.c): above the threshold, one hex that way, if that hex is yours (issue #45)
        for (int i = 0; i < ctx.led.nSectors; i++) {
            Sector s = ctx.sector(i);
            if (!s.owned() || s.deliver().count() == 0) continue;
            for (int c = 0; c < ctx.com.size(); c++) {
                if (!s.deliver().has(c)) continue;
                double post = s.stock().get(c) + ctx.led.stock[i][c];
                double thr = s.deliver().threshold(c);
                if (post <= thr + 1e-9) continue;
                Coord to = Hex.normalise(ctx.snap, Hex.stepRaw(s.at(), s.deliver().dir(c)));
                if (to == null) continue;
                Sector t = ctx.snap.sector(to);
                if (t.owner() != s.owner() || !t.terrain().isLand()) continue;
                plans.add(new Plan("deliver", s.owner(), c, i, to, List.of(s.at(), to), null, floorQ(post - thr, quantum)));
            }
        }
        // held parcels resume
        List<HeldParcel> heldRefs = new ArrayList<>();
        Map<HeldParcel, Integer> heldAt = new IdentityHashMap<>();
        for (int i = 0; i < ctx.led.nSectors; i++) {
            for (HeldParcel p : ctx.sector(i).held()) {
                heldRefs.add(p); heldAt.put(p, i);
                if (p.rail()) continue;   // trains are handled by the rail pass
                List<Coord> path = path(ctx, ctx.sector(i).at(), p.dest(), p.owner(), dc);
                if (path == null) continue; // stays where it is
                plans.add(new Plan("resume", p.owner(), p.commodity(), i, p.dest(), truncate(ctx, path, p.owner(), dc), p, p.qty()));
            }
        }
        // manual moves
        for (MoveOrder m : ctx.snap.pendingMoves()) {
            int fi = ctx.idx(m.from());
            List<Coord> path = path(ctx, m.from(), m.to(), m.owner(), dc);
            if (path == null) { ctx.led.event("move_failed", m.owner(), m.from(), "no route from " + m.from() + " to " + m.to(), m.qty()); continue; }
            int reach = (int) Math.floor(ctx.cfg.economy().mobility().manualMoveMaxSectorsPerUpdate().eval(ctx.country(m.owner()).levels().tech()));
            List<Coord> p = path.size() - 1 > reach ? new ArrayList<>(path.subList(0, reach + 1)) : path;
            plans.add(new Plan("move", m.owner(), m.commodity(), fi, m.to(), p, null, floorQ(m.qty(), quantum)));
        }
        if (plans.isEmpty()) { Map<Integer, List<HeldParcel>> nh = new HashMap<>(); for (int i = 0; i < ctx.led.nSectors; i++) for (HeldParcel p : ctx.sector(i).held()) addHeld(nh, i, p); railPass(ctx, nh); carryHeld(ctx, nh); return; }

        // --- 7. resolve contention ------------------------------------------------------
        // source budgets
        Map<Long, Double> sourceBudget = new HashMap<>();
        for (Plan p : plans) {
            if (p.fromHeld != null) { p.sourceKey = -1 - heldRefs.indexOf(p.fromHeld); sourceBudget.put((long) p.sourceKey, p.fromHeld.qty()); }
            else {
                p.sourceKey = p.originIdx;
                long key = ((long) p.originIdx << 8) | p.commodity;
                Sector src = ctx.sector(p.originIdx);
                double post = src.stock().get(p.commodity) + ctx.led.stock[p.originIdx][p.commodity];
                // every order keeps its own threshold (a centre supplying others keeps the centre's; a source pushing to its
                // centre keeps its own, which its request already respects); where a deliver order and a distribution
                // threshold draw on one stock, the stricter keep bounds them both
                double keep = switch (p.kind) {
                    case "distribution" -> src.hasThreshold(p.commodity) ? src.threshold(p.commodity) : 0;
                    case "deliver" -> src.deliver().has(p.commodity) ? src.deliver().threshold(p.commodity) : 0;
                    default -> 0;
                };
                sourceBudget.merge(key, Math.max(0, post - keep), Math::min);
            }
        }
        // mobility budgets
        double[] mobBudget = new double[ctx.led.nSectors];
        for (int i = 0; i < ctx.led.nSectors; i++) mobBudget[i] = Math.max(0, ctx.sector(i).mobility() + ctx.led.mobility[i]);
        // room for people (issue #48): civilians and workers never move into a sector that cannot hold them —
        // the apply step would truncate them. Room = population cap − people there after this update's births.
        double[] roomBudget = new double[ctx.led.nSectors];
        for (int i = 0; i < ctx.led.nSectors; i++) {
            Sector s = ctx.sector(i);
            roomBudget[i] = Math.max(0, ctx.maxPopulation(s) - (s.stock().get(ctx.com.civ) + ctx.led.stock[i][ctx.com.civ] + s.stock().get(ctx.com.uw) + ctx.led.stock[i][ctx.com.uw]));
        }

        for (int iter = 0; iter < 50; iter++) {
            boolean changed = false;
            // sources
            Map<Long, Double> claimed = new HashMap<>();
            for (Plan p : plans) claimed.merge(srcKey(p), p.claim, Double::sum);
            for (Plan p : plans) {
                double total = claimed.get(srcKey(p)), budget = sourceBudget.get(srcKey(p));
                if (total > budget + 1e-9) { p.claim *= budget / total; changed = true; }
            }
            // mobility
            double[] mobClaim = new double[ctx.led.nSectors];
            for (Plan p : plans) for (int h = 1; h < p.path.size(); h++) mobClaim[payer(ctx, p, h)] += hopCost(ctx, p, p.claim, h);
            for (Plan p : plans) {
                double f = 1.0;
                for (int h = 1; h < p.path.size(); h++) { int t = payer(ctx, p, h); if (mobClaim[t] > mobBudget[t] + 1e-9) f = Math.min(f, mobBudget[t] / mobClaim[t]); }
                if (f < 1.0) { p.claim *= f; changed = true; }
            }
            // room at the destination for people
            double[] roomClaim = new double[ctx.led.nSectors];
            for (Plan p : plans) if (needsRoom(ctx, p)) roomClaim[ctx.idx(p.path.get(p.path.size() - 1))] += p.claim;
            for (Plan p : plans) {
                if (!needsRoom(ctx, p)) continue;
                int d = ctx.idx(p.path.get(p.path.size() - 1));
                if (roomClaim[d] > roomBudget[d] + 1e-9) { p.claim *= roomBudget[d] / roomClaim[d]; changed = true; }
            }
            if (!changed) break;
        }
        // quantise (if a quantum is configured); hand out the leftover whole units by priority, then RNG
        for (Plan p : plans) p.claim = floorQ(p.claim, quantum);
        if (quantum > 0) handOutLeftovers(ctx, plans, quantum, sourceBudget, mobBudget);

        walk(ctx, plans, quantum, mobBudget, heldRefs, heldAt);
    }

    private static void handOutLeftovers(Ctx ctx, List<Plan> plans, double quantum, Map<Long, Double> sourceBudget, double[] mobBudget) {
        SplittableRandom rng = Rng.stream("contention", ctx.seed);
        List<Plan> order = new ArrayList<>(plans);
        double[] tie = new double[plans.size()];
        for (int k = 0; k < tie.length; k++) tie[k] = rng.nextDouble();
        Map<Plan, Double> tieOf = new IdentityHashMap<>();
        for (int k = 0; k < plans.size(); k++) tieOf.put(plans.get(k), tie[k]);
        order.sort(Comparator.<Plan>comparingInt(p -> ctx.com.priority(p.commodity)).thenComparingDouble(tieOf::get));
        Map<Long, Double> used = new HashMap<>();
        double[] mobUsed = new double[ctx.led.nSectors];
        for (Plan p : plans) { used.merge(srcKey(p), p.claim, Double::sum); for (int h = 1; h < p.path.size(); h++) mobUsed[payer(ctx, p, h)] += hopCost(ctx, p, p.claim, h); }
        for (Plan p : order) {
            while (p.claim + quantum <= p.requested + 1e-9 && used.get(srcKey(p)) + quantum <= sourceBudget.get(srcKey(p)) + 1e-9 && mobRoom(ctx, p, quantum, mobBudget, mobUsed)) {
                p.claim += quantum; used.merge(srcKey(p), quantum, Double::sum);
                for (int h = 1; h < p.path.size(); h++) mobUsed[payer(ctx, p, h)] += hopCost(ctx, p, quantum, h);
            }
        }

    }

    private void walk(Ctx ctx, List<Plan> plans, double quantum, double[] mobBudget, List<HeldParcel> heldRefs, Map<HeldParcel, Integer> heldAt) {
        double[] mobSpent = new double[ctx.led.nSectors];
        Map<Integer, List<HeldParcel>> newHeld = new HashMap<>();
        Set<HeldParcel> consumedHeld = Collections.newSetFromMap(new IdentityHashMap<>());
        plans.sort(Comparator.<Plan>comparingInt(p -> p.originIdx).thenComparingInt(p -> p.commodity).thenComparing(p -> p.dest));
        for (Plan p : plans) {
            double qty = p.claim;
            if (qty <= 0) {
                ctx.led.flows.add(new Flow(p.kind, p.owner, p.commodity, p.requested, 0, p.path, 0, false, "no allocation"));
                if (isPull(ctx, p)) ctx.led.shortOf(ctx.idx(p.dest), p.commodity, p.requested);
                continue;
            }
            if (p.fromHeld != null) consumedHeld.add(p.fromHeld);
            int hops = 0; int cur = p.originIdx; String hold = null; double moving = qty;
            for (int h = 1; h < p.path.size(); h++) {
                int t = ctx.idx(p.path.get(h));
                int pay = payer(ctx, p, h);
                double unitCost = unitCost(ctx, p, h);
                double avail = mobBudget[pay] - mobSpent[pay];
                double canMove = unitCost <= 0 ? moving : Math.min(moving, floorQ(Math.max(0, avail) / unitCost, quantum));
                if (canMove < 1e-9) canMove = 0;
                if (moving - canMove < 1e-9) canMove = moving;   // floating-point dust is not a parcel
                if (canMove <= 0) { hold = "mobility exhausted in " + ctx.sector(pay).at(); break; }
                if (canMove < moving) { // the remainder holds here
                    addHeld(newHeld, cur, new HeldParcel(p.commodity, moving - canMove, p.owner, p.path.get(0), p.dest, ctx.snap.updateNumber()));
                    if (p.fromHeld == null) ctx.led.toHeld(p.originIdx, p.commodity, moving - canMove);
                    moving = canMove; hold = "mobility exhausted in " + ctx.sector(pay).at();
                }
                mobSpent[pay] += moving * unitCost;
                cur = t; hops++;
            }
            boolean completed = ctx.sector(cur).at().equals(p.dest);
            if (!completed && hold == null) hold = "reach exhausted at " + ctx.sector(cur).at();
            if (moving > 0) {
                if (completed) {
                    if (p.fromHeld == null) ctx.led.transfer(p.originIdx, cur, p.commodity, moving);
                    else ctx.led.fromHeld(cur, p.commodity, moving);
                } else {
                    addHeld(newHeld, cur, new HeldParcel(p.commodity, moving, p.owner, p.path.get(0), p.dest, ctx.snap.updateNumber()));
                    if (p.fromHeld == null) ctx.led.toHeld(p.originIdx, p.commodity, moving);
                }
            }
            if (p.fromHeld != null && qty < p.fromHeld.qty() - 1e-9) // partial claim of a held parcel: the rest stays put
                addHeld(newHeld, p.originIdx, p.fromHeld.withQty(p.fromHeld.qty() - qty));
            ctx.led.flows.add(new Flow(p.kind, p.owner, p.commodity, p.requested, qty, p.path, hops, completed, hold));
            // the story, at both ends (issue #49)
            String what = Ledger.q(moving) + " " + ctx.com.id(p.commodity);
            String verb = switch (p.kind) { case "deliver" -> "delivered"; case "move" -> "moved"; case "resume" -> "forwarded"; default -> "sent"; };
            if (moving > 0) {
                Coord from = ctx.sector(p.originIdx).at(), to = ctx.sector(cur).at();
                if (completed) {
                    ctx.led.note(p.originIdx, verb + " " + what + " to " + to + (p.kind.equals("distribution") && p.dest.equals(ctx.sector(p.originIdx).distCenter()) ? " (surplus to centre)" : ""));
                    ctx.led.note(cur, "received " + what + " from " + from + (p.kind.equals("distribution") && !p.dest.equals(ctx.sector(p.originIdx).distCenter()) ? " (supply from centre)" : ""));
                } else {
                    ctx.led.note(p.originIdx, what + " bound for " + p.dest + " held at " + to + (hold != null ? " — " + hold : ""));
                    if (cur != p.originIdx) ctx.led.note(cur, "holding " + what + " bound for " + p.dest + " from " + from);
                }
            }
            if (qty - moving > 1e-9) ctx.led.note(p.originIdx, Ledger.q(qty - moving) + " " + ctx.com.id(p.commodity) + " for " + p.dest + " stayed" + (hold != null ? " — " + hold : ""));
            // a sector that pulled from its centre and did not get all it asked for is short by the rest
            if (isPull(ctx, p) && p.requested - (completed ? moving : 0) > 1e-9) ctx.led.shortOf(ctx.idx(p.dest), p.commodity, p.requested - (completed ? moving : 0));
        }
        for (int i = 0; i < ctx.led.nSectors; i++) ctx.led.mobility[i] -= mobSpent[i];
        // held parcels that were not planned (no route) stay put
        for (HeldParcel p : heldRefs) if (!consumedHeld.contains(p)) addHeld(newHeld, heldAt.get(p), p);
        railPass(ctx, newHeld);
        carryHeld(ctx, newHeld);
    }

    /**
     * Rail (NEW by spec): trains run depot to depot along contiguous rail. Each rail sector has a
     * capacity budget per update (shared proportionally); a train advances up to its range and
     * holds on the rail sector it reached; a severed line strands it in place. Cash by volume.
     */
    private void railPass(Ctx ctx, Map<Integer, List<HeldParcel>> newHeld) {
        var rail = ctx.cfg.infrastructure().rail();
        record Train(int owner, int commodity, double qty, int originIdx, Coord dest, List<Coord> path, HeldParcel from) {}
        List<Train> trains = new ArrayList<>();
        // new orders
        for (var o : ctx.snap.pendingRail()) {
            List<Coord> path = ctx.railPath(o.from(), o.to(), o.owner());
            if (path == null) { ctx.led.event("rail_severed", o.owner(), o.from(), "no rail line from " + o.from() + " to " + o.to() + " any more; shipment cancelled", o.qty()); continue; }
            int fi = ctx.idx(o.from());
            double avail = ctx.sector(fi).stock().get(o.commodity()) + ctx.led.stock[fi][o.commodity()];
            trains.add(new Train(o.owner(), o.commodity(), Math.max(0, Math.min(o.qty(), avail)), fi, o.to(), path, null));
        }
        // trains already on the line: they were removed from newHeld's carry-over by the road pass only if planned there; rail parcels were never planned there, so take them out now
        for (var e : new ArrayList<>(newHeld.entrySet())) {
            List<HeldParcel> keep = new ArrayList<>();
            for (HeldParcel p : e.getValue()) {
                if (!p.rail()) { keep.add(p); continue; }
                List<Coord> path = ctx.railPath(ctx.sector(e.getKey()).at(), p.dest(), p.owner());
                if (path == null) { keep.add(p); ctx.led.event("rail_stranded", p.owner(), ctx.sector(e.getKey()).at(), "train stranded at " + ctx.sector(e.getKey()).at() + ": the line to " + p.dest() + " is cut", p.qty()); continue; }
                trains.add(new Train(p.owner(), p.commodity(), p.qty(), e.getKey(), p.dest(), path, p));
            }
            e.setValue(keep);
        }
        if (trains.isEmpty()) return;
        // capacity contention per rail sector entered, proportional
        double[] cap = new double[ctx.led.nSectors];
        for (int i = 0; i < ctx.led.nSectors; i++) cap[i] = ctx.railCapable(i) ? ctx.railCapacity(i) : 0;
        double[] claim = new double[trains.size()];
        for (int k = 0; k < trains.size(); k++) claim[k] = trains.get(k).qty();
        for (int iter = 0; iter < 50; iter++) {
            double[] used = new double[ctx.led.nSectors]; boolean changed = false;
            for (int k = 0; k < trains.size(); k++) for (int h = 1; h < trains.get(k).path.size(); h++) used[ctx.idx(trains.get(k).path.get(h))] += claim[k];
            for (int k = 0; k < trains.size(); k++) {
                double f = 1.0;
                for (int h = 1; h < trains.get(k).path.size(); h++) { int t = ctx.idx(trains.get(k).path.get(h)); if (used[t] > cap[t] + 1e-9) f = Math.min(f, cap[t] / used[t]); }
                if (f < 1.0) { claim[k] *= f; changed = true; }
            }
            if (!changed) break;
        }
        for (int k = 0; k < trains.size(); k++) {
            Train t = trains.get(k);
            double qty = claim[k];
            if (qty <= 1e-9) { ctx.led.flows.add(new Flow("rail", t.owner, t.commodity, t.qty, 0, t.path, 0, false, "line at capacity")); continue; }
            int range = (int) Math.floor(rail.maxSectorsPerUpdate().eval(ctx.country(t.owner).levels().tech()));
            int hops = Math.min(range, t.path.size() - 1);
            int stopIdx = ctx.idx(t.path.get(hops));
            boolean arrives = hops == t.path.size() - 1;
            // depot efficiency at the endpoints scales what actually gets through (spec: capacity scales with depot efficiency)
            double effScale = Math.min(ctx.sector(ctx.idx(t.path.get(0))).efficiency(), ctx.sector(ctx.idx(t.dest)).efficiency()) / 100.0;
            double moving = Math.min(qty, t.from == null ? qty : t.qty) * (t.from == null ? Math.max(0.01, effScale) : 1.0);
            if (t.from == null) ctx.led.toHeld(t.originIdx, t.commodity, moving);   // leaves stock; becomes cargo
            double leftover = t.qty - moving;
            if (t.from != null && leftover > 1e-9) addHeld(newHeld, t.originIdx, t.from.withQty(leftover));
            if (arrives) ctx.led.fromHeld(stopIdx, t.commodity, moving);
            else addHeld(newHeld, stopIdx, new HeldParcel(t.commodity, moving, t.owner, t.path.get(0), t.dest, ctx.snap.updateNumber(), "rail"));
            double cash = rail.cashPer100UnitsShipped() * moving / 100.0;
            ctx.led.cash[t.owner] -= cash;
            ctx.led.flows.add(new Flow("rail", t.owner, t.commodity, t.qty, moving, t.path, hops, arrives, arrives ? null : "range exhausted at " + t.path.get(hops)));
        }
    }

    private static void carryHeld(Ctx ctx, Map<Integer, List<HeldParcel>> newHeld) {
        for (int i = 0; i < ctx.led.nSectors; i++) ctx.led.heldNext[i] = newHeld.getOrDefault(i, List.of());
    }

    /** Parcels with the same commodity, owner and destination merge, keeping the earliest issue. */
    private static void addHeld(Map<Integer, List<HeldParcel>> m, int at, HeldParcel p) {
        if (p.qty() < 1e-9) return;
        List<HeldParcel> l = m.computeIfAbsent(at, k -> new ArrayList<>());
        for (int i = 0; i < l.size(); i++) {
            HeldParcel q = l.get(i);
            if (q.commodity() == p.commodity() && q.owner() == p.owner() && q.dest().equals(p.dest()) && q.rail() == p.rail()) {
                l.set(i, new HeldParcel(p.commodity(), q.qty() + p.qty(), p.owner(), q.origin(), p.dest(), Math.min(q.issuedUpdate(), p.issuedUpdate()), p.mode()));
                return;
            }
        }
        l.add(p);
    }

    private static long srcKey(Plan p) { return p.fromHeld != null ? (long) p.sourceKey : (((long) p.originIdx << 8) | p.commodity); }

    private static double hopCost(Ctx ctx, Plan p, double qty, int h) { return qty * unitCost(ctx, p, h); }

    /** Mobility per unit for hop h: packed weight (leaving the origin) × cost into the entered sector, ÷ the distribution bonus. */
    private static double unitCost(Ctx ctx, Plan p, int h) {
        double w = ctx.weightLeaving(p.commodity, ctx.sector(p.originIdx));
        double bonus = p.kind.equals("move") ? 1.0 : p.kind.equals("deliver") ? ctx.cfg.distribution().deliverMobilityBonusOr1() : ctx.cfg.distribution().mobilityBonusOr1();
        return w * ctx.moveCostInto(ctx.snap.sector(p.path.get(h))) / bonus;
    }

    /** A distribution flow from a centre to a sector below its threshold. */
    private static boolean isPull(Ctx ctx, Plan p) {
        if (!p.kind.equals("distribution")) return false;
        Sector dst = ctx.snap.sector(p.dest);
        return dst.distCenter() != null && dst.distCenter().equals(ctx.sector(p.originIdx).at());
    }

    /** Civilians and workers count against the destination's population cap (mil do not: KNOWN, trunc_people). */
    private static boolean needsRoom(Ctx ctx, Plan p) { return p.commodity == ctx.com.civ || p.commodity == ctx.com.uw; }

    private static boolean mobRoom(Ctx ctx, Plan p, double qty, double[] budget, double[] used) {
        for (int h = 1; h < p.path.size(); h++) { int t = payer(ctx, p, h); if (used[t] + hopCost(ctx, p, qty, h) > budget[t] + 1e-9) return false; }
        return true;
    }

    /** Index of the sector whose mobility pays for hop h: the origin (sending sector, or the sector holding a resumed parcel) or the entered sector. */
    private static int payer(Ctx ctx, Plan p, int h) {
        return ctx.cfg.distribution().sourcePays() ? p.originIdx : ctx.idx(p.path.get(h));
    }

    private static double floorQ(double v, double q) { return q <= 0 ? v : Math.floor(v / q + 1e-9) * q; }

    /** Reach in hops for this path: base + tech, plus the road bonus prorated by the path's mean road level. */
    private static List<Coord> truncate(Ctx ctx, List<Coord> path, int owner, DistributionCfg dc) {
        double tech = ctx.country(owner).levels().tech();
        double road = 0;
        for (int h = 1; h < path.size(); h++) road += ctx.snap.sector(path.get(h)).roadLevel();
        if (path.size() > 1) road /= (path.size() - 1);
        double bonus = dc.maxReachSectors().roadBonusAt100() == null ? 0 : dc.maxReachSectors().roadBonusAt100() * road / 100.0;
        int reach = (int) Math.floor(dc.maxReachSectors().eval(tech) + bonus);
        return path.size() - 1 > reach ? new ArrayList<>(path.subList(0, reach + 1)) : path;
    }

    /** Cheapest-mobility (or fewest-hop) path through sectors owned by {@code owner}. Deterministic tie-break on coordinates. */
    public static List<Coord> path(Ctx ctx, Coord from, Coord to, int owner, DistributionCfg dc) {
        if (from.equals(to)) return List.of(from);
        boolean hops = "fewest_hops".equals(dc.pathCost());
        int n = ctx.led.nSectors;
        double[] dist = new double[n]; Arrays.fill(dist, Double.POSITIVE_INFINITY);
        int[] prev = new int[n]; Arrays.fill(prev, -1);
        int src = ctx.idx(from), dst = ctx.idx(to);
        dist[src] = 0;
        // keys are (index, dist-bits) snapshots; stale entries are skipped via done[]
        PriorityQueue<long[]> q = new PriorityQueue<>((a, b) -> { int c = Double.compare(Double.longBitsToDouble(a[1]), Double.longBitsToDouble(b[1])); return c != 0 ? c : Long.compare(a[0], b[0]); });
        q.add(new long[] {src, Double.doubleToLongBits(0)});
        boolean[] done = new boolean[n];
        while (!q.isEmpty()) {
            long[] top = q.poll();
            int u = (int) top[0];
            if (done[u]) continue;
            done[u] = true;
            if (u == dst) break;
            for (Coord nb : ctx.neighbours.get(u)) {
                int v = ctx.idx(nb);
                Sector sv = ctx.sector(v);
                if (sv.owner() != owner || !sv.terrain().isLand()) continue;
                double w = hops ? 1.0 : ctx.moveCostInto(sv);
                if (Double.isInfinite(w)) continue;
                double nd = dist[u] + w;
                if (nd < dist[v] - 1e-12) { dist[v] = nd; prev[v] = u; q.add(new long[] {v, Double.doubleToLongBits(nd)}); }
            }
        }
        if (Double.isInfinite(dist[dst])) return null;
        LinkedList<Coord> out = new LinkedList<>();
        for (int v = dst; v != -1; v = prev[v]) out.addFirst(ctx.sector(v).at());
        return out;
    }
}
