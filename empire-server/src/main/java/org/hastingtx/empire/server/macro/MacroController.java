package org.hastingtx.empire.server.macro;

import jakarta.servlet.http.HttpServletRequest;
import org.hastingtx.empire.engine.command.Command;
import org.hastingtx.empire.engine.model.Coord;
import org.hastingtx.empire.engine.view.CountryView;
import org.hastingtx.empire.server.auth.Account;
import org.hastingtx.empire.server.auth.AuthInterceptor;
import org.hastingtx.empire.server.console.SectorSelector;
import org.hastingtx.empire.server.game.GameService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Macros: per account, up to ten slots. Issue #47. */
@RestController
@RequestMapping("/api")
public class MacroController {
    private final MacroRepository macros;
    private final GameService games;
    public MacroController(MacroRepository macros, GameService games) { this.macros = macros; this.games = games; }

    public record MacroOut(int slot, String name, List<Map<String, Object>> steps, String summary) {
        static MacroOut of(MacroRepository.MacroRow r) { return new MacroOut(r.slot(), r.name(), r.steps(), Macros.describe(r.steps())); }
    }
    public record MacroIn(String name, List<Map<String, Object>> steps) {}
    public record RunRequest(Integer x, Integer y, String scope) {}

    @GetMapping("/macros")
    public List<MacroOut> list(HttpServletRequest req) { return macros.list(AuthInterceptor.current(req).id()).stream().map(MacroOut::of).toList(); }

    @PutMapping("/macros/{slot}")
    public MacroOut put(@PathVariable int slot, @RequestBody MacroIn m, HttpServletRequest req) {
        if (slot < 1 || slot > Macros.SLOTS) throw new IllegalArgumentException("slot is 1.." + Macros.SLOTS);
        Macros.validate(m.name(), m.steps());
        long a = AuthInterceptor.current(req).id();
        macros.put(a, slot, m.name().trim(), m.steps());
        return MacroOut.of(macros.find(a, slot).orElseThrow());
    }

    @DeleteMapping("/macros/{slot}")
    public Map<String, Object> delete(@PathVariable int slot, HttpServletRequest req) {
        macros.delete(AuthInterceptor.current(req).id(), slot);
        return Map.of("slot", slot, "deleted", true);
    }

    /** Run a macro on one sector (x,y absolute) or on a selection (scope, relative). Each step is an ordinary command with its own BTU. */
    @PostMapping("/games/{id}/macros/{slot}/run")
    public GameService.Outcome run(@PathVariable long id, @PathVariable int slot, @RequestBody RunRequest r, HttpServletRequest req) {
        Account a = AuthInterceptor.current(req);
        MacroRepository.MacroRow m = macros.find(a.id(), slot).orElseThrow(() -> new IllegalArgumentException("no macro in slot " + slot));
        CountryView v = games.view(id, a);
        var cfg = games.get(id).cfg;
        boolean mass = r.scope() != null && !r.scope().isBlank();
        List<Coord> sectors = mass ? SectorSelector.expand(v, cfg, r.scope()) : List.of(new Coord(r.x() == null ? 0 : r.x(), r.y() == null ? 0 : r.y()));
        if (!mass && (r.x() == null || r.y() == null)) throw new IllegalArgumentException("a sector or a scope is required");
        boolean mixed = mass && SectorSelector.isMixed(r.scope());
        List<Command> cmds = new ArrayList<>();
        for (Coord at : sectors) cmds.addAll(Macros.expand(m.steps(), v, cfg, at, mixed));
        return games.commandAll(id, a, cmds, "macro", "macro " + slot + " '" + m.name() + "'" + (sectors.size() > 1 ? " on " + sectors.size() + " sectors" : ""));
    }
}
