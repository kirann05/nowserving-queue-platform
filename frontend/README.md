# NowServing — frontend

React + TypeScript + Vite client: the owner console (live line, Next,
open/close, venue and booking config, history) and the customer surfaces
(QR join, live ticket with Leave Now, reservations).

```bash
npm install
npm run dev               # :5173 — start the backend on :8080 first
npm run build             # typecheck + production build
npm run lint
```

Copy `.env.example` to `.env`. Both values are compiled into the bundle and are
public by design.

See the [root README](../README.md) for the full picture.
