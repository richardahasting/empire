// Attribution: Brian Casel / Builder Methods
// Source: https://github.com/buildermethods/bm-skills (free to use, fork, and adapt)
// Adapted for this design system: inline Tailwind classes use standard shadcn tokens.
// Token remaps: form-control CSS class replaced with explicit Tailwind utilities:
//   bg-page → bg-background, border-hairline → border-input,
//   text-ink-body → text-foreground, ring-accent → ring-ring,
//   placeholder text uses text-muted-foreground.
import * as React from "react";
import { cn } from "./utils";

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {}

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type = "text", ...props }, ref) => {
    return (
      <input
        ref={ref}
        type={type}
        className={cn(
          "flex h-9 w-full rounded-[var(--radius)] border border-input bg-background px-3 py-1 text-sm text-foreground shadow-sm transition-colors",
          "file:border-0 file:bg-transparent file:text-sm file:font-medium",
          "placeholder:text-muted-foreground",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background",
          "disabled:cursor-not-allowed disabled:opacity-50",
          className,
        )}
        {...props}
      />
    );
  },
);
Input.displayName = "Input";

export { Input };
