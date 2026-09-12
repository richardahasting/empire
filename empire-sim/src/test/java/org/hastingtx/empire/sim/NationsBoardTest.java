package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.score.NationsBoard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #120. The board is built from full world knowledge, so the thing worth testing is not that
 * it renders — it is that it does not leak. Territory and population are exactly what detection and
 * map memory make expensive to learn; a board that hands them over undoes that quietly.
 */
class NationsBoardTest {

    private static GameConfig cfg(String visibility) {
        ConfigLoader loader = new ConfigLoader();
        var raw = new java.util.LinkedHashMap<>(loader.loadPreset("teaching").raw());
        @SuppressWarnings("unchecked")
        var scoring = new java.util.LinkedHashMap<>((Map<String, Object>) raw.get("scoring"));
        scoring.put("visibility", visibility);
        raw.put("scoring", scoring);
        return loader.loadYaml(loader.toYaml(raw)).config();
    }

    private static World world(GameConfig c) { return new WorldGenerator(c).generate(List.of("A", "B", "C"), 11L); }

    @Test
    void everyoneIsRankedAndYouAreMarked() {
        GameConfig c = cfg("banded");
        List<NationsBoard.Standing> board = NationsBoard.of(c, world(c), 1);
        assertThat(board).hasSize(3);
        assertThat(board).extracting(NationsBoard.Standing::rank).containsExactly(1, 2, 3);
        assertThat(board).filteredOn(NationsBoard.Standing::you).singleElement()
                .satisfies(s -> assertThat(s.countryId()).isEqualTo(1));
    }

    /** The whole point: banded tells you the order and a word, and no numbers about anybody else. */
    @Test
    void bandedTellsYouNothingCountableAboutARival() {
        GameConfig c = cfg("banded");
        for (NationsBoard.Standing s : NationsBoard.of(c, world(c), 1)) {
            if (s.you()) continue;
            assertThat(s.band()).as("a rival still gets a word").isNotBlank();
            assertThat(s.score()).as("but not a score").isNull();
            assertThat(s.sectors()).as("nor a territory count").isNull();
            assertThat(s.civilians()).as("nor a population").isNull();
            assertThat(s.tech()).as("nor a tech level").isNull();
        }
    }

    @Test
    void yourOwnFiguresAreAlwaysExact() {
        GameConfig c = cfg("banded");
        var me = NationsBoard.of(c, world(c), 1).stream().filter(NationsBoard.Standing::you).findFirst().orElseThrow();
        assertThat(me.score()).isNotNull();
        assertThat(me.sectors()).isNotNull().isGreaterThan(0);
        assertThat(me.civilians()).isNotNull().isGreaterThan(0.0);
    }

    @Test
    void rankGivesTheOrderAndNotEvenAWord() {
        GameConfig c = cfg("rank");
        for (NationsBoard.Standing s : NationsBoard.of(c, world(c), 1)) {
            if (s.you()) continue;
            assertThat(s.band()).isNull();
            assertThat(s.score()).isNull();
        }
    }

    @Test
    void exactPublishesEverythingToEverybody() {
        GameConfig c = cfg("exact");
        assertThat(NationsBoard.of(c, world(c), 1)).allSatisfy(s -> {
            assertThat(s.score()).isNotNull();
            assertThat(s.sectors()).isNotNull();
        });
    }

    /** The deity is not a player and sees the board whole. */
    @Test
    void theDeitySeesEverything() {
        GameConfig c = cfg("banded");
        assertThat(NationsBoard.of(c, world(c), -1)).allSatisfy(s -> assertThat(s.score()).isNotNull());
    }

    /** Somebody in no country at all is told nothing countable about anyone. */
    @Test
    void anOutsiderLearnsNothing() {
        GameConfig c = cfg("banded");
        assertThat(NationsBoard.of(c, world(c), -2)).allSatisfy(s -> {
            assertThat(s.you()).isFalse();
            assertThat(s.score()).isNull();
            assertThat(s.sectors()).isNull();
        });
    }

    @Test
    void theOrderIsByScoreBestFirst() {
        GameConfig c = cfg("exact");
        var board = NationsBoard.of(c, world(c), -1);
        for (int i = 1; i < board.size(); i++)
            assertThat(board.get(i - 1).score()).isGreaterThanOrEqualTo(board.get(i).score());
    }
}
