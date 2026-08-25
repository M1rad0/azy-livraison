import { useCallback, useEffect, useState } from 'react'
import { logout, refreshSession, type AuthSession } from './api/auth'
import { AuthenticatedHeader } from './components/AuthenticatedHeader'
import { AuthScreen } from './components/AuthScreen'
import { Brand } from './components/Brand'
import { AdminDashboard, ClientDashboard, CourierDashboard } from './components/RoleDashboards'
import './App.css'

type SessionState = 'restoring' | 'guest' | 'authenticated'

export function App() {
  const [sessionState, setSessionState] = useState<SessionState>('restoring')
  const [session, setSession] = useState<AuthSession>()

  const setAuthenticated = useCallback((nextSession: AuthSession) => {
    setSession(nextSession)
    setSessionState('authenticated')
  }, [])

  useEffect(() => {
    let active = true
    void refreshSession()
      .then((nextSession) => {
        if (active) setAuthenticated(nextSession)
      })
      .catch(() => {
        if (active) setSessionState('guest')
      })
    return () => {
      active = false
    }
  }, [setAuthenticated])

  useEffect(() => {
    if (!session) return
    const refreshDelay = Math.max((session.expiresIn - 30) * 1000, 1000)
    const timer = window.setTimeout(() => {
      void refreshSession()
        .then(setAuthenticated)
        .catch(() => {
          setSession(undefined)
          setSessionState('guest')
        })
    }, refreshDelay)
    return () => window.clearTimeout(timer)
  }, [session, setAuthenticated])

  async function handleLogout() {
    try {
      await logout()
    } finally {
      setSession(undefined)
      setSessionState('guest')
    }
  }

  if (sessionState === 'restoring') {
    return (
      <main className="session-loader" aria-live="polite">
        <Brand />
        <p>Restauration de la session…</p>
      </main>
    )
  }

  if (sessionState === 'guest' || !session) {
    return <AuthScreen onAuthenticated={setAuthenticated} />
  }

  return (
    <main className="app-shell app-shell--portal">
      <AuthenticatedHeader account={session.account} onLogout={handleLogout} />
      {session.account.role === 'ADMIN' && <AdminDashboard session={session} />}
      {session.account.role === 'CLIENT' && <ClientDashboard session={session} />}
      {session.account.role === 'COURIER' && <CourierDashboard session={session} />}
      <footer>
        <span>Espace privé</span>
        <span>AZY Livraison · Antananarivo</span>
      </footer>
    </main>
  )
}
