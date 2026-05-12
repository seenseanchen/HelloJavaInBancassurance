import axios, { AxiosHeaders } from 'axios'
import router from '../router'
import { pinia } from '../stores'
import { useAuthStore } from '../stores/auth'

export const http = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

http.interceptors.request.use((config) => {
  const authStore = useAuthStore(pinia)
  authStore.hydrate()

  const headers =
    config.headers instanceof AxiosHeaders
      ? config.headers
      : new AxiosHeaders(config.headers)

  if (authStore.isServerAuth && authStore.token) {
    headers.set('Authorization', `Bearer ${authStore.token}`)
  }

  if (authStore.user?.username) {
    headers.set('X-Actor', authStore.user.username)
  }

  config.headers = headers

  return config
})

let redirectingAfterUnauthorized = false

http.interceptors.response.use(
  (response) => response,
  async (error) => {
    const authStore = useAuthStore(pinia)
    authStore.hydrate()

    if (error?.response?.status === 401 && authStore.isServerAuth) {
      authStore.logout()

      if (!redirectingAfterUnauthorized) {
        redirectingAfterUnauthorized = true
        const currentPath = router.currentRoute.value.fullPath
        await router.replace({
          path: '/login',
          query: currentPath === '/login' ? {} : { redirect: currentPath },
        })
        redirectingAfterUnauthorized = false
      }
    }

    return Promise.reject(error)
  },
)
