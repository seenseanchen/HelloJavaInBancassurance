import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8080'
  const host = env.VITE_HOST || '127.0.0.1'
  const port = Number(env.VITE_PORT || '5173')

  return {
    plugins: [vue(), tailwindcss()],
    server: {
      host,
      port: Number.isNaN(port) ? 5173 : port,
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true,
        },
      },
    },
  }
})
