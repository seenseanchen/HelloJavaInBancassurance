import axios from 'axios'
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { DEMO_USERS } from '../config/users'

type ServerRole = 'ROLE_ADMIN' | 'ROLE_UNDERWRITER' | 'ROLE_CSR' | 'ROLE_AGENT'
type AppRole = 'admin' | 'underwriter' | 'csr' | 'agent'
type AuthMode = 'server' | 'mock'

interface AuthUser {
  username: string
  displayName: string
  role: AppRole
  roles: ServerRole[]
}

interface StoredAuthState {
  authMode: AuthMode
  token: string
  user: AuthUser
}

interface BackendLoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  username: string
  displayName: string
  roles: string[]
}

interface ApiEnvelope<T> {
  code: string
  message: string
  data: T
  traceId?: string
}

interface LoginResult {
  success: boolean
  mode?: AuthMode
  message?: string
}

const STORAGE_KEY = 'banca.auth'

const authClient = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

function normalizeRoles(rawRoles: string[]): ServerRole[] {
  if (rawRoles.length === 0) {
    return ['ROLE_AGENT']
  }
  return rawRoles.map((role) => role as ServerRole)
}

function primaryRole(roles: ServerRole[]): AppRole {
  if (roles.includes('ROLE_ADMIN')) {
    return 'admin'
  }
  if (roles.includes('ROLE_UNDERWRITER')) {
    return 'underwriter'
  }
  if (roles.includes('ROLE_CSR')) {
    return 'csr'
  }
  return 'agent'
}

function extractBackendLoginPayload(payload: unknown): BackendLoginResponse | null {
  if (!payload || typeof payload !== 'object') {
    return null
  }

  const direct = payload as Partial<BackendLoginResponse>
  if (typeof direct.accessToken === 'string') {
    return {
      accessToken: direct.accessToken,
      tokenType: direct.tokenType ?? 'Bearer',
      expiresIn: Number(direct.expiresIn ?? 0),
      username: String(direct.username ?? ''),
      displayName: String(direct.displayName ?? ''),
      roles: Array.isArray(direct.roles) ? direct.roles.map(String) : [],
    }
  }

  const wrapped = payload as Partial<ApiEnvelope<BackendLoginResponse>>
  if (wrapped.data && typeof wrapped.data === 'object') {
    return extractBackendLoginPayload(wrapped.data)
  }

  return null
}

function extractLoginErrorMessage(error: unknown): string | null {
  if (!axios.isAxiosError(error)) {
    return null
  }

  const payload = error.response?.data
  if (payload && typeof payload === 'object' && 'message' in payload) {
    const message = payload.message
    if (typeof message === 'string' && message.length > 0) {
      return message
    }
  }

  return null
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(null)
  const user = ref<AuthUser | null>(null)
  const authMode = ref<AuthMode | null>(null)
  const hydrated = ref(false)

  const isAuthenticated = computed(() => Boolean(token.value && user.value && authMode.value))
  const isServerAuth = computed(() => authMode.value === 'server')

  function setSession(nextToken: string, nextUser: AuthUser, nextMode: AuthMode) {
    token.value = nextToken
    user.value = nextUser
    authMode.value = nextMode
    persist()
  }

  function persist() {
    if (typeof window === 'undefined') {
      return
    }

    if (!token.value || !user.value || !authMode.value) {
      window.localStorage.removeItem(STORAGE_KEY)
      return
    }

    const payload: StoredAuthState = {
      authMode: authMode.value,
      token: token.value,
      user: user.value,
    }
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
  }

  function hydrate() {
    if (hydrated.value || typeof window === 'undefined') {
      hydrated.value = true
      return
    }

    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      hydrated.value = true
      return
    }

    try {
      const parsed = JSON.parse(raw) as StoredAuthState
      token.value = parsed.token
      user.value = parsed.user
      authMode.value = parsed.authMode
    } catch {
      window.localStorage.removeItem(STORAGE_KEY)
    } finally {
      hydrated.value = true
    }
  }

  async function login(username: string, password: string): Promise<LoginResult> {
    const normalizedUsername = username.trim()

    try {
      const response = await authClient.post<BackendLoginResponse | ApiEnvelope<BackendLoginResponse>>(
        '/auth/login',
        {
          username: normalizedUsername,
          password,
        },
      )
      const payload = extractBackendLoginPayload(response.data)

      if (payload && payload.accessToken.length > 0) {
        const normalizedRoles = normalizeRoles(payload.roles)
        const loginUser: AuthUser = {
          username: payload.username,
          displayName: payload.displayName,
          role: primaryRole(normalizedRoles),
          roles: normalizedRoles,
        }
        setSession(payload.accessToken, loginUser, 'server')
        return { success: true, mode: 'server' }
      }
    } catch (error) {
      // 401/400 會走 fallback mock；非認證類錯誤保留訊息於最終失敗時回傳。
      const message = extractLoginErrorMessage(error)
      if (message && !['Bad credentials', 'username must not be blank', 'password must not be blank'].includes(message)) {
        return { success: false, message }
      }
    }

    const matchedUser = DEMO_USERS.find(
      (candidate) => candidate.username === normalizedUsername && candidate.password === password,
    )

    if (!matchedUser) {
      return { success: false, message: '帳號或密碼錯誤' }
    }

    const mockRoles: ServerRole[] =
      matchedUser.role === 'underwriter' ? ['ROLE_UNDERWRITER'] : ['ROLE_AGENT']
    const mockUser: AuthUser = {
      username: matchedUser.username,
      displayName: matchedUser.displayName,
      role: matchedUser.role,
      roles: mockRoles,
    }
    setSession(`mock-token-${matchedUser.username}-${Date.now()}`, mockUser, 'mock')
    return { success: true, mode: 'mock' }
  }

  function logout() {
    token.value = null
    user.value = null
    authMode.value = null
    persist()
  }

  return {
    authMode,
    isAuthenticated,
    isServerAuth,
    token,
    user,
    hydrate,
    login,
    logout,
  }
})
