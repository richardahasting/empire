import { useState } from "react";
import { Button } from "@/components/ui/button";

/**
 * A bot's freshly minted SESSION token, labelled for what it is (issue #161). It looks like the
 * token in a sign-in link and is not one: an agent handed it has, more than once, gone looking
 * for a verify step to put it through. The copy here says so before the token does.
 */
export function SessionTokenNote() {
  return (
    <>
      This is a <strong>session token</strong>, not a sign-in link. The bot sends it as
      {" "}<code>Authorization: Bearer …</code> on every request (<code>empire-cli.py --token …</code> keeps it
      in <code>~/.config/empire/session</code>). There is nothing to verify — it is already signed in.
      It is shown once; only its hash is stored, so a lost one needs a new one from the deity.
    </>
  );
}

export function SessionTokenBox({ token }: { token: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try { await navigator.clipboard.writeText(token); setCopied(true); } catch { setCopied(false); }
  };
  return (
    <div className="space-y-2 py-2">
      <code className="block break-all rounded-[var(--radius)] border border-border bg-muted p-2 text-xs">{token}</code>
      <Button size="sm" variant="soft" onClick={() => void copy()}>{copied ? "Copied" : "Copy session token"}</Button>
    </div>
  );
}
