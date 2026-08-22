const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export interface Account {
  id: string
  username: string
  role: AccountRole
  displayName: string
  phone: string | null
}

export type AccountRole = 'CLIENT' | 'COURIER' | 'ADMIN'

export interface RegisterInput {
  username: string
  password: string
  displayName: string
  phone: string
}

export interface AuthSession {
  tokenType: 'Bearer'
  accessToken: string
  expiresIn: number
  account: Account
}

interface AuthErrorPayload {
  code?: string
  message?: string
}

export class AuthError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly status: number,
  ) {
    super(message)
  }
}

async function authRequest(path: string, init?: RequestInit): Promise<AuthSession> {
  const response = await fetch(`${API_BASE_URL}/auth/${path}`, {
    ...init,
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...init?.headers,
    },
  })

  if (!response.ok) {
    const payload = (await response.json().catch(() => ({}))) as AuthErrorPayload
    throw new AuthError(
      payload.message ?? 'La connexion a échoué.',
      payload.code ?? 'AUTHENTICATION_FAILED',
      response.status,
    )
  }

  return (await response.json()) as AuthSession
}

export function login(username: string, password: string): Promise<AuthSession> {
  return authRequest('login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
}

export function register(input: RegisterInput): Promise<AuthSession> {
  return authRequest('register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

let refreshInFlight: Promise<AuthSession> | undefined

export function refreshSession(): Promise<AuthSession> {
  if (!refreshInFlight) {
    refreshInFlight = authRequest('refresh', { method: 'POST' }).finally(() => {
      refreshInFlight = undefined
    })
  }
  return refreshInFlight
}

export async function logout(): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/auth/logout`, {
    method: 'POST',
    credentials: 'include',
  })
  if (!response.ok) {
    throw new AuthError('La déconnexion a échoué.', 'LOGOUT_FAILED', response.status)
  }
}
