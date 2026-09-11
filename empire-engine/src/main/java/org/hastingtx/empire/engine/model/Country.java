package org.hastingtx.empire.engine.model;

import org.hastingtx.empire.engine.config.HandicapCfg;

/**
 * A player. No controller flag lives here on purpose: whether a country is a human or an
 * agent is server-side bookkeeping, never world state, so it can never leak into a view.
 */
public record Country(
        int id,
        String name,
        Coord capital,
        double cash,
        double btu,
        Levels levels,
        HandicapCfg handicap,
        boolean inSanctuary,
        boolean bankrupt,
        int plagueUpdatesLeft) {

    /** Rename (issue #119). Uniqueness within a game is the server's business, not the engine's. */
    public Country withName(String n) { return new Country(id, n, capital, cash, btu, levels, handicap, inSanctuary, bankrupt, plagueUpdatesLeft); }

    public Country withCash(double c) { return new Country(id, name, capital, c, btu, levels, handicap, inSanctuary, bankrupt, plagueUpdatesLeft); }
    public Country withBtu(double b) { return new Country(id, name, capital, cash, b, levels, handicap, inSanctuary, bankrupt, plagueUpdatesLeft); }
    public Country withLevels(Levels l) { return new Country(id, name, capital, cash, btu, l, handicap, inSanctuary, bankrupt, plagueUpdatesLeft); }
    public Country withSanctuary(boolean s) { return new Country(id, name, capital, cash, btu, levels, handicap, s, bankrupt, plagueUpdatesLeft); }
    public Country withBankrupt(boolean b) { return new Country(id, name, capital, cash, btu, levels, handicap, inSanctuary, b, plagueUpdatesLeft); }
    public Country withHandicap(HandicapCfg h) { return new Country(id, name, capital, cash, btu, levels, h, inSanctuary, bankrupt, plagueUpdatesLeft); }
    public Country withCapital(Coord c) { return new Country(id, name, c, cash, btu, levels, handicap, inSanctuary, bankrupt, plagueUpdatesLeft); }
}
