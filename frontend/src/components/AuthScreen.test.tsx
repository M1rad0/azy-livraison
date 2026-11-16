import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { login, register, type AuthSession } from '../api/auth'
import { AuthScreen } from './AuthScreen'
import { PHONE_NUMBER_ERROR } from '../phone'

vi.mock('../api/auth', () => ({ login: vi.fn(), register: vi.fn() }))
vi.mock('./PublicPricingCalculator', () => ({ PublicPricingCalculator: ({ onClose }: { onClose: () => void }) => <div role="dialog" aria-label="Calculer un tarif"><button onClick={onClose}>Fermer</button></div> }))

const session: AuthSession = { tokenType: 'Bearer', accessToken: 'token', expiresIn: 900, account: { id: 'client', username: 'soa', role: 'CLIENT', displayName: 'Boutique Soa', phone: '0340011122' } }

afterEach(() => { cleanup(); vi.clearAllMocks() })

function openRegistration() {
  fireEvent.click(screen.getByRole('button', { name: 'Créer un compte' }))
}

function fillRegistration() {
  fireEvent.change(screen.getByLabelText('Nom ou enseigne'), { target: { value: ' Boutique Soa ' } })
  fireEvent.change(screen.getByLabelText('Téléphone'), { target: { value: ' 0340011122 ' } })
  fireEvent.change(screen.getByLabelText('Identifiant'), { target: { value: ' soa ' } })
  fireEvent.change(screen.getByLabelText('Mot de passe', { exact: true }), { target: { value: 'secret-123' } })
  fireEvent.change(screen.getByLabelText('Confirmer le mot de passe'), { target: { value: 'secret-123' } })
}

