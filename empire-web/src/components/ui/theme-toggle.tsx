// Attribution: Brian Casel / Builder Methods
// Source: https://github.com/buildermethods/bm-skills (free to use, fork, and adapt)
// Adapted for this design system: Casel-specific token classes replaced with standard
// shadcn tokens.
// Token remaps:
//   border-hairline → border-border
//   bg-surface → bg-secondary
//   ring-accent → ring-ring
//   bg-page (active button) → bg-background
//   text-ink-display (active) → text-foreground
//   text-ink-muted → text-muted-foreground
//   hover:text-ink-display → hover:text-foreground
import * as React from "react";
import { Monitor, Moon, Sun } from "lucide-react";
import { useTheme, type Theme } from "./theme";
import { cn } from "./utils";

interface ThemeOption {
  value: Theme;
  label: string;
  Icon: React.ComponentType<{ className?: string }>;
}

const OPTIONS: ThemeOption[] = [
  { value: "light", label: "Light mode", Icon: Sun },
  { value: "dark", label: "Dark mode", Icon: Moon },
  { value: "system", label: "System theme", Icon: Monitor },
];

export interface ThemeToggleProps {
  block?: boolean;
  className?: string;
}

export function ThemeToggle({ block = false, className }: ThemeToggleProps) {
  const [theme, setTheme] = useTheme();

  return (
    <div
      role="radiogroup"
      aria-label="Theme"
      className={cn(
        "inline-flex items-center gap-0.5 rounded-[var(--radius)] border border-border bg-secondary p-0.5",
        block && "w-full",
        className,
      )}
    >
      {OPTIONS.map(({ value, label, Icon }) => {
        const isActive = theme === value;
        return (
          <button
            key={value}
            type="button"
            role="radio"
            aria-checked={isActive}
            aria-label={label}
            title={label}
            onClick={() => setTheme(value)}
            className={cn(
              "inline-flex h-7 cursor-pointer items-center justify-center rounded transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              block ? "flex-1" : "w-7",
              isActive
                ? "bg-background text-foreground shadow-sm"
                : "text-muted-foreground hover:text-foreground",
            )}
          >
            <Icon className="h-4 w-4" />
          </button>
        );
      })}
    </div>
  );
}
