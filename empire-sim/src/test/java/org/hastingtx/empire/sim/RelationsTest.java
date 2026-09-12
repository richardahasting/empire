package org.hastingtx.empire.sim;

import org.hastingtx.empire.config.ConfigLoader;
import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.command.CommandExecutor;
import org.hastingtx.empire.engine.config.GameConfig;
import org.hastingtx.empire.engine.gen.WorldGenerator;
import org.hastingtx.empire.engine.model.Relation;
import org.hastingtx.empire.engine.model.World;
import org.hastingtx.empire.engine.update.Update;
import org.hastingtx.empire.engine.view.CountryView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Issue #137. Declaring war is unilateral; being at war is mutual. That is enforced by there being
 * one row per pair rather than by two rows agreeing — a one-sided war where the victim's ships do
 * not shoot back is not a war, and structure is a better guarantee than discipline.
 */
class RelationsTest {

    private static final GameConfig CFG = new ConfigLoader().loadPreset("teaching").config();
    private static final long SEED = 11L;

    /** Three countries, out of sanctuary so they can act. */
    private static World world() {
        World w = new WorldGenerator(CFG).generate(List.of("A", "B", "C"), SEED);
        for (int i = 0; i < 3; i++) w = new CommandExecutor(CFG).execute(w, i, new Command.BreakSanctuary()).world();
        return w;
    }

    private static World run(World w, int country, Command c) {
        var r = new CommandExecutor(CFG).execute(w, country, c);
        assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
        return r.world();
    }

    @Test
    void countriesStartAtPeaceWithNothingRecorded() {
        World w = world();
        assertThat(w.relations()).isEmpty();
        assertThat(w.atWar(0, 1)).isFalse();
    }

    /** The point of one row per pair: B is at war with A without having done anything. */
    @Test
    void warIsMutualTheMomentItIsDeclared() {
        World w = run(world(), 0, new Command.DeclareWar(1));
        assertThat(w.atWar(0, 1)).isTrue();
        assertThat(w.atWar(1, 0)).as("the same question, the other way round").isTrue();
        assertThat(w.relations()).singleElement().satisfies(r -> {
            assertThat(r.a()).isLessThan(r.b());
            assertThat(r.state()).isEqualTo(Relation.WAR);
        });
    }

    @Test
    void aThirdCountryIsNotDraggedIn() {
        World w = run(world(), 0, new Command.DeclareWar(1));
        assertThat(w.atWar(0, 2)).isFalse();
        assertThat(w.atWar(1, 2)).isFalse();
    }

    /** Peace needs both. A war you can end alone costs nothing to start. */
    @Test
    void oneSideCannotSimplyStopTheWar() {
        World w = run(world(), 0, new Command.DeclareWar(1));
        w = run(w, 0, new Command.OfferPeace(1));
        assertThat(w.atWar(0, 1)).as("offering is not ending").isTrue();
        assertThat(w.relation(0, 1).peaceOfferedBy()).isEqualTo(0);

        var again = new CommandExecutor(CFG).execute(w, 0, new Command.OfferPeace(1));
        assertThat(again.error()).contains("theirs to accept");
    }

    @Test
    void peaceComesWhenTheOtherSideAccepts() {
        World w = run(world(), 0, new Command.DeclareWar(1));
        w = run(w, 0, new Command.OfferPeace(1));
        w = run(w, 1, new Command.OfferPeace(0));
        assertThat(w.atWar(0, 1)).isFalse();
        assertThat(w.relation(0, 1).state()).isEqualTo(Relation.PEACE);
        assertThat(w.relation(0, 1).peaceOfferedBy()).as("the offer is spent").isNull();
    }

    @Test
    void sanctuaryIsNoPlaceForAWar() {
        World w = new WorldGenerator(CFG).generate(List.of("A", "B"), SEED);
        assertThat(new CommandExecutor(CFG).execute(w, 0, new Command.DeclareWar(1)).error())
                .contains("break sanctuary");

        World out = new CommandExecutor(CFG).execute(w, 0, new Command.BreakSanctuary()).world();
        assertThat(new CommandExecutor(CFG).execute(out, 0, new Command.DeclareWar(1)).error())
                .as("and you cannot hit somebody who is still in it").contains("still in sanctuary");
    }

    @Test
    void theObviousMistakesAreRefused() {
        World w = world();
        assertThat(new CommandExecutor(CFG).execute(w, 0, new Command.DeclareWar(0)).error()).contains("on yourself");
        assertThat(new CommandExecutor(CFG).execute(w, 0, new Command.DeclareWar(99)).error()).contains("no such country");
        assertThat(new CommandExecutor(CFG).execute(w, 0, new Command.OfferPeace(1)).error()).contains("not at war");
        World atWar = run(w, 0, new Command.DeclareWar(1));
        assertThat(new CommandExecutor(CFG).execute(atWar, 0, new Command.DeclareWar(1)).error()).contains("already at war");
    }

    /** Both sides are told, because a declaration is not a secret once made. */
    @Test
    void bothSidesSeeTheWarInTheirOwnView() {
        World w = run(world(), 0, new Command.DeclareWar(1));
        assertThat(CountryView.of(w, CFG, 0).atWarWith()).containsExactly("B");
        assertThat(CountryView.of(w, CFG, 1).atWarWith()).containsExactly("A");
        assertThat(CountryView.of(w, CFG, 2).atWarWith()).as("and it is not their war").isEmpty();
    }

    /** Relations are standing: an update must not quietly make peace. */
    @Test
    void aWarSurvivesTheUpdate() {
        World w = run(world(), 0, new Command.DeclareWar(1));
        World after = Update.run(w, CFG, SEED).next();
        assertThat(after.atWar(0, 1)).isTrue();
        assertThat(after.relations()).hasSize(1);
    }

    @Test
    void aPairIsStoredOneWayRoundWhicheverWayItWasDeclared() {
        World fromHigh = run(world(), 2, new Command.DeclareWar(0));
        assertThat(fromHigh.relations()).singleElement().satisfies(r -> {
            assertThat(r.a()).isZero();
            assertThat(r.b()).isEqualTo(2);
        });
    }
}
