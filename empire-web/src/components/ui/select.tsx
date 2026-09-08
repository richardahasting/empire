// Attribution: Brian Casel / Builder Methods
// Source: https://github.com/buildermethods/bm-skills (free to use, fork, and adapt)
// Adapted for this design system: inline Tailwind classes use standard shadcn tokens.
// Token remaps: form-control/form-control-select CSS classes replaced with Tailwind utilities:
//   bg-page → bg-background, border-hairline → border-input,
//   text-ink-body → text-foreground, ring-accent → ring-ring,
//   text-ink-muted (chevron) → text-muted-foreground.
import * as React from "react";
import { ChevronDown } from "lucide-react";
import { cn } from "./utils";

export interface SelectProps
  extends React.SelectHTMLAttributes<HTMLSelectElement> {}

const Select = React.forwardRef<HTMLSelectElement, SelectProps>(
  ({ className, children, ...props }, ref) => {
    return (
      <div className="relative">
        <select
          ref={ref}
          className={cn(
            "flex h-9 w-full appearance-none rounded-[var(--radius)] border border-input bg-background px-3 py-1 pr-8 text-sm text-foreground shadow-sm transition-colors",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background",
            "disabled:cursor-not-allowed disabled:opacity-50",
            className,
          )}
          {...props}
        >
          {children}
        </select>
        <ChevronDown
          aria-hidden="true"
          className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
        />
      </div>
    );
  },
);
Select.displayName = "Select";

export { Select };