describe('AuthScreen', () => {
  it('normalise aussi un numéro international rempli directement dans le formulaire', async () => {
    vi.mocked(register).mockResolvedValue(session)
    render(<AuthScreen onAuthenticated={vi.fn()} />)
    openRegistration(); fillRegistration()
    const phone = screen.getByLabelText('Téléphone') as HTMLInputElement
    // A mobile password manager may fill the DOM without an onChange event.
    phone.value = '+261 34 16 793 43'
    fireEvent.submit(phone.closest('form')!)
    await waitFor(() => expect(register).toHaveBeenCalledWith(expect.objectContaining({ phone: '0341679343' })))
  })

  it('refuse un numéro trop long sans en supprimer le dernier chiffre', () => {
    render(<AuthScreen onAuthenticated={vi.fn()} />)
    openRegistration(); fillRegistration()
    fireEvent.change(screen.getByLabelText('Téléphone'), { target: { value: '03416793431' } })
    fireEvent.submit(screen.getByLabelText('Téléphone').closest('form')!)
    expect(screen.getByLabelText('Téléphone')).toHaveValue('03416793431')
    expect(screen.getByRole('alert')).toHaveTextContent(PHONE_NUMBER_ERROR)
    expect(register).not.toHaveBeenCalled()
  })

  it('conserve le tarif sous la connexion et place le lien discret d’inscription ensuite', () => {
    render(<AuthScreen onAuthenticated={vi.fn()} />)
    const pricing = screen.getByRole('button', { name: /Calculer un tarif/ })
    const registration = screen.getByRole('button', { name: 'Créer un compte' })
    expect(screen.getByRole('button', { name: 'Se connecter' }).compareDocumentPosition(pricing) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(pricing.compareDocumentPosition(registration) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    fireEvent.click(pricing)
    expect(screen.getByRole('dialog', { name: 'Calculer un tarif' })).toBeInTheDocument()
  })

  it('masque le calculateur pendant la création de compte et le conserve au retour à la connexion', () => {
    render(<AuthScreen onAuthenticated={vi.fn()} />)
    openRegistration()
    expect(screen.queryByRole('button', { name: /Calculer un tarif/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Calculer un tarif' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Se connecter' }))
    fireEvent.click(screen.getByRole('button', { name: /Calculer un tarif/ }))
    expect(screen.getByRole('dialog', { name: 'Calculer un tarif' })).toBeInTheDocument()
  })

  it('ferme le calculateur lors du passage à la création de compte sans le rouvrir au retour', () => {
    render(<AuthScreen onAuthenticated={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /Calculer un tarif/ }))
    openRegistration()
    expect(screen.queryByRole('dialog', { name: 'Calculer un tarif' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Se connecter' }))
    expect(screen.getByRole('button', { name: /Calculer un tarif/ })).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Calculer un tarif' })).not.toBeInTheDocument()
  })

  it('crée uniquement un compte client via le parcours public puis ouvre sa session', async () => {
    const onAuthenticated = vi.fn()
    vi.mocked(register).mockResolvedValue(session)
    render(<AuthScreen onAuthenticated={onAuthenticated} />)
    openRegistration(); fillRegistration()
    fireEvent.click(screen.getByRole('button', { name: 'Créer mon compte' }))
    await waitFor(() => expect(onAuthenticated).toHaveBeenCalledWith(session))
    expect(register).toHaveBeenCalledWith({ username: 'soa', password: 'secret-123', displayName: 'Boutique Soa', phone: '0340011122' })
    expect(login).not.toHaveBeenCalled()
  })

  it('signale une inscription réussie au parcours de bienvenue sans la confondre avec une connexion', async () => {
    const onRegistered = vi.fn()
    const onAuthenticated = vi.fn()
    vi.mocked(register).mockResolvedValue(session)
    render(<AuthScreen onAuthenticated={onAuthenticated} onRegistered={onRegistered} />)
    openRegistration(); fillRegistration()
    fireEvent.click(screen.getByRole('button', { name: 'Créer mon compte' }))
    await waitFor(() => expect(onRegistered).toHaveBeenCalledWith(session))
    expect(onAuthenticated).not.toHaveBeenCalled()
  })

  it('n’affiche pas le parcours de bienvenue lors d’une connexion habituelle', async () => {
    const onRegistered = vi.fn()
    const onAuthenticated = vi.fn()
    vi.mocked(login).mockResolvedValue(session)
    render(<AuthScreen onAuthenticated={onAuthenticated} onRegistered={onRegistered} />)
    fireEvent.change(screen.getByLabelText('Identifiant'), { target: { value: 'soa' } })
    fireEvent.change(screen.getByLabelText('Mot de passe'), { target: { value: 'secret-123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Se connecter' }))
    await waitFor(() => expect(onAuthenticated).toHaveBeenCalledWith(session))
    expect(onRegistered).not.toHaveBeenCalled()
  })

  it('vérifie la confirmation avant de créer un compte', () => {
    render(<AuthScreen onAuthenticated={vi.fn()} />)
    openRegistration(); fillRegistration()
    fireEvent.change(screen.getByLabelText('Confirmer le mot de passe'), { target: { value: 'other-123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Créer mon compte' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Les mots de passe ne correspondent pas.')
    expect(register).not.toHaveBeenCalled()
  })

  it('permet de revenir à la connexion et efface les mots de passe du mode précédent', () => {
    render(<AuthScreen onAuthenticated={vi.fn()} />)
    openRegistration(); fillRegistration()
    fireEvent.click(screen.getByRole('button', { name: 'Se connecter' }))
    expect(screen.getByRole('heading', { name: 'Bienvenue' })).toBeInTheDocument()
    expect(screen.getByLabelText('Mot de passe')).toHaveValue('')
    expect(screen.getByLabelText('Mot de passe')).toHaveAttribute('autocomplete', 'current-password')
  })

  it('affiche une erreur serveur sans quitter le formulaire', async () => {
    vi.mocked(register).mockRejectedValue(new Error('Cet identifiant est déjà utilisé.'))
    render(<AuthScreen onAuthenticated={vi.fn()} />)
    openRegistration(); fillRegistration()
    fireEvent.click(screen.getByRole('button', { name: 'Créer mon compte' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Cet identifiant est déjà utilisé.')
    expect(screen.getByRole('button', { name: 'Créer mon compte' })).toBeEnabled()
  })
})
