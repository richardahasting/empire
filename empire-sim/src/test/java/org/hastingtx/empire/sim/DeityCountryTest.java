package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.model.Country;
import org.hastingtx.empire.engine.model.Sector;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.view.CountryView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** Issue #128: POGO, the deity's country at the origin, seeing everything and doing nothing. */
class DeityCountryTest {

    private static final GameConfig CFG = new ConfigLoader().loadPreset("teaching").config();
    private static final long SEED = 11L;

    private static World withDeity() {
        World w = new WorldGenerator(CFG).generate(List.of("A", "B"), SEED);
        return new WorldGenerator(CFG).addDeity(w, "POGO", SEED);
    }

    @Test
    void theDeityIsAtTheOrigin() {
        World w = withDeity();
        Country pogo = w.countries().get(w.countries().size() - 1);
        assertThat(pogo.name()).isEqualTo("POGO");
        assertThat(pogo.capital()).isEqualTo(new Coord(0, 0));
        assertThat(w.sector(new Coord(0, 0)).owner()).isEqualTo(pogo.id());
        assertThat(w.sector(new Coord(0, 0)).terrain().isLand()).isTrue();
    }

    /**
     * The origin gets most of the way there: relativising against a capital of 0,0 is the identity —
     * but only within half a world. Past that, wrapping folds a hex to the short way round, which is
     * right for a player and useless for a tool that edits a sector by name.
     */
    @Test
    void relativisingAgainstTheOriginIsTheIdentityUntilTheWorldWraps() {
        World w = withDeity();
        Coord origin = new Coord(0, 0);
        assertThat(CountryView.relative(w, origin, new Coord(3, 4))).isEqualTo(new Coord(3, 4));
        assertThat(CountryView.relative(w, origin, new Coord(10, 2)))
                .as("on a %d-wide torus the far half folds", w.width()).isEqualTo(new Coord(-6, 2));
    }

    /** So the deity's own view reports absolute coordinates outright — what you see is what you edit. */
    @Test
    void theDeityViewReportsAbsoluteCoordinates() {
        World w = withDeity();
        CountryView v = CountryView.omniscient(w, CFG, w.countries().size() - 1);
        assertThat(v.sectors()).allSatisfy(s ->
                assertThat(s.relative()).as("%s should report as itself", s.at()).isEqualTo(s.at()));
        assertThat(v.sectors()).anySatisfy(s -> assertThat(s.at()).isEqualTo(new Coord(10, 2)));
    }

    /** And a player's view is unchanged: still relative, still folded by the wrap. */
    @Test
    void aPlayerStillSeesRelativeCoordinates() {
        World w = withDeity();
        CountryView v = CountryView.of(w, CFG, 0);
        Coord cap = w.country(0).capital();
        assertThat(v.sectors()).allSatisfy(s ->
                assertThat(s.relative()).isEqualTo(CountryView.relative(w, cap, s.at())));
        assertThat(v.sectors()).anySatisfy(s -> assertThat(s.relative()).isEqualTo(new Coord(0, 0)));
    }

    @Test
    void theDeityHoldsTwoSanctuarySectorsAndNothingElse() {
        World w = withDeity();
        int id = w.countries().size() - 1;
        List<Sector> mine = w.sectors().stream().filter(s -> s.owner() == id).toList();
        assertThat(mine).hasSize(2);
        assertThat(mine).allSatisfy(s -> assertThat(s.sanctuary()).as("sanctuary means invisible and inert").isTrue());
        assertThat(w.country(id).inSanctuary()).isTrue();
    }

    @Test
    void thePlayersAreUntouched() {
        World before = new WorldGenerator(CFG).generate(List.of("A", "B"), SEED);
        World after = new WorldGenerator(CFG).addDeity(before, "POGO", SEED);
        for (int i = 0; i < before.sectors().size(); i++) {
            Sector b = before.sectors().get(i), a = after.sectors().get(i);
            if (!b.owned()) continue;
            assertThat(a.owner()).as("%s changed hands", b.at()).isEqualTo(b.owner());
            assertThat(a.stock()).isEqualTo(b.stock());
        }
        assertThat(after.country(0).name()).isEqualTo("A");
        assertThat(after.country(1).name()).isEqualTo("B");
    }

    /** The deity sees the whole map; a player sees only what a player may. */
    @Test
    void theFogLiftsForTheDeityAndNobodyElse() {
        World w = withDeity();
        int id = w.countries().size() - 1;
        CountryView all = CountryView.omniscient(w, CFG, id);
        assertThat(all.sectors()).hasSize(w.width() * w.height());

        CountryView player = CountryView.of(w, CFG, 0);
        assertThat(player.sectors().size()).as("a player still has fog").isLessThan(w.width() * w.height());
    }

    @Test
    void theDeitysOwnSectorsAreInvisibleToPlayers() {
        World w = withDeity();
        CountryView player = CountryView.of(w, CFG, 0);
        assertThat(player.sectors()).noneSatisfy(s ->
                assertThat(s.owner()).as("a player should never be shown a deity sector as owned").isEqualTo(w.countries().size() - 1));
    }

    @Test
    void aWorldWithADeityStillUpdates() {
        World w = withDeity();
        var r = Update.run(w, CFG, SEED);
        assertThat(r.next().countries()).hasSize(3);
        assertThat(r.next().country(2).name()).isEqualTo("POGO");
    }

    @Test
    void thereIsOnlyEverOneDeity() {
        World w = withDeity();
        assertThatThrownBy(() -> new WorldGenerator(CFG).addDeity(w, "POGO", SEED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already");
    }
}
