import { useState } from "react";
import type { CommandRequest, CountryView, Macro, MacroStep } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";

/**
 * Macros (issue #47): recorded panel actions with the sector left blank. Nobody types syntax —
 * you record by playing, run by pointing, and read each slot as plain sentences.
 */
export const MACRO_SLOTS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
const MACRO_VERBS = new Set(["threshold", "distribute", "deliver", "build_road", "build_rail", "designate"]);

/** The step a panel command becomes while recording; null if it is not the kind of thing a macro can hold. */
export function stepFromCommand(c: CommandRequest, view: CountryView): MacroStep | null {
  if (!MACRO_VERBS.has(c.verb) || c.scope) return null;
  const s: MacroStep = { verb: c.verb };
  if (c.commodity) s.commodity = c.commodity;
  if (c.amount !== undefined) s.amount = c.amount;
  if (c.clear) s.clear = true;
  if (c.type) s.type = c.type;
  if (c.direction) s.direction = c.direction;
  if (c.verb === "distribute" && !c.clear) {
    if (c.x2 === undefined || c.y2 === undefined || (c.x2 === view.capital.x && c.y2 === view.capital.y)) s.center = "capital";
    else { const t = view.sectors.find(v => v.at.x === c.x2 && v.at.y === c.y2); s.center = t ? { dx: t.relative.x, dy: t.relative.y } : "capital"; }
  }
  return s;
}

const q = (v: number | undefined) => v === undefined ? "?" : Number.isInteger(v) ? String(v) : String(v);

/** One sentence per step — the same wording the console prints. */
export function describeStep(s: MacroStep): string {
  switch (s.verb) {
    case "threshold": return s.clear ? `clear threshold ${s.commodity}` : `threshold ${s.commodity} ${q(s.amount)}`;
    case "distribute": return s.clear ? "clear distribution centre" : `distribution centre: ${!s.center || s.center === "capital" ? "capital" : `${s.center.dx},${s.center.dy} from the capital`}`;
    case "deliver": return s.clear ? `stop delivering ${s.commodity}` : `deliver ${s.commodity} ${s.direction} above ${q(s.amount)}`;
    case "build_road": return (s.amount ?? 0) <= 0 ? "cancel road order" : `road toward ${q(s.amount)}`;
    case "build_rail": return (s.amount ?? 0) <= 0 ? "cancel rail order" : `rail toward ${q(s.amount)}`;
    case "designate": return `designate ${s.type}`;
    default: return `? ${s.verb}`;
  }
}

export function describeMacro(m: Macro): string { return m.steps.map(describeStep).join("; "); }

