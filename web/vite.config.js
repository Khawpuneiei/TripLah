import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// /v1 is proxied to the Ktor API (./gradlew :api:run), so the page never talks to providers directly.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/v1': 'http://127.0.0.1:8080' } },
})
