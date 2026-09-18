import { type FormEvent, useState } from 'react'
import { ArrowRight, Calculator, LogIn } from 'lucide-react'
import { login, type AuthSession } from '../api/auth'
import { Brand } from './Brand'
import { PublicPricingCalculator } from './PublicPricingCalculator'

interface AuthScreenProps {
  onAuthenticated: (session: AuthSession) => void
}

export function AuthScreen({ onAuthenticated }: AuthScreenProps) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string>()
  const [pricingOpen, setPricingOpen] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setErrorMessage(undefined)

    setSubmitting(true)
    try {
      const session = await login(username, password)
      onAuthenticated(session)
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : 'La demande a échoué.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="login-page">
      <section className="login-intro" aria-labelledby="login-title">
        <Brand />
        <div>
          <p className="eyebrow">Livrer avec précision</p>
          <h1 id="login-title">Vos colis, organisés du retrait à la destination.</h1>
          <p>
            Enregistrez vos demandes, suivez leur préparation et retrouvez chaque livraison depuis un espace privé.
          </p>
        </div>
        <span className="login-intro__caption">Pensé à Antananarivo · Pour les réalités du terrain</span>
      </section>

      <section className="login-panel" aria-labelledby="form-title">
        <form className="login-form" onSubmit={(event) => void handleSubmit(event)}>
          <div className="login-form__heading">
            <p className="eyebrow">Retrouver votre espace</p>
            <h2 id="form-title">Bienvenue</h2>
            <p className="login-form__intro">Connectez-vous pour accéder à vos livraisons.</p>
          </div>

          <label>
            Identifiant
            <input
              name="username"
              autoComplete="username"
              maxLength={100}
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
              maxLength={200}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
          </label>

          {errorMessage && <p className="form-error" role="alert">{errorMessage}</p>}

          <button className="button button--wide login-form__submit" type="submit" disabled={submitting}>
            <LogIn aria-hidden="true" />
            {submitting ? 'Veuillez patienter…' : 'Se connecter'}
          </button>

          <button className="login-pricing-cta" type="button" onClick={() => setPricingOpen(true)}>
            <span aria-hidden="true"><Calculator /></span>
            <span>
              <strong>Calculer un tarif</strong>
              <small>Obtenez immédiatement la zone et le prix d'une destination.</small>
            </span>
            <ArrowRight aria-hidden="true" />
          </button>
        </form>
      </section>
      {pricingOpen && <PublicPricingCalculator onClose={() => setPricingOpen(false)} />}
    </main>
  )
}
