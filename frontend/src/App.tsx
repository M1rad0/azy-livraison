import { type FormEvent, useCallback, useEffect, useState } from 'react'
import {
  login,
  logout,
  refreshSession,
  type AuthSession,
} from './api/auth'
import { getSystemStatus, type ServiceState, type SystemStatus } from './api/systemStatus'
import { CsvImportWorkspace } from './components/CsvImportWorkspace'
import './App.css'

type LoadingState = 'loading' | 'ready' | 'error'
type SessionState = 'restoring' | 'guest' | 'authenticated'

function Brand() {
  return (
    <div className="brand-lockup">
      <div className="brand-mark" aria-hidden="true">
        <span>A</span>
      </div>
      <div>
        <strong>AZY Livraison</strong>
        <span>Antananarivo · Madagascar</span>
      </div>
    </div>
  )
}

function LoginScreen({ onAuthenticated }: { onAuthenticated: (session: AuthSession) => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string>()

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setErrorMessage(undefined)

    try {
      onAuthenticated(await login(username, password))
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : 'La connexion a échoué.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login-page">
      <section className="login-intro" aria-labelledby="login-title">
        <Brand />
        <div>
          <p className="eyebrow">Planifier avec précision</p>
          <h1 id="login-title">Des tournées plus simples, dès le départ.</h1>
          <p>
            Préparez les colis, organisez les livreurs et visualisez chaque itinéraire depuis un espace privé.
          </p>
        </div>
        <span className="login-intro__caption">Une seule origine · Plusieurs destinations</span>
      </section>

      <section className="login-panel" aria-labelledby="form-title">
        <form className="login-form" onSubmit={(event) => void handleSubmit(event)}>
          <div>
            <p className="eyebrow">Accès sécurisé</p>
            <h2 id="form-title">Bienvenue</h2>
            <p className="login-form__intro">Connectez-vous avec le compte configuré pour cette installation.</p>
          </div>

          <label>
            Identifiant
            <input
              name="username"
              autoComplete="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              required
              autoFocus
            />
          </label>

          <label>
            Mot de passe
            <input
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
          </label>

          {errorMessage && <p className="form-error" role="alert">{errorMessage}</p>}

          <button className="button button--wide" type="submit" disabled={submitting}>
            {submitting ? 'Connexion…' : 'Se connecter'}
          </button>
        </form>
      </section>
    </main>
  )
}

function StatusCard({ label, state }: { label: string; state?: ServiceState }) {
  const stateLabel = state === 'UP' ? 'Opérationnel' : state === 'DOWN' ? 'Indisponible' : 'Vérification'

  return (
    <article className={`status-card status-card--${state?.toLowerCase() ?? 'loading'}`}>
      <span className="status-card__dot" aria-hidden="true" />
      <div>
        <p>{label}</p>
        <strong>{stateLabel}</strong>
      </div>
    </article>
  )
}

function Dashboard({ session, onLogout }: { session: AuthSession; onLogout: () => Promise<void> }) {
  const [status, setStatus] = useState<SystemStatus>()
  const [loadingState, setLoadingState] = useState<LoadingState>('loading')
  const [errorMessage, setErrorMessage] = useState<string>()
  const [loggingOut, setLoggingOut] = useState(false)

  const refreshStatus = useCallback(async (signal?: AbortSignal) => {
    setLoadingState('loading')
    setErrorMessage(undefined)

    try {
      const nextStatus = await getSystemStatus(session.accessToken, signal)
      setStatus(nextStatus)
      setLoadingState('ready')
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return
      setStatus(undefined)
      setLoadingState('error')
      setErrorMessage(error instanceof Error ? error.message : 'Connexion impossible.')
    }
  }, [session.accessToken])

  useEffect(() => {
    const controller = new AbortController()
    void refreshStatus(controller.signal)
    return () => controller.abort()
  }, [refreshStatus])

  async function handleLogout() {
    setLoggingOut(true)
    try {
      await onLogout()
    } finally {
      setLoggingOut(false)
    }
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <Brand />
        <div className="account-menu">
          <span>Connecté : <strong>{session.account.username}</strong></span>
          <button className="button button--secondary" type="button" onClick={() => void handleLogout()} disabled={loggingOut}>
            {loggingOut ? 'Déconnexion…' : 'Se déconnecter'}
          </button>
        </div>
      </header>

      <section className="hero" aria-labelledby="page-title">
        <p className="eyebrow">Espace de planification</p>
        <h1 id="page-title">Préparez vos livraisons.</h1>
        <p className="hero__intro">
          Importez vos colis et vos livreurs, contrôlez chaque ligne puis préparez la prochaine tournée.
        </p>
      </section>

      <CsvImportWorkspace accessToken={session.accessToken} />

      <section className="foundation" aria-labelledby="foundation-title">
        <div className="foundation__heading">
          <div>
            <p className="eyebrow">Jalon 02</p>
            <h2 id="foundation-title">État de la plateforme</h2>
          </div>
          <button className="button" type="button" onClick={() => void refreshStatus()} disabled={loadingState === 'loading'}>
            {loadingState === 'loading' ? 'Vérification…' : 'Actualiser'}
          </button>
        </div>

        <div className="status-grid" aria-live="polite">
          <StatusCard label="Interface web" state="UP" />
          <StatusCard label="API Spring Boot" state={status?.service} />
          <StatusCard label="Base PostgreSQL" state={status?.database} />
        </div>

        {loadingState === 'error' && (
          <p className="message message--error" role="alert">
            L'interface fonctionne, mais l'API n'est pas joignable. {errorMessage}
          </p>
        )}

        {loadingState === 'ready' && status && (
          <p className="message">
            Dernière vérification :{' '}
            <time dateTime={status.checkedAt}>
              {new Intl.DateTimeFormat('fr-MG', {
                dateStyle: 'medium',
                timeStyle: 'medium',
                timeZone: 'Indian/Antananarivo',
              }).format(new Date(status.checkedAt))}
            </time>
          </p>
        )}
      </section>

      <footer>
        <span>Espace privé</span>
        <span>Spring Boot · React · PostgreSQL</span>
      </footer>
    </main>
  )
}

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
    return <LoginScreen onAuthenticated={setAuthenticated} />
  }

  return <Dashboard session={session} onLogout={handleLogout} />
}
