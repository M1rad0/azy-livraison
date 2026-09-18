import { type FormEvent, useState } from 'react'
import { ArrowRight, Calculator, LogIn, UserPlus } from 'lucide-react'
import { login, register, type AuthSession } from '../api/auth'
import { Brand } from './Brand'
import { PublicPricingCalculator } from './PublicPricingCalculator'

type AuthMode = 'login' | 'register'

interface AuthScreenProps {
  onAuthenticated: (session: AuthSession) => void
}

export function AuthScreen({ onAuthenticated }: AuthScreenProps) {
  const [mode, setMode] = useState<AuthMode>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirmation, setPasswordConfirmation] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [phone, setPhone] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string>()
  const [pricingOpen, setPricingOpen] = useState(false)

  function changeMode(nextMode: AuthMode) {
    setMode(nextMode)
    setPassword('')
    setPasswordConfirmation('')
    setErrorMessage(undefined)
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setErrorMessage(undefined)

    if (mode === 'register' && password !== passwordConfirmation) {
      setErrorMessage('Les mots de passe ne correspondent pas.')
      return
    }

    setSubmitting(true)
    try {
      const session = mode === 'login'
        ? await login(username, password)
        : await register({ username, password, displayName, phone })
      onAuthenticated(session)
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : 'La demande a échoué.')
    } finally {
      setSubmitting(false)
    }
  }

  const registering = mode === 'register'

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
        <form className={`login-form${registering ? ' login-form--register' : ''}`} onSubmit={(event) => void handleSubmit(event)}>
          <div className="login-form__heading">
            <p className="eyebrow">{registering ? 'Créer votre espace' : 'Retrouver votre espace'}</p>
            <h2 id="form-title">{registering ? 'Inscription client' : 'Bienvenue'}</h2>
            <p className="login-form__intro">
              {registering
                ? 'Quelques informations suffisent pour enregistrer votre première demande.'
                : 'Connectez-vous pour accéder à vos livraisons.'}
            </p>
            <button className="login-pricing-cta" type="button" onClick={() => setPricingOpen(true)}>
                <span aria-hidden="true"><Calculator /></span>
                <span><strong>Calculer un tarif</strong><small>Choisissez une destination et obtenez immédiatement sa zone et son prix.</small></span>
                <ArrowRight aria-hidden="true" />
            </button>
          </div>

          {registering && (
            <>
              <label>
                Nom affiché
                <input
                  name="displayName"
                  autoComplete="name"
                  maxLength={150}
                  value={displayName}
                  onChange={(event) => setDisplayName(event.target.value)}
                  required
                  autoFocus
                />
              </label>

              <label>
                Téléphone
                <input
                  name="phone"
                  type="tel"
                  autoComplete="tel"
                  maxLength={50}
                  placeholder="+261 34 00 000 00"
                  value={phone}
                  onChange={(event) => setPhone(event.target.value)}
                  required
                />
              </label>
            </>
          )}

          <label>
            Identifiant
            <input
              name="username"
              autoComplete="username"
              maxLength={100}
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              required
              autoFocus={!registering}
            />
          </label>

          <label>
            Mot de passe
            <input
              name="password"
              type="password"
              autoComplete={registering ? 'new-password' : 'current-password'}
              minLength={registering ? 8 : undefined}
              maxLength={200}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
          </label>

          {registering && (
            <label className="login-form__confirmation">
              Confirmer le mot de passe
              <input
                name="passwordConfirmation"
                type="password"
                autoComplete="new-password"
                minLength={8}
                maxLength={200}
                value={passwordConfirmation}
                onChange={(event) => setPasswordConfirmation(event.target.value)}
                required
              />
            </label>
          )}

          {errorMessage && <p className="form-error" role="alert">{errorMessage}</p>}

          <button className="button button--wide login-form__submit" type="submit" disabled={submitting}>
            {registering ? <UserPlus aria-hidden="true" /> : <LogIn aria-hidden="true" />}
            {submitting ? 'Veuillez patienter…' : registering ? 'Créer mon compte' : 'Se connecter'}
          </button>

          <div className="auth-switch">
            <span>{registering ? 'Vous avez déjà un compte ?' : 'Première livraison avec AZY ?'}</span>
            <button type="button" onClick={() => changeMode(registering ? 'login' : 'register')}>
              {registering ? 'Se connecter' : 'Créer un compte'}<ArrowRight aria-hidden="true" />
            </button>
          </div>
        </form>
      </section>
      {pricingOpen && <PublicPricingCalculator onClose={() => setPricingOpen(false)} />}
    </main>
  )
}
