package org.hastingtx.empire.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.model.Coord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #312: the mine verbs were finished in the engine, the console and the guide, but no panel sent them, so
 * nothing held the web client's JSON to the shape {@code toCommand} reads. These are the exact bodies the Fleet,
 * Army and Air panels now POST to {@code /command} — field for field, as the browser serialises them. If a field
 * is ever renamed on either side, this fails instead of the button quietly doing nothing.
 */
class MineCommandShapesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Command of(String body) throws Exception {
        return JSON.readValue(body, GameController.CommandRequest.class).toCommand();
    }

    /** Fleet.tsx LayMinesDialog: a minelayer at sea, a shell a mine. */
    @Test
    void layComesFromTheFleetPanel() throws Exception {
        assertThat(of("""
                {"verb":"lay","ship":3,"amount":12}"""))
                .isEqualTo(new Command.Lay(3, 12));
    }

    /** Army.tsx LandMineDialog: an engineer ashore, capped by its mobility and the shells to hand. */
    @Test
    void lmineComesFromTheArmyPanel() throws Exception {
        assertThat(of("""
                {"verb":"lmine","unit":5,"amount":4}"""))
                .isEqualTo(new Command.LandMine(5, 4));
    }

    /** Air.tsx SweepDialog: the flight in "planes", the escorts in "units" — not the other way round. */
    @Test
    void sweepComesFromTheAirPanel() throws Exception {
        assertThat(of("""
                {"verb":"sweep","planes":[7,8],"x":10,"y":4,"units":[9]}"""))
                .isEqualTo(new Command.SweepAir(List.of(7L, 8L), new Coord(10, 4), List.of(9L)));
    }

    /** A sweep with nobody along is still a sweep: the empty escort list must not become null. */
    @Test
    void sweepWithoutEscorts() throws Exception {
        assertThat(of("""
                {"verb":"sweep","planes":[7],"x":10,"y":4,"units":[]}"""))
                .isEqualTo(new Command.SweepAir(List.of(7L), new Coord(10, 4), List.of()));
    }

    /**
     * Air.tsx TransportDialog: air minelaying is a drop of shell, and the sea hex is the whole difference —
     * {@code Air.drop} routes it to {@code mineDrop} on the terrain, not on the verb. The client used to refuse
     * this body itself, because it asked for a sector you own and nobody owns the sea.
     */
    @Test
    void droppingShellsIsHowPlanesLayMines() throws Exception {
        assertThat(of("""
                {"verb":"drop","planes":[7],"x":10,"y":4,"commodity":"shell"}"""))
                .isEqualTo(new Command.Drop(List.of(7L), new Coord(10, 4), "shell", List.of()));
    }
}
