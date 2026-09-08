import { useEffect, useRef, useState } from "react";
import { Input } from "@/components/ui/input";

interface Props { onLine: (line: string) => Promise<{ output: string; accepted: boolean; error?: string }> }

/** The text command line: readline-style history, same command API as the panels. */
export function ConsolePanel({ onLine }: Props) {
  const [lines, setLines] = useState<string[]>(["Empire console. Type help."]);
  const [input, setInput] = useState("");
  const history = useRef<string[]>([]);
  const cursor = useRef(-1);
  const out = useRef<HTMLPreElement>(null);

  useEffect(() => { out.current?.scrollTo({ top: out.current.scrollHeight }); }, [lines]);

  const submit = async () => {
    const line = input.trim(); if (!line) return;
    history.current.push(line); cursor.current = history.current.length; setInput("");
    setLines(l => [...l, "> " + line]);
    try {
      const r = await onLine(line);
      setLines(l => [...l, r.error ? "! " + r.error : r.output || "ok"]);
    } catch (e) { setLines(l => [...l, "! " + (e as Error).message]); }
  };
  const onKey = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") { void submit(); }
    else if (e.key === "ArrowUp") { e.preventDefault(); if (cursor.current > 0) { cursor.current--; setInput(history.current[cursor.current] ?? ""); } }
    else if (e.key === "ArrowDown") { e.preventDefault(); if (cursor.current < history.current.length) { cursor.current++; setInput(history.current[cursor.current] ?? ""); } }
  };
  return (
    <div className="flex h-full flex-col gap-2">
      <pre ref={out} className="min-h-0 flex-1 overflow-auto rounded-md border border-border bg-muted p-2 font-mono text-xs leading-snug whitespace-pre">{lines.join("\n")}</pre>
      <Input value={input} onChange={e => setInput(e.target.value)} onKeyDown={onKey} placeholder="map · census · des 1,0 agribusiness · thresh * hcm 100 · road *:a 100 · dist 1,0 0,0" className="font-mono text-xs" />
    </div>
  );
}
