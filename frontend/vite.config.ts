import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // PINNED, and strict. Vite's default behaviour is to silently bump to
    // 5174, 5175... when 5173 is busy — which broke Google sign-in twice
    // with `Error 400: origin_mismatch`, because Google only trusts the
    // exact origins registered in the Cloud Console, and the backend's CORS
    // allow-list is likewise a fixed list.
    //
    // strictPort makes that failure LOUD and immediate ("port in use") at
    // startup, instead of quiet and confusing three screens into a demo.
    // If 5173 is genuinely taken, stop the other process rather than letting
    // the app drift to an origin nothing trusts.
    port: 5173,
    strictPort: true,
  },
})
