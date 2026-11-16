import { type FormEvent, useState } from 'react'
import { ArrowRight, Calculator, LogIn, UserPlus } from 'lucide-react'
import { login, register, type AuthSession } from '../api/auth'
import { Brand } from './Brand'
import { PublicPricingCalculator } from './PublicPricingCalculator'
import { PhoneInput } from './PhoneInput'
import { isValidPhoneNumber, normalizePhoneNumber, PHONE_NUMBER_ERROR } from '../phone'

interface AuthScreenProps {
  onAuthenticated: (session: AuthSession) => void
  onRegistered?: (session: AuthSession) => void
}

export function AuthScreen({ onAuthenticated, onRegistered }: AuthScreenProps) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [phone, setPhone] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string>()
  const [pricingOpen, setPricingOpen] = useState(false)
  const registering = mode === 'register'

  function switchMode(nextMode: 'login' | 'register') {
    setMode(nextMode)
    setPassword('')
    setConfirmation('')
    setErrorMessage(undefined)
    setPricingOpen(false)
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting) return
    setErrorMessage(undefined)

    // Some mobile browsers and password managers fill the native inputs
    // without notifying React. Read the submitted form itself so the visible
    // credentials are always the credentials sent to the API.
    const formData = new FormData(event.currentTarget)
    const submittedUsername = String(formData.get('username') ?? '').trim()
    const submittedPassword = String(formData.get('password') ?? '')
    if (registering && submittedPassword !== String(formData.get('confirmation') ?? '')) {
      setErrorMessage('Les mots de passe ne correspondent pas.')
      return
    }
    const submittedPhone = normalizePhoneNumber(String(formData.get('phone') ?? ''))
    if (registering && !isValidPhoneNumber(submittedPhone)) {
      setErrorMessage(PHONE_NUMBER_ERROR)
      return
    }

    setSubmitting(true)
    try {
      const session = registering
        ? await register({ username: submittedUsername, password: submittedPassword,
          displayName: String(formData.get('displayName') ?? '').trim(), phone: submittedPhone })
        : await login(submittedUsername, submittedPassword)
      if (registering && onRegistered) onRegistered(session)
      else onAuthenticated(session)
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
        <form key={mode} className={`login-form${registering ? ' login-form--register' : ''}`} onSubmit={(event) => void handleSubmit(event)}>
          <div className="login-form__heading">
            <p className="eyebrow">{registering ? 'Votre espace client' : 'Retrouver votre espace'}</p>
            <h2 id="form-title">{registering ? 'Créer votre compte' : 'Bienvenue'}</h2>
            <p className="login-form__intro">{registering ? 'Retrouvez vos demandes et vos rapports au même endroit.' : 'Connectez-vous pour accéder à vos livraisons.'}</p>
          </div>

          {registering && <>
            <label>Nom ou enseigne<input name="displayName" autoComplete="organization" maxLength={150} value={displayName} onChange={(event) => setDisplayName(event.target.value)} required /></label>
            <label>Téléphone<PhoneInput name="phone" value={phone} onChange={(event) => setPhone(event.target.value)} required /></label>
          </>}

          <label>
            Identifiant
            <input
              name="username"
              autoComplete="username"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
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
              autoComplete={registering ? 'new-password' : 'current-password'}
              minLength={registering ? 8 : undefined}
              maxLength={200}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
          </label>

          {registering && <label className="login-form__confirmation">Confirmer le mot de passe<input name="confirmation" type="password" autoComplete="new-password" minLength={8} maxLength={200} value={confirmation} onChange={(event) => setConfirmation(event.target.value)} required /></label>}

          {errorMessage && <p className="form-error" role="alert">{errorMessage}</p>}

          <button className="button button--wide login-form__submit" type="submit" disabled={submitting}>
            {registering ? <UserPlus aria-hidden="true" /> : <LogIn aria-hidden="true" />}
            {submitting ? 'Veuillez patienter…' : registering ? 'Créer mon compte' : 'Se connecter'}
          </button>

          {!registering && <button className="login-pricing-cta" type="button" onClick={() => setPricingOpen(true)}>
            <span aria-hidden="true"><Calculator /></span>
            <span>
              <strong>Calculer un tarif</strong>
              <small>Obtenez immédiatement la zone et le prix d'une destination.</small>
            </span>
            <ArrowRight aria-hidden="true" />
          </button>}

          <p className="auth-switch">
            {registering ? 'Vous avez déjà un compte ?' : 'Pas encore de compte ?'}
            <button type="button" disabled={submitting} onClick={() => switchMode(registering ? 'login' : 'register')}>
              {registering ? 'Se connecter' : 'Créer un compte'}
            </button>
          </p>
        </form>
      </section>
      {!registering && pricingOpen && <PublicPricingCalculator onClose={() => setPricingOpen(false)} />}
    </main>
  )
}
