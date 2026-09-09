package org.hastingtx.empire.engine.model;

import java.util.ArrayList;
import java.util.List;

/** Complete world state. Immutable. Sectors are indexed y * width + x. */
public record World(
        int width,
        int height,
        boolean wrapX,
        boolean wrapY,
        List<Sector> sectors,
        List<Country> countries,
        List<MoveOrder> pendingMoves,
        long updateNumber,
        List<RailOrder> pendingRail,
        /** Ships (issue #56), ids unique for the life of the world. */
        List<Ship> ships,
        long nextShipId) {

    public World(int width, int height, boolean wrapX, boolean wrapY, List<Sector> sectors, List<Country> countries, List<MoveOrder> pendingMoves, long updateNumber) {
        this(width, height, wrapX, wrapY, sectors, countries, pendingMoves, updateNumber, List.of());
    }
    public World(int width, int height, boolean wrapX, boolean wrapY, List<Sector> sectors, List<Country> countries, List<MoveOrder> pendingMoves, long updateNumber, List<RailOrder> pendingRail) {
        this(width, height, wrapX, wrapY, sectors, countries, pendingMoves, updateNumber, pendingRail, List.of(), 1);
    }

    public World {
        sectors = List.copyOf(sectors);
        countries = List.copyOf(countries);
        pendingMoves = List.copyOf(pendingMoves);
        pendingRail = List.copyOf(pendingRail);
        ships = List.copyOf(ships);
        if (sectors.size() != width * height) throw new IllegalArgumentException("sector count != width*height");
    }

    public int index(int x, int y) { return y * width + x; }
    public int index(Coord c) { return index(c.x(), c.y()); }
    public boolean inBounds(Coord c) { return c.x() >= 0 && c.x() < width && c.y() >= 0 && c.y() < height; }
    public Sector sector(Coord c) { return sectors.get(index(c)); }
    public Sector sector(int x, int y) { return sectors.get(index(x, y)); }
    public Country country(int id) { return countries.get(id); }

    public World withSectors(List<Sector> s) { return new World(width, height, wrapX, wrapY, s, countries, pendingMoves, updateNumber, pendingRail, ships, nextShipId); }
    public World withCountries(List<Country> c) { return new World(width, height, wrapX, wrapY, sectors, c, pendingMoves, updateNumber, pendingRail, ships, nextShipId); }
    public World withPendingMoves(List<MoveOrder> m) { return new World(width, height, wrapX, wrapY, sectors, countries, m, updateNumber, pendingRail, ships, nextShipId); }
    public World withPendingRail(List<RailOrder> r) { return new World(width, height, wrapX, wrapY, sectors, countries, pendingMoves, updateNumber, r, ships, nextShipId); }
    public World withUpdateNumber(long n) { return new World(width, height, wrapX, wrapY, sectors, countries, pendingMoves, n, pendingRail, ships, nextShipId); }

    public World withShips(List<Ship> sh) { return new World(width, height, wrapX, wrapY, sectors, countries, pendingMoves, updateNumber, pendingRail, sh, nextShipId); }
    public World withShips(List<Ship> sh, long next) { return new World(width, height, wrapX, wrapY, sectors, countries, pendingMoves, updateNumber, pendingRail, sh, next); }
    public Ship ship(long id) { for (Ship sh : ships) if (sh.id() == id) return sh; return null; }
    public World withShip(Ship sh) { List<Ship> copy = new ArrayList<>(ships); for (int i = 0; i < copy.size(); i++) if (copy.get(i).id() == sh.id()) { copy.set(i, sh); return withShips(copy); } copy.add(sh); return withShips(copy); }
    public World withoutShip(long id) { List<Ship> copy = new ArrayList<>(ships); copy.removeIf(x -> x.id() == id); return withShips(copy); }
    public List<Ship> shipsAt(Coord c) { List<Ship> out = new ArrayList<>(); for (Ship sh : ships) if (sh.at().equals(c)) out.add(sh); return out; }
    public List<Ship> shipsOf(int owner) { List<Ship> out = new ArrayList<>(); for (Ship sh : ships) if (sh.owner() == owner) out.add(sh); return out; }

    public World withSector(Sector s) {
        List<Sector> copy = new ArrayList<>(sectors);
        copy.set(index(s.at()), s);
        return withSectors(copy);
    }
    public World withCountry(Country c) {
        List<Country> copy = new ArrayList<>(countries);
        copy.set(c.id(), c);
        return withCountries(copy);
    }

    public List<Sector> ownedBy(int country) {
        List<Sector> out = new ArrayList<>();
        for (Sector s : sectors) if (s.owner() == country) out.add(s);
        return out;
    }
}