/** Name and slot for a new recording. */
export function RecordMacroDialog({ macros, onClose, onStart }: { macros: Macro[]; onClose: () => void; onStart: (slot: number, name: string) => void }) {
  const free = MACRO_SLOTS.find(s => !macros.some(m => m.slot === s)) ?? 1;
  const [slot, setSlot] = useState(free);
  const [name, setName] = useState("");
  const taken = macros.find(m => m.slot === slot);
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Record a macro</DialogTitle><DialogDescription>Then just play: every threshold, centre, delivery, road, rail or designation you set through the dialogs is captured with the sector left blank. Stop when you are done.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label>Name<Input value={name} onChange={e => setName(e.target.value)} placeholder="e.g. settle" autoFocus maxLength={40} /></label>
          <label>Slot (its key on the map)
            <Select value={String(slot)} onChange={e => setSlot(Number(e.target.value))}>
              {MACRO_SLOTS.map(s => { const m = macros.find(x => x.slot === s); return <option key={s} value={s}>{s === 10 ? "0" : s} · {m ? `${m.name} (replace)` : "empty"}</option>; })}
            </Select>
          </label>
          {taken && <p className="text-xs text-destructive">Recording replaces "{taken.name}" in this slot.</p>}
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={!name.trim()} onClick={() => { onStart(slot, name.trim()); onClose(); }}>Start recording</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Run a macro on this sector, all your sectors, or all of this designation. */
export function RunMacroDialog({ macros, sectorLabel, designation, onClose, onRun }: { macros: Macro[]; sectorLabel: string; designation: string | null; onClose: () => void; onRun: (slot: number, scope: string) => void }) {
  const [slot, setSlot] = useState(macros[0]?.slot ?? 1);
  const [scope, setScope] = useState("");
  const m = macros.find(x => x.slot === slot);
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent>
        <DialogHeader><DialogTitle>Run a macro</DialogTitle><DialogDescription>Each step is an ordinary command and costs its usual BTU per sector.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          <label>Macro
            <Select value={String(slot)} onChange={e => setSlot(Number(e.target.value))}>
              {macros.map(x => <option key={x.slot} value={x.slot}>{x.slot === 10 ? "0" : x.slot} · {x.name}</option>)}
            </Select>
          </label>
          {m && <ol className="list-decimal pl-5 text-xs text-muted-foreground">{m.steps.map((s, i) => <li key={i}>{describeStep(s)}</li>)}</ol>}
          <label>Apply to
            <Select value={scope} onChange={e => setScope(e.target.value)}>
              <option value="">this sector only ({sectorLabel})</option>
              <option value="*">all my sectors</option>
              {designation && <option value={`*:${designation}`}>all my {designation} sectors</option>}
            </Select>
          </label>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button disabled={!m} onClick={() => { onRun(slot, scope); onClose(); }}>Run</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** See and edit every slot: what each key does, in sentences; numbers editable in place; steps removable and reorderable. */
export function MacrosDialog({ macros, onClose, onSave, onDelete }: { macros: Macro[]; onClose: () => void; onSave: (m: Macro) => Promise<void>; onDelete: (slot: number) => Promise<void> }) {
  const [drafts, setDrafts] = useState<Record<number, Macro>>(() => Object.fromEntries(macros.map(m => [m.slot, structuredClone(m)])));
  const [busy, setBusy] = useState(false);
  const setDraft = (slot: number, f: (m: Macro) => Macro) => setDrafts(d => ({ ...d, [slot]: f(d[slot]) }));
  return (
    <Dialog open onOpenChange={o => { if (!o) onClose(); }}>
      <DialogContent className="max-h-[85vh] overflow-auto sm:max-w-2xl">
        <DialogHeader><DialogTitle>Macros</DialogTitle><DialogDescription>What each key does. With the map focused and a sector selected, keys 1 to 9 and 0 run slots 1 to 10 there. Record a new one from any sector's right-click menu.</DialogDescription></DialogHeader>
        <div className="grid gap-3 text-sm">
          {MACRO_SLOTS.map(slot => {
            const m = drafts[slot];
            return (
              <div key={slot} className="rounded-md border border-border p-2">
                <div className="flex items-center gap-2">
                  <span className="w-6 text-center font-mono text-xs text-muted-foreground">{slot === 10 ? "0" : slot}</span>
                  {m ? <Input value={m.name} onChange={e => setDraft(slot, x => ({ ...x, name: e.target.value }))} className="max-w-48" maxLength={40} /> : <span className="text-muted-foreground">empty</span>}
                  {m && <span className="ml-auto flex gap-1">
                    <Button size="sm" variant="secondary" disabled={busy || !m.name.trim() || m.steps.length === 0} onClick={async () => { setBusy(true); try { await onSave(m); } finally { setBusy(false); } }}>Save</Button>
                    <Button size="sm" variant="danger" disabled={busy} onClick={async () => { setBusy(true); try { await onDelete(slot); setDrafts(d => { const n = { ...d }; delete n[slot]; return n; }); } finally { setBusy(false); } }}>Delete</Button>
                  </span>}
                </div>
                {m && (
                  <ol className="mt-1 list-decimal pl-8 text-xs">
                    {m.steps.map((s, i) => (
                      <li key={i} className="flex items-center gap-2 py-0.5">
                        <span className="flex-1">{describeStep(s)}</span>
                        {s.amount !== undefined && !s.clear && <Input value={String(s.amount)} inputMode="numeric" className="w-20" aria-label="amount" onChange={e => { const v = Number(e.target.value); setDraft(slot, x => ({ ...x, steps: x.steps.map((t, j) => j === i ? { ...t, amount: Number.isFinite(v) ? v : t.amount } : t) })); }} />}
                        <Button size="sm" variant="ghost" disabled={i === 0} aria-label="move up" onClick={() => setDraft(slot, x => { const st = [...x.steps]; [st[i - 1], st[i]] = [st[i], st[i - 1]]; return { ...x, steps: st }; })}>↑</Button>
                        <Button size="sm" variant="ghost" disabled={i === m.steps.length - 1} aria-label="move down" onClick={() => setDraft(slot, x => { const st = [...x.steps]; [st[i + 1], st[i]] = [st[i], st[i + 1]]; return { ...x, steps: st }; })}>↓</Button>
                        <Button size="sm" variant="ghost" aria-label="remove step" onClick={() => setDraft(slot, x => ({ ...x, steps: x.steps.filter((_, j) => j !== i) }))}>✕</Button>
                      </li>
                    ))}
                  </ol>
                )}
              </div>
            );
          })}
        </div>
        <DialogFooter><Button variant="ghost" onClick={onClose}>Close</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
