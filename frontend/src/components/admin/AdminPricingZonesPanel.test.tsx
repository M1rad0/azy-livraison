import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getAdvancedPricingPolicies, listAdminClients, saveAdvancedPricingPolicy, type AdvancedPricingPolicy } from '../../api/admin'
import { AdminPricingZonesPanel } from './AdminPricingZonesPanel'

vi.mock('../../api/admin', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../api/admin')>(),
  getAdvancedPricingPolicies: vi.fn(), listAdminClients: vi.fn(), resetClientPricingPolicy: vi.fn(), saveAdvancedPricingPolicy: vi.fn(),
}))
vi.mock('../LocationPicker', () => ({ LocationPicker: () => <div>Centre tarifaire confirmé</div> }))

function policy(kind: 'NORMAL' | 'EXPRESS' = 'NORMAL'): AdvancedPricingPolicy {
  return {
    id: kind === 'NORMAL' ? 1 : 2, requestKind: kind, clientSpecific: false, version: 2,
    distanceMode: 'AIR_DISTANCE', center: { label: 'Analakely', latitude: -18.9, longitude: 47.52 }, defaultWeightKg: 1,
    zones: [{ id: 'zone-1', name: 'Centre', maxDistanceMeters: 5000, deliveryFeeAriary: 5000 }],
    weightTiers: [], timeSlots: [], morningSurchargeAriary: kind === 'NORMAL' ? 500 : 700,
    afternoonSurchargeAriary: kind === 'NORMAL' ? 500 : 900,
    keywordRules: [{ id: 'keyword-1', keyword: 'Ivándry Nord', deliveryFeeAriary: 4000 }],
  }
}

function csvFile(content: string, name = 'tarifs.csv') {
  const file = new File([content], name, { type: 'text/csv' })
  Object.defineProperty(file, 'arrayBuffer', { value: async () => new TextEncoder().encode(content).buffer })
  return file
}

async function openPanel() {
  render(<AdminPricingZonesPanel accessToken="admin-token" />)
  await screen.findByRole('button', { name: 'Enregistrer cette grille' })
}

