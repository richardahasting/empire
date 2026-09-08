# empire-web

React 19 + Vite + Tailwind v4 frontend for Empire. Served by `empire-server` under `/empire/`.

```bash
npm run dev      # http://localhost:5173/empire/ — proxies /empire/api to the server on 8020
npm run build    # dist/ is bundled into the server jar by `mvn package`
```

UI primitives live in `src/components/ui/` (design system; reference at `/empire/admin/design-system`).
Map colours live in one place: `src/map/palette.ts`.
