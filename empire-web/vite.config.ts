import path from "path";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Served by empire-server under /empire (context path); the API is same-origin.
export default defineConfig({
  base: "/empire/",
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
  server: {
    port: 5173,
    proxy: { "/empire/api": { target: "http://127.0.0.1:8020", changeOrigin: true } },
  },
  build: { outDir: "dist", emptyOutDir: true },
});
