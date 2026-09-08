/**
 * Design System Reference Page
 * Route: /admin/design-system
 *
 * Stack-agnostic showcase of every design-system primitive.
 * - Imports components via "@/components/ui/<name>" (the path both variants install to).
 * - Page scaffolding uses inline style={{ var(--token) }} only — NO Tailwind utility
 *   classes — so this file renders correctly under both the Tailwind and CSS variants.
 * - Dark mode is handled by ThemeToggle, which toggles the `dark` class on <html>
 *   via theme.ts (applyTheme). No wrapper `dark` class manipulation needed here.
 */
import * as React from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Checkbox } from "@/components/ui/checkbox";
import { Radio, RadioGroup } from "@/components/ui/radio";
import {
  Dialog,
  DialogTrigger,
  DialogContent,
  DialogHeader,
  DialogFooter,
  DialogTitle,
  DialogDescription,
  DialogClose,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
} from "@/components/ui/dropdown-menu";
import { ThemeToggle } from "@/components/ui/theme-toggle";

// ─── Token list (matches shared/tokens.css) ────────────────────────────────
const COLOR_TOKENS = [
  "background",
  "foreground",
  "card",
  "card-foreground",
  "popover",
  "popover-foreground",
  "primary",
  "primary-foreground",
  "secondary",
  "secondary-foreground",
  "muted",
  "muted-foreground",
  "accent",
  "accent-foreground",
  "destructive",
  "destructive-foreground",
  "border",
  "input",
  "ring",
] as const;

// ─── Layout helpers (all via inline style, no Tailwind) ────────────────────

const pageStyle: React.CSSProperties = {
  minHeight: "100vh",
  backgroundColor: "var(--background)",
  color: "var(--foreground)",
  fontFamily: "inherit",
  padding: "2rem",
  boxSizing: "border-box",
};

const headerStyle: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  marginBottom: "2.5rem",
  paddingBottom: "1rem",
  borderBottom: "1px solid var(--border)",
};

const h1Style: React.CSSProperties = {
  fontSize: "1.875rem",
  fontWeight: 700,
  color: "var(--foreground)",
  margin: 0,
};

const sectionStyle: React.CSSProperties = {
  marginBottom: "3rem",
};

const sectionHeadingStyle: React.CSSProperties = {
  fontSize: "1.125rem",
  fontWeight: 600,
  color: "var(--muted-foreground)",
  marginBottom: "1rem",
  paddingBottom: "0.5rem",
  borderBottom: "1px solid var(--border)",
};

const rowStyle: React.CSSProperties = {
  display: "flex",
  flexWrap: "wrap",
  gap: "0.5rem",
  alignItems: "center",
  marginBottom: "0.75rem",
};

const swatchGridStyle: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "repeat(auto-fill, minmax(9rem, 1fr))",
  gap: "0.75rem",
};

const swatchBoxBase: React.CSSProperties = {
  border: "1px solid var(--border)",
  borderRadius: "var(--radius, 0.375rem)",
  height: "3.5rem",
  width: "100%",
};

const swatchLabelStyle: React.CSSProperties = {
  fontSize: "0.75rem",
  color: "var(--muted-foreground)",
  marginTop: "0.25rem",
  fontFamily: "monospace",
};

const labelStyle: React.CSSProperties = {
  display: "flex",
  flexDirection: "column",
  gap: "0.375rem",
  fontSize: "0.875rem",
  color: "var(--foreground)",
  maxWidth: "18rem",
};

const inlineCheckStyle: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  gap: "0.5rem",
  fontSize: "0.875rem",
  color: "var(--foreground)",
  marginBottom: "0.5rem",
};

// ─── Sub-sections ──────────────────────────────────────────────────────────

function ColorSection() {
  return (
    <section style={sectionStyle}>
      <h2 style={sectionHeadingStyle}>Colors</h2>
      <div style={swatchGridStyle}>
        {COLOR_TOKENS.map((token) => (
          <div key={token}>
            <div style={{ ...swatchBoxBase, background: `var(--${token})` }} />
            <code style={swatchLabelStyle}>--{token}</code>
          </div>
        ))}
      </div>
    </section>
  );
}

