// Attribution: Brian Casel / Builder Methods
// Source: https://github.com/buildermethods/bm-skills (free to use, fork, and adapt)
// Adapted for this design system: tone variants remapped to standard shadcn tokens.
// Token remaps:
//   tone "neutral" → border + background + foreground tokens
//   tone "accent"  → primary tokens
//   tone "signal"  → secondary tokens (closest available; no warning token in shadcn neutral set)
//   tone "muted"   → muted tokens
//   tone "solid"   → primary (solid fill)
import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "./utils";

const badgeVariants = cva(
  "inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 focus:ring-offset-background",
  {
    variants: {
      tone: {
        // neutral: subtle bordered badge
        neutral:
          "border-border bg-background text-foreground hover:bg-secondary",
        // accent → primary (Remap: badge-accent → primary tokens)
        accent:
          "border-transparent bg-primary/10 text-primary",
        // signal → secondary (Remap: badge-signal → secondary; no warning in shadcn neutral)
        signal:
          "border-transparent bg-secondary text-secondary-foreground",
        // muted → muted tokens (Remap: badge-muted → muted tokens)
        muted:
          "border-transparent bg-muted text-muted-foreground",
        // solid → filled primary (Remap: badge-solid → primary solid)
        solid:
          "border-transparent bg-primary text-primary-foreground",
      },
    },
    defaultVariants: {
      tone: "neutral",
    },
  },
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badgeVariants> {}

const Badge = React.forwardRef<HTMLSpanElement, BadgeProps>(
  ({ className, tone, ...props }, ref) => {
    return (
      <span
        ref={ref}
        className={cn(badgeVariants({ tone, className }))}
        {...props}
      />
    );
  },
);
Badge.displayName = "Badge";

export { Badge, badgeVariants };
