// Attribution: Brian Casel / Builder Methods
// Source: https://github.com/buildermethods/bm-skills (free to use, fork, and adapt)
// Adapted for this design system: inline Tailwind classes use standard shadcn tokens.
// Token remaps:
//   border-hairline → border-input
//   bg-page → bg-background
//   text-accent → text-primary
//   accent-[var(--color-accent)] → accent-[var(--primary)]
//   ring-accent → ring-ring
//   ring-offset-page → ring-offset-background
import * as React from "react";
import { cn } from "./utils";

export interface CheckboxProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, "type"> {}

const Checkbox = React.forwardRef<HTMLInputElement, CheckboxProps>(
  ({ className, ...props }, ref) => {
    return (
      <input
        ref={ref}
        type="checkbox"
        className={cn(
          "h-4 w-4 shrink-0 rounded-[calc(var(--radius)/2)] border border-input bg-background",
          "accent-[var(--primary)]",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background",
          "disabled:cursor-not-allowed disabled:opacity-50",
          className,
        )}
        {...props}
      />
    );
  },
);
Checkbox.displayName = "Checkbox";

export { Checkbox };
