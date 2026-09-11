package org.hastingtx.empire.server.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #118: the console's help text and the guide's command reference are one file. If the guide
 * stops being packaged, or its quick-reference block is renamed or removed, `help` silently degrades
 * to an apology — so the contract is pinned here rather than discovered in a game.
 */
class HelpTest {

    @Test
    void theHelpTextComesFromTheGuide() {
        String help = Console.HELP;
        assertFalse(help.startsWith("the command reference"), "the guide was not on the classpath: " + help);
        assertTrue(help.contains("map"), help);
        assertTrue(help.contains("des SECTOR TYPE"), help);
        assertTrue(help.contains("thresh SECTOR COMMODITY N"), help);
        assertFalse(help.contains("```"), "the fence markers should be stripped");
    }

    @Test
    void everyConsoleCommandAppearsInTheReference() {
        // the commands a player can type, as the console dispatches them
        String[] commands = {"map", "census", "break", "des", "thresh", "dist", "deliver", "macro", "ships",
                "contacts", "build", "sail", "load", "unload", "lane", "fish", "scrap", "move", "expl",
                "road", "rail", "railship", "raillane"};
        for (String c : commands)
            assertTrue(Console.HELP.contains(c), "'" + c + "' is dispatched by the console but not in the reference");
    }
}
