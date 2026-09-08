// Attribution: Brian Casel / Builder Methods
// Source: https://github.com/buildermethods/bm-skills (free to use, fork, and adapt)
// Adapted for this design system: CVA variants map to standard shadcn token classes.
// Token remaps: btn-danger variant → destructive styling using --destructive token.
import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "./utils";

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-[var(--radius)] text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50",
  {
    variants: {
      variant: {
        // primary: solid primary background
        primary:
          "bg-primary text-primary-foreground shadow hover:bg-primary/90",
        // secondary: subtle, secondary background
        secondary:
          "bg-secondary text-secondary-foreground shadow-sm hover:bg-secondary/80",
        // ghost: no background, hover fills
        ghost: "hover:bg-accent hover:text-accent-foreground",
        // soft: muted background
        soft: "bg-muted text-muted-foreground hover:bg-muted/80",
        // danger: destructive action — remapped from Casel's btn-danger
        // Remap: btn-danger → destructive token
        danger:
          "bg-destructive text-destructive-foreground shadow-sm hover:bg-destructive/90",
        // link: looks like a link
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        sm: "h-8 rounded-[var(--radius)] px-3 text-xs",
        md: "h-9 px-4 py-2",
        lg: "h-10 rounded-[var(--radius)] px-8",
        icon: "h-9 w-9",
      },
    },
    defaultVariants: {
      variant: "primary",
      size: "md",
    },
  },
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "button";
    return (
      <Comp
        ref={ref}
        className={cn(buttonVariants({ variant, size, className }))}
        {...props}
      />
    );
  },
);
Button.displayName = "Button";

export { Button, buttonVariants };