function TypographySection() {
  return (
    <section style={sectionStyle}>
      <h2 style={sectionHeadingStyle}>Typography</h2>
      <div style={{ display: "flex", flexDirection: "column", gap: "0.75rem" }}>
        <h1 style={{ fontSize: "2.25rem", fontWeight: 800, margin: 0, color: "var(--foreground)" }}>
          Heading 1 — 36px / 800
        </h1>
        <h2 style={{ fontSize: "1.875rem", fontWeight: 700, margin: 0, color: "var(--foreground)" }}>
          Heading 2 — 30px / 700
        </h2>
        <h3 style={{ fontSize: "1.5rem", fontWeight: 600, margin: 0, color: "var(--foreground)" }}>
          Heading 3 — 24px / 600
        </h3>
        <h4 style={{ fontSize: "1.25rem", fontWeight: 600, margin: 0, color: "var(--foreground)" }}>
          Heading 4 — 20px / 600
        </h4>
        <p style={{ fontSize: "1rem", margin: 0, color: "var(--foreground)" }}>
          Body — 16px. The quick brown fox jumps over the lazy dog.
        </p>
        <p style={{ fontSize: "0.875rem", margin: 0, color: "var(--muted-foreground)" }}>
          Small / muted — 14px. Secondary descriptive text, captions, and hints.
        </p>
        <p style={{ fontSize: "0.75rem", margin: 0, fontFamily: "monospace", color: "var(--muted-foreground)" }}>
          Mono — 12px. <code>var(--token)</code> code samples.
        </p>
      </div>
    </section>
  );
}

function ButtonSection() {
  return (
    <section style={sectionStyle}>
      <h2 style={sectionHeadingStyle}>Button</h2>

      <p style={{ fontSize: "0.75rem", color: "var(--muted-foreground)", marginBottom: "0.5rem" }}>
        Variants
      </p>
      <div style={rowStyle}>
        <Button variant="primary">Primary</Button>
        <Button variant="secondary">Secondary</Button>
        <Button variant="ghost">Ghost</Button>
        <Button variant="soft">Soft</Button>
        <Button variant="danger">Danger</Button>
        <Button variant="link">Link</Button>
      </div>

      <p style={{ fontSize: "0.75rem", color: "var(--muted-foreground)", marginBottom: "0.5rem", marginTop: "0.75rem" }}>
        Sizes
      </p>
      <div style={rowStyle}>
        <Button size="sm">Small</Button>
        <Button size="md">Medium</Button>
        <Button size="lg">Large</Button>
        <Button size="icon" aria-label="Icon button">★</Button>
      </div>

      <p style={{ fontSize: "0.75rem", color: "var(--muted-foreground)", marginBottom: "0.5rem", marginTop: "0.75rem" }}>
        Disabled state
      </p>
      <div style={rowStyle}>
        <Button variant="primary" disabled>Primary disabled</Button>
        <Button variant="secondary" disabled>Secondary disabled</Button>
      </div>
    </section>
  );
}

function BadgeSection() {
  return (
    <section style={sectionStyle}>
      <h2 style={sectionHeadingStyle}>Badge</h2>
      <div style={rowStyle}>
        <Badge tone="neutral">Neutral</Badge>
        <Badge tone="accent">Accent</Badge>
        <Badge tone="signal">Signal</Badge>
        <Badge tone="muted">Muted</Badge>
        <Badge tone="solid">Solid</Badge>
      </div>
    </section>
  );
}

function InputSection() {
  return (
    <section style={sectionStyle}>
      <h2 style={sectionHeadingStyle}>Input</h2>
      <div style={{ display: "flex", flexDirection: "column", gap: "1rem" }}>
        <label style={labelStyle}>
          <span>Email address</span>
          <Input type="email" placeholder="you@example.com" />
        </label>
        <label style={labelStyle}>
          <span>Password</span>
          <Input type="password" placeholder="••••••••" />
        </label>
        <label style={{ ...labelStyle, opacity: 0.6 }}>
          <span>Disabled field</span>
          <Input type="text" placeholder="Not editable" disabled />
        </label>
      </div>
    </section>
  );
}

function SelectSection() {
  return (
    <section style={sectionStyle}>
      <h2 style={sectionHeadingStyle}>Select</h2>
      <div style={{ maxWidth: "18rem", display: "flex", flexDirection: "column", gap: "1rem" }}>
        <label style={labelStyle}>
          <span>Preferred language</span>
          <Select defaultValue="ts">
            <option value="">Pick one…</option>
            <option value="ts">TypeScript</option>
            <option value="js">JavaScript</option>
            <option value="py">Python</option>
            <option value="go">Go</option>
          </Select>
        </label>
        <label style={{ ...labelStyle, opacity: 0.6 }}>
          <span>Disabled select</span>
          <Select disabled>
            <option>Not available</option>
          </Select>
        </label>
      </div>
    </section>
  );
}

