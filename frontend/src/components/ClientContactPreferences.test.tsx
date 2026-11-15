import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { updateClientContactNumbers, updateClientPayoutDestinations, type ClientProfile } from '../api/client'
import { ClientContactPreferences } from './ClientContactPreferences'

vi.mock('../api/client', () => ({ updateClientContactNumbers: vi.fn(), updateClientPayoutDestinations: vi.fn() }))

const payouts = [
  { id: 'p1', operator: 'MVola', accountHolder: 'Soa', number: '034 11 111 11', priority: 1, active: true },
  { id: 'p2', operator: 'Orange Money', accountHolder: 'Soa', number: '032 22 222 22', priority: 2, active: true },
]
const profile: ClientProfile = { accountId: 'seller', username: 'soa', displayName: 'Soa', phone: '0340000000', defaultPickup: null, version: 4, payoutDestinations: payouts }

afterEach(() => { cleanup(); vi.clearAllMocks() })

describe('shared payout editor in the client profile', () => {
  it('keeps the existing client save and priority order with activation changes', async () => {
    const saved = { ...profile, version: 5 }
    vi.mocked(updateClientPayoutDestinations).mockResolvedValue(saved)
    const onSaved = vi.fn()
    render(<ClientContactPreferences accessToken="token" profile={profile} section="payouts" onSaved={onSaved} />)
    fireEvent.click(screen.getByRole('button', { name: 'Monter 032 22 222 22' }))
    fireEvent.click(screen.getAllByRole('checkbox', { name: 'Actif' })[0])
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(updateClientPayoutDestinations).toHaveBeenCalledWith(4, [
      { ...payouts[1], priority: 1, active: false },
      { ...payouts[0], priority: 2 },
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
