import { type FormEvent, useCallback, useEffect, useState } from 'react'
import {
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  Clock3,
  Download,
  LogIn,
  LogOut,
  PackageCheck,
  Route,
  Save,
  Users,
} from 'lucide-react'
import {
  login,
  logout,
  refreshSession,
  type AuthSession,
} from './api/auth'
import { PlanningWorkspace } from './components/PlanningWorkspace'
import './App.css'

type SessionState = 'restoring' | 'guest' | 'authenticated'

function Brand() {
  return (
    <div className="brand-lockup">
      <div className="brand-mark" aria-hidden="true">
        <PackageCheck />
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
            <p className="login-form__intro">Connectez-vous pour retrouver votre espace de préparation.</p>
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
            <LogIn aria-hidden="true" />{submitting ? 'Connexion…' : 'Se connecter'}
          </button>
        </form>
      </section>
    </main>
  )
}

function Dashboard({ session, onLogout }: { session: AuthSession; onLogout: () => Promise<void> }) {
  const [loggingOut, setLoggingOut] = useState(false)
  const [patternSelected, setPatternSelected] = useState(false)
  const accountInitials = session.account.username.trim().slice(0, 2).toUpperCase() || 'AZ'

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
          <div className="account-profile">
            <span className="account-avatar" aria-hidden="true">{accountInitials}</span>
            <span className="account-profile__details">
              <small><i aria-hidden="true" />Session active</small>
              <strong>{session.account.username}</strong>
            </span>
          </div>
          <button className="button button--secondary account-logout" type="button" onClick={() => void handleLogout()} disabled={loggingOut}>
            <LogOut aria-hidden="true" />{loggingOut ? 'Déconnexion…' : 'Se déconnecter'}
          </button>
        </div>
      </header>

      {patternSelected && (
        <nav className="workspace-context-bar" aria-label="Mode de planification actif">
          <div className="mode-context">
            <span className="mode-context__icon" aria-hidden="true"><Route /></span>
            <div>
              <small>Mode actif</small>
              <strong>Un retrait · plusieurs livraisons</strong>
            </div>
            <button type="button" onClick={() => setPatternSelected(false)}>
              <ArrowLeft aria-hidden="true" />Changer de mode
            </button>
          </div>
        </nav>
      )}

      {patternSelected ? (
        <PlanningWorkspace accessToken={session.accessToken} />
      ) : (
        <>
          <section className="hero pattern-hero" aria-labelledby="page-title">
            <p className="eyebrow">Nouvelle planification</p>
            <h1 id="page-title">Quel trajet préparez-vous ?</h1>
            <p className="hero__intro">
              Choisissez votre façon d’organiser les livraisons. L’espace de préparation s’adaptera à ce choix.
            </p>
          </section>

          <section className="pattern-picker" aria-labelledby="pattern-title">
            <div className="section-heading">
              <div>
                <p className="eyebrow">Modes disponibles</p>
                <h2 id="pattern-title">Commencer une préparation</h2>
              </div>
              <p>Choisissez le mode qui correspond à la manière dont vos colis seront récupérés et livrés.</p>
            </div>

            <div className="pattern-grid">
              <article className="pattern-card pattern-card--active">
                <div className="pattern-card__visual" aria-hidden="true">
                  <span className="pattern-node pattern-node--pickup">A</span>
                  <i /><i /><i />
                  <span className="pattern-node">1</span>
                  <span className="pattern-node">2</span>
                  <span className="pattern-node">3</span>
                </div>
                <div className="pattern-card__content">
                  <span className="section-badge"><CheckCircle2 aria-hidden="true" />Disponible</span>
                  <h3>Un retrait, plusieurs livraisons</h3>
                  <p>Un point de récupération commun, plusieurs colis, plusieurs livreurs et des destinations réparties.</p>
                  <ul><li><Download aria-hidden="true" />Import de listes</li><li><Save aria-hidden="true" />Sauvegarde partageable</li><li><Users aria-hidden="true" />Capacités véhicules</li></ul>
                  <button className="button button--wide" type="button" onClick={() => setPatternSelected(true)}><Route aria-hidden="true" />Choisir ce mode <ArrowRight aria-hidden="true" /></button>
                </div>
              </article>

              <article className="pattern-card pattern-card--soon" aria-disabled="true">
                <div className="pattern-card__visual pattern-card__visual--multi" aria-hidden="true">
                  <span className="pattern-node pattern-node--pickup">A</span><i /><span className="pattern-node">1</span>
                  <span className="pattern-node pattern-node--pickup">B</span><i /><span className="pattern-node">2</span>
                </div>
                <div className="pattern-card__content">
                  <span className="section-badge section-badge--muted"><Clock3 aria-hidden="true" />Prochainement</span>
                  <h3>Plusieurs groupes de livraison</h3>
                  <p>Plusieurs groupes indépendants, chacun avec son point de récupération et ses destinations.</p>
                  <button className="button button--secondary button--wide" type="button" disabled><Clock3 aria-hidden="true" />Bientôt disponible</button>
                </div>
              </article>
            </div>
          </section>
        </>
      )}

      <footer>
        <span>Espace privé</span>
        <span>Vos préparations restent sous votre contrôle</span>
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
