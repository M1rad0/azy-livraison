import { useCallback, useEffect, useState } from 'react'
import { getSystemStatus, type ServiceState, type SystemStatus } from './api/systemStatus'
import './App.css'

type LoadingState = 'loading' | 'ready' | 'error'

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

export function App() {
  const [status, setStatus] = useState<SystemStatus>()
  const [loadingState, setLoadingState] = useState<LoadingState>('loading')
  const [errorMessage, setErrorMessage] = useState<string>()

  const refreshStatus = useCallback(async (signal?: AbortSignal) => {
    setLoadingState('loading')
    setErrorMessage(undefined)

    try {
      const nextStatus = await getSystemStatus(signal)
      setStatus(nextStatus)
      setLoadingState('ready')
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return

      setStatus(undefined)
      setLoadingState('error')
      setErrorMessage(error instanceof Error ? error.message : 'Connexion impossible.')
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    void refreshStatus(controller.signal)
    return () => controller.abort()
  }, [refreshStatus])

  return (
    <main className="app-shell">
      <section className="hero" aria-labelledby="page-title">
        <div className="brand-mark" aria-hidden="true">
          <span>A</span>
        </div>
        <p className="eyebrow">Antananarivo · Madagascar</p>
        <h1 id="page-title">AZY Livraison</h1>
        <p className="hero__intro">
          Le socle technique est prêt à accueillir les plans, les livreurs et les futures tournées optimisées.
        </p>
      </section>

      <section className="foundation" aria-labelledby="foundation-title">
        <div className="foundation__heading">
          <div>
            <p className="eyebrow">Jalon 01</p>
            <h2 id="foundation-title">État de la plateforme</h2>
          </div>
          <button
            type="button"
            onClick={() => void refreshStatus()}
            disabled={loadingState === 'loading'}
          >
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
        <span>Fondation locale</span>
        <span>Spring Boot · React · PostgreSQL</span>
      </footer>
    </main>
  )
}