function CheckboxSection() {
  const [checked1, setChecked1] = React.useState(true);
  const [checked2, setChecked2] = React.useState(false);

  return (
    <section style={sectionStyle}>
      <h2 style={sectionHeadingStyle}>Checkbox</h2>
      <div>
        <label style={inlineCheckStyle}>
          <Checkbox
            checked={checked1}
            onChange={(e) => setChecked1(e.target.checked)}
          />
          <span>Receive email notifications</span>
        </label>
        <label style={inlineCheckStyle}>
          <Checkbox
            checked={checked2}
            onChange={(e) => setChecked2(e.target.checked)}
          />
          <span>Subscribe to newsletter</span>
        </label>
        <label style={{ ...inlineCheckStyle, opacity: 0.5 }}>
          <Checkbox disabled defaultChecked={false} />
          <span>Disabled option</span>
        </label>
      </div>
    </section>
  );
}

function RadioSection() {
  const [selected, setSelected] = React.useState("monthly");

  return (
    <section style={sectionStyle}>
      <h2 style={sectionHeadingStyle}>Radio</h2>
      <RadioGroup orientation="vertical">
        {(["monthly", "annual", "lifetime"] as const).map((val) => (
          <label key={val} style={inlineCheckStyle}>
            <Radio
              name="billing"
              value={val}
              checked={selected === val}
              onChange={() => setSelected(val)}
            />
            <span style={{ textTransform: "capitalize" }}>{val}</span>
          </label>
        ))}
        <label style={{ ...inlineCheckStyle, opacity: 0.5 }}>
          <Radio name="billing" value="enterprise" disabled />
          <span>Enterprise (disabled)</span>
        </label>
      </RadioGroup>
    </section>
  );
}

function DialogSection() {
  return (
    <section style={sectionStyle}>
      <h2 style={sectionHeadingStyle}>Dialog</h2>
      <div style={rowStyle}>
        <Dialog>
          <DialogTrigger asChild>
            <Button variant="secondary">Open dialog (md)</Button>
          </DialogTrigger>
          <DialogContent size="md">
            <DialogHeader>
              <DialogTitle>Confirm action</DialogTitle>
              <DialogDescription>
                This dialog is a controlled overlay rendered via Radix UI Portal.
                It uses the card surface token and respects the active theme.
              </DialogDescription>
            </DialogHeader>
            <p style={{ fontSize: "0.875rem", color: "var(--foreground)", margin: "0 0 1rem" }}>
              Are you sure you want to proceed? This cannot be undone.
            </p>
            <DialogFooter>
              <DialogClose asChild>
                <Button variant="ghost">Cancel</Button>
              </DialogClose>
              <DialogClose asChild>
                <Button variant="primary">Confirm</Button>
              </DialogClose>
            </DialogFooter>
          </DialogContent>
        </Dialog>

        <Dialog>
          <DialogTrigger asChild>
            <Button variant="danger">Destructive dialog (sm)</Button>
          </DialogTrigger>
          <DialogContent size="sm">
            <DialogHeader>
              <DialogTitle>Delete item</DialogTitle>
              <DialogDescription>
                This action is permanent and cannot be reversed.
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <DialogClose asChild>
                <Button variant="ghost">Cancel</Button>
              </DialogClose>
              <DialogClose asChild>
                <Button variant="danger">Delete</Button>
              </DialogClose>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    </section>
  );
}

function DropdownMenuSection() {
  return (
    <section style={sectionStyle}>
      <h2 style={sectionHeadingStyle}>DropdownMenu</h2>
      <div style={rowStyle}>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="secondary">Open menu ▾</Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            <DropdownMenuLabel>My Account</DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem>Profile</DropdownMenuItem>
            <DropdownMenuItem>Settings</DropdownMenuItem>
            <DropdownMenuItem>Billing</DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem destructive>Delete account</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </section>
  );
}

function ThemeToggleSection() {
  return (
    <section style={sectionStyle}>
      <h2 style={sectionHeadingStyle}>ThemeToggle</h2>
      <p style={{ fontSize: "0.875rem", color: "var(--muted-foreground)", marginBottom: "0.75rem" }}>
        Toggles the <code>dark</code> class on <code>&lt;html&gt;</code>. Persists to
        {" "}<code>localStorage</code> under the key <code>ds-theme</code>.
      </p>
      <div style={rowStyle}>
        <ThemeToggle />
        <ThemeToggle block />
      </div>
    </section>
  );
}

// ─── Page root ─────────────────────────────────────────────────────────────

export default function DesignSystem() {
  return (
    <div style={pageStyle}>
      <header style={headerStyle}>
        <h1 style={h1Style}>Design System</h1>
        {/* header toggle; also demoed in ThemeToggleSection below */}
        <ThemeToggle />
      </header>

      <main>
        <ColorSection />
        <TypographySection />
        <ButtonSection />
        <BadgeSection />
        <InputSection />
        <SelectSection />
        <CheckboxSection />
        <RadioSection />
        <DialogSection />
        <DropdownMenuSection />
        <ThemeToggleSection />
      </main>
    </div>
  );
}