describe('AdminPricingZonesPanel', () => {
  beforeEach(() => {
    vi.mocked(listAdminClients).mockResolvedValue([])
    vi.mocked(getAdvancedPricingPolicies).mockResolvedValue([policy(), policy('EXPRESS')])
    vi.mocked(saveAdvancedPricingPolicy).mockResolvedValue(policy())
  })
  afterEach(() => { cleanup(); vi.resetAllMocks() })

  it('enregistre les suppléments configurés, les mots-clés édités et leur ordre', async () => {
    await openPanel()
    expect(screen.getByLabelText(/^Matin \(avant midi\)/)).toHaveValue(500)
    expect(screen.getByLabelText(/^Soir \(après midi\)/)).toHaveValue(500)
    fireEvent.change(screen.getByLabelText(/^Matin \(avant midi\)/), { target: { value: '650' } })
    fireEvent.change(screen.getByLabelText(/^Soir \(après midi\)/), { target: { value: '850' } })
    fireEvent.change(screen.getByLabelText('Mot-clé'), { target: { value: 'Ivandry' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un mot-clé' }))
    fireEvent.change(screen.getAllByLabelText('Mot-clé')[1], { target: { value: 'Ivandry Nord' } })
    fireEvent.change(screen.getAllByLabelText(/^Tarif de base/)[1], { target: { value: '3500' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cette grille' }))
    await waitFor(() => expect(saveAdvancedPricingPolicy).toHaveBeenCalledWith(expect.objectContaining({
      requestKind: 'NORMAL', morningSurchargeAriary: 650, afternoonSurchargeAriary: 850,
      keywordRules: [{ id: 'keyword-1', keyword: 'Ivandry', deliveryFeeAriary: 4000 }, { keyword: 'Ivandry Nord', deliveryFeeAriary: 3500 }],
    }), 'admin-token'))
  })

  it('importe de façon additive et ignore les doublons sans écraser le tarif existant', async () => {
    await openPanel()
    fireEvent.change(screen.getByLabelText(/Importer des mots-clés/), { target: { files: [csvFile('\uFEFFclé;tarif\r\nIVANDRY   nord;1000\r\nAnalakely;3500\r\nanálakely;8000')] } })
    expect(await screen.findByText(/1 règle\(s\) ajoutée\(s\), 2 doublon\(s\) ignoré\(s\)/)).toBeInTheDocument()
    expect(screen.getAllByLabelText('Mot-clé').map((input) => (input as HTMLInputElement).value)).toEqual(['Ivándry Nord', 'Analakely'])
    expect(screen.getAllByLabelText(/^Tarif de base/).map((input) => (input as HTMLInputElement).value)).toEqual(['4000', '3500'])
    expect(saveAdvancedPricingPolicy).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Supprimer le mot-clé Analakely' }))
    expect(screen.getAllByLabelText('Mot-clé')).toHaveLength(1)
  })

  it('rejette tout un import invalide et conserve les règles actuelles', async () => {
    await openPanel()
    fireEvent.change(screen.getByLabelText(/Importer des mots-clés/), { target: { files: [csvFile('clé;tarif\nAnalakely;3500\nAutre;-1')] } })
    expect(await screen.findByRole('alert')).toHaveTextContent('Ligne 3')
    expect(screen.getAllByLabelText('Mot-clé')).toHaveLength(1)
    expect(screen.getByLabelText('Mot-clé')).toHaveValue('Ivándry Nord')
  })

  it('refuse les doublons manuels et les suppléments négatifs avant de sauvegarder', async () => {
    await openPanel()
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un mot-clé' }))
    fireEvent.change(screen.getAllByLabelText('Mot-clé')[1], { target: { value: 'IVANDRY   nord' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cette grille' }))
    expect(await screen.findByText(/Chaque mot-clé doit être unique/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Supprimer le mot-clé IVANDRY\s+nord/ }))
    fireEvent.change(screen.getByLabelText(/^Matin \(avant midi\)/), { target: { value: '-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cette grille' }))
    expect(await screen.findByText(/Les suppléments Matin et Soir doivent être/)).toBeInTheDocument()
    expect(saveAdvancedPricingPolicy).not.toHaveBeenCalled()
  })

  it('charge les montants propres à la grille express et utilise 500 pour une ancienne grille', async () => {
    const legacy = policy()
    delete legacy.morningSurchargeAriary; delete legacy.afternoonSurchargeAriary; delete legacy.keywordRules
    vi.mocked(getAdvancedPricingPolicies).mockResolvedValue([legacy, policy('EXPRESS')])
    await openPanel()
    expect(screen.getByLabelText(/^Matin \(avant midi\)/)).toHaveValue(500)
    expect(screen.getByLabelText(/^Soir \(après midi\)/)).toHaveValue(500)
    expect(screen.getByText('Aucun tarif par mot-clé.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: 'Express' }))
    expect(await screen.findByLabelText(/^Matin \(avant midi\)/)).toHaveValue(700)
    expect(screen.getByLabelText(/^Soir \(après midi\)/)).toHaveValue(900)
  })

  it('personnalise la grille héritée d’un client sans réutiliser les identifiants des règles par défaut', async () => {
    vi.mocked(listAdminClients).mockResolvedValue([{
      accountId: 'client-1', username: 'boutique', displayName: 'Boutique Rado', phone: '0340000000',
      defaultPickup: null, enabled: true, expressDeliveryEnabled: true, version: 1,
    }])
    await openPanel()
    const target = screen.getByRole('combobox', { name: 'Rechercher le client de cette grille' })
    fireEvent.focus(target)
    fireEvent.change(target, { target: { value: 'Boutique Rado' } })
    fireEvent.mouseDown(await screen.findByRole('option', { name: /Boutique Rado/ }))
    await screen.findByText(/enregistrez pour personnaliser Boutique Rado/)
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cette grille' }))
    await waitFor(() => expect(saveAdvancedPricingPolicy).toHaveBeenCalledWith(expect.objectContaining({
      clientAccountId: 'client-1', version: undefined, morningSurchargeAriary: 500, afternoonSurchargeAriary: 500,
      keywordRules: [{ keyword: 'Ivándry Nord', deliveryFeeAriary: 4000 }],
    }), 'admin-token'))
  })
})
