import { useCallback, useEffect, useState } from 'react'
import { logout, refreshSession, type AuthSession } from './api/auth'
import { AuthScreen } from './components/AuthScreen'
import { Brand } from './components/Brand'
import { PortalShell } from './components/PortalShell'
import { AdminDashboard, ClientDashboard, CourierDashboard } from './components/RoleDashboards'
import './App.css'

type SessionState = 'restoring' | 'guest' | 'authenticated'

export function App() {
  const [sessionState, setSessionState] = useState<SessionState>('restoring')
  const [session, setSession] = useState<AuthSession>()
  const [welcomeAccountId, setWelcomeAccountId] = useState<string>()

  const setAuthenticated = useCallback((nextSession: AuthSession) => {
    setSession(nextSession)
    setSessionState('authenticated')
  }, [])

  function handleRegistered(nextSession: AuthSession) {
    if (nextSession.account.role === 'CLIENT') {
      // Set the first destination before mounting the portal, including when
      // another account left a different section in this browser's URL.
      window.history.replaceState(null, '', '#/client/profile')
      setWelcomeAccountId(nextSession.account.id)
    }
    setAuthenticated(nextSession)
  }

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
          setWelcomeAccountId(undefined)
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
      setWelcomeAccountId(undefined)
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
    return <AuthScreen onAuthenticated={setAuthenticated} onRegistered={handleRegistered} />
  }

  return (
    <main className="app-shell app-shell--portal">
      <PortalShell account={session.account} onLogout={handleLogout}>
        {session.account.role === 'ADMIN' && <AdminDashboard session={session} />}
        {session.account.role === 'CLIENT' && <ClientDashboard session={session} welcome={welcomeAccountId === session.account.id} onWelcomeDismiss={() => setWelcomeAccountId(undefined)} />}
        {session.account.role === 'COURIER' && <CourierDashboard session={session} />}
      </PortalShell>
    </main>
  )
}
