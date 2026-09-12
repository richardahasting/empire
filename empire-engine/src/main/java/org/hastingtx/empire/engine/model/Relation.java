package org.hastingtx.empire.engine.model;

/**
 * How two countries stand with one another (issue #137).
 *
 * <p>One row per pair, always with {@code a < b}, so the state is <b>mutual by construction</b>
 * rather than by two rows agreeing with each other. A one-sided war where the victim's ships do not
 * shoot back is not a war, it is target practice — and making that structural means no code can get
 * it half right.
 *
 * <p>{@code peaceOfferedBy} is the country that has offered to stop, if either has. Peace needs both:
 * a war you can end alone costs nothing to start.
 *
 * <p>This is world state, not server bookkeeping. Combat happens in the update, and the update is a
 * pure function of the world — so who is at war has to be in the world or the update cannot see it.
 */
public record Relation(int a, int b, String state, long sinceUpdate, Integer peaceOfferedBy) {

    public static final String PEACE = "peace";
    public static final String WAR = "war";

    public Relation {
        if (a == b) throw new IllegalArgumentException("a country has no relation with itself");
        if (a > b) throw new IllegalArgumentException("relations are stored with a < b, so the pair is unordered");
    }

    /** The pair, in the order this record demands, whichever way round the caller had them. */
    public static Relation of(int x, int y, String state, long since, Integer offeredBy) {
        return x < y ? new Relation(x, y, state, since, offeredBy) : new Relation(y, x, state, since, offeredBy);
    }

    public boolean involves(int country) { return a == country || b == country; }
    public boolean between(int x, int y) { return (a == x && b == y) || (a == y && b == x); }
    public int other(int country) { return country == a ? b : a; }
    public boolean atWar() { return WAR.equals(state); }

    public Relation withState(String s, long since) { return new Relation(a, b, s, since, null); }
    public Relation withPeaceOfferedBy(Integer who) { return new Relation(a, b, state, sinceUpdate, who); }
}
