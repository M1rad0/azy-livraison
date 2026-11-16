import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { updateClientContactNumbers, updateClientPayoutDestinations, type ClientProfile } from '../api/client'
import { ClientContactPreferences } from './ClientContactPreferences'
import { PHONE_NUMBER_ERROR } from '../phone'
import { PAYOUT_OPERATOR_ERROR } from '../payoutOperators'

vi.mock('../api/client', () => ({ updateClientContactNumbers: vi.fn(), updateClientPayoutDestinations: vi.fn() }))

const payouts = [
  { id: 'p1', operator: 'MVola', accountHolder: 'Soa', number: '034 11 111 11', priority: 1, active: true },
  { id: 'p2', operator: 'Orange Money', accountHolder: 'Soa', number: '032 22 222 22', priority: 2, active: true },
]
const profile: ClientProfile = { accountId: 'seller', username: 'soa', displayName: 'Soa', phone: '0340000000', defaultPickup: null, version: 4, payoutDestinations: payouts }

afterEach(() => { cleanup(); vi.clearAllMocks() })

describe('shared payout editor in the client profile', () => {
  it.each(['contacts', 'payouts'] as const)('valide les numéros de %s même sans formulaire HTML', (section) => {
    render(<ClientContactPreferences accessToken="token" profile={profile} section={section} onSaved={vi.fn()} />)
    fireEvent.change(screen.getAllByLabelText('Numéro')[0], { target: { value: '03416793431' } })
    fireEvent.click(screen.getByRole('button', { name: section === 'contacts' ? 'Enregistrer les contacts' : 'Enregistrer les versements' }))
    expect(screen.getByRole('alert')).toHaveTextContent(PHONE_NUMBER_ERROR)
    expect(updateClientContactNumbers).not.toHaveBeenCalled()
    expect(updateClientPayoutDestinations).not.toHaveBeenCalled()
  })

  it('reconnaît un doublon national/international sans modifier le profil chargé', () => {
    render(<ClientContactPreferences accessToken="token" profile={profile} section="contacts" onSaved={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un numéro' }))
    fireEvent.change(screen.getAllByLabelText('Numéro')[1], { target: { value: '+261 34 00 000 00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les contacts' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Un même numéro ne peut pas apparaître deux fois')
    expect(profile.phone).toBe('0340000000')
    expect(updateClientContactNumbers).not.toHaveBeenCalled()
  })

  it('demande un opérateur autorisé sans remplacer un ancien opérateur inconnu', () => {
    const legacyPayout = { ...payouts[0], operator: 'Banque', number: '0341111111' }
    render(<ClientContactPreferences accessToken="token" profile={{ ...profile, payoutDestinations: [legacyPayout] }} section="payouts" onSaved={vi.fn()} />)
    expect(screen.getByRole('combobox', { name: 'Opérateur' })).toHaveValue('')
    expect(screen.getByLabelText('Numéro')).toHaveAttribute('type', 'tel')
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    expect(screen.getByRole('alert')).toHaveTextContent(PAYOUT_OPERATOR_ERROR)
    expect(updateClientPayoutDestinations).not.toHaveBeenCalled()
    expect(legacyPayout.operator).toBe('Banque')
  })

  it('propose uniquement les trois opérateurs et enregistre leur nom canonique', async () => {
    vi.mocked(updateClientPayoutDestinations).mockResolvedValue({ ...profile, version: 5 })
    render(<ClientContactPreferences accessToken="token" profile={{ ...profile, payoutDestinations: [{ ...payouts[0], operator: 'orange_money' }] }} section="payouts" onSaved={vi.fn()} />)
    const operator = screen.getByRole('combobox', { name: 'Opérateur' })
    expect(operator).toHaveValue('Orange Money')
    expect(Array.from((operator as HTMLSelectElement).options).map((option) => option.value)).toEqual(['', 'MVola', 'Orange Money', 'Airtel Money'])
    fireEvent.change(operator, { target: { value: 'Airtel Money' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(updateClientPayoutDestinations).toHaveBeenCalledWith(4, [expect.objectContaining({ operator: 'Airtel Money', number: '0341111111' })], 'token'))
  })

  it('normalise une ancienne orthographe autorisée uniquement lors de l’enregistrement', async () => {
    const oldPayout = { ...payouts[0], operator: 'mvola' }
    vi.mocked(updateClientPayoutDestinations).mockResolvedValue({ ...profile, version: 5 })
    render(<ClientContactPreferences accessToken="token" profile={{ ...profile, payoutDestinations: [oldPayout] }} section="payouts" onSaved={vi.fn()} />)
    expect(screen.getByRole('combobox', { name: 'Opérateur' })).toHaveValue('MVola')
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(updateClientPayoutDestinations).toHaveBeenCalledWith(4, [expect.objectContaining({ operator: 'MVola' })], 'token'))
    expect(oldPayout.operator).toBe('mvola')
  })

  it('conserve les libellés et les priorités de contact avec l’éditeur partagé admin/client', async () => {
    const saved = { ...profile, version: 5 }
    vi.mocked(updateClientContactNumbers).mockResolvedValue(saved)
    const onSaved = vi.fn()
    render(<ClientContactPreferences accessToken="token" profile={profile} section="contacts" onSaved={onSaved} />)
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un numéro' }))
    fireEvent.change(screen.getAllByLabelText('Libellé')[1], { target: { value: ' Assistant ' } })
    fireEvent.change(screen.getAllByLabelText('Numéro')[1], { target: { value: ' 032 11 111 11 ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Monter 0321111111' }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les contacts' }))
    await waitFor(() => expect(updateClientContactNumbers).toHaveBeenCalledWith(4, [
      { label: 'Assistant', number: '0321111111', priority: 1, active: true },
      { id: undefined, label: 'Principal', number: profile.phone, priority: 2, active: true },
    ], 'token'))
    expect(onSaved).toHaveBeenCalledWith(saved)
    expect(updateClientPayoutDestinations).not.toHaveBeenCalled()
  })

  it('keeps the existing client save and priority order with activation changes', async () => {
    const saved = { ...profile, version: 5 }
    vi.mocked(updateClientPayoutDestinations).mockResolvedValue(saved)
    const onSaved = vi.fn()
    render(<ClientContactPreferences accessToken="token" profile={profile} section="payouts" onSaved={onSaved} />)
    fireEvent.click(screen.getByRole('button', { name: 'Monter 032 22 222 22' }))
    fireEvent.click(screen.getAllByRole('checkbox', { name: 'Actif' })[0])
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(updateClientPayoutDestinations).toHaveBeenCalledWith(4, [
      { ...payouts[1], number: '0322222222', priority: 1, active: false },
      { ...payouts[0], number: '0341111111', priority: 2 },
    ], 'token'))
    expect(onSaved).toHaveBeenCalledWith(saved)
    expect(updateClientContactNumbers).not.toHaveBeenCalled()
  })

  it('still refuses an incomplete added number before saving', async () => {
    render(<ClientContactPreferences accessToken="token" profile={profile} section="payouts" onSaved={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un numéro' }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Chaque versement doit contenir un numéro.')
    expect(updateClientPayoutDestinations).not.toHaveBeenCalled()
  })

  it('disables all editable actions when the containing profile is locked', () => {
    render(<ClientContactPreferences accessToken="token" profile={profile} section="payouts" onSaved={vi.fn()} disabled />)
    expect(screen.getByRole('button', { name: 'Ajouter un numéro' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    screen.getAllByLabelText('Numéro').forEach((input) => expect(input).toBeDisabled())
  })
})
