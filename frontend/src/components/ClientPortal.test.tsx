import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  DailyCapacityExceededError,
  ClientApiError,
  cancelDeliveryPackage,
  createDeliveryRequest,
  getClientProfile,
  listDeliveryRequests,
  quoteDeliveryPrice,
  updateClientContactNumbers,
  updateClientProfile,
  type ClientProfile,
  type DeliveryRequestRecord,
} from '../api/client'
import { ClientPortal } from './ClientPortal'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    getClientProfile: vi.fn(),
    listDeliveryRequests: vi.fn(),
    createDeliveryRequest: vi.fn(),
    cancelDeliveryPackage: vi.fn(),
    quoteDeliveryPrice: vi.fn(),
    updateClientContactNumbers: vi.fn(),
    updateClientProfile: vi.fn(),
  }
})

vi.mock('./LocationPicker', () => ({
  LocationPicker: ({ title, value, onChange }: {
    title: string
    value: { label?: string; destinationQuery?: string; latitude?: number; longitude?: number }
    onChange: (location: { label: string; destinationQuery: string; latitude: number; longitude: number }) => void
  }) => (
    <button type="button" aria-label={`Choisir ${title}`} onClick={() => onChange({
      label: value.label || title,
      destinationQuery: title,
      latitude: -18.9,
      longitude: 47.52,
    })}>{value.label || `Choisir ${title}`}</button>
  ),
}))

const profile: ClientProfile = {
  accountId: 'account-1',
  username: 'boutique-soa',
  displayName: 'Boutique Soa',
  phone: '034 00 000 00',
  defaultPickup: {
    label: 'Boutique Analakely',
    destinationQuery: 'Analakely, Antananarivo',
    latitude: -18.9089,
    longitude: 47.5212,
  },
  contactNumbers: [{
    id: 'contact-1',
    label: 'Boutique',
    number: '034 00 000 00',
    priority: 1,
    active: true,
  }],
  payoutDestinations: [{
    id: 'payout-1',
    operator: 'MVola',
    accountHolder: 'Boutique Soa',
    number: '034 11 111 11',
    priority: 1,
    active: true,
  }],
  version: 1,
}

const request: DeliveryRequestRecord = {
  id: 'request-1',
  ownerAccountId: profile.accountId,
  ownerDisplayName: profile.displayName,
  requestedDate: '2026-08-24',
  pickup: { label: 'Boutique Analakely', query: 'Analakely', latitude: -18.9089, longitude: 47.5212 },
  capacity: { limit: 50, current: 2, requested: 1, projected: 3, warning: false, accepted: false },
  status: 'PENDING',
  version: 0,
  createdAt: '2026-08-22T08:00:00Z',
  updatedAt: '2026-08-22T08:00:00Z',
  packages: [{
    id: 'package-1',
    sequenceNumber: 1,
    recipientName: 'Rakoto',
    faritra: false,
    destination: { label: 'Ampasampito', query: 'Ampasampito', latitude: -18.8957, longitude: 47.5458 },
    contact: '032 00 000 00',
    amountToCollectAriary: 45_000,
    mobileMoneyPaid: false,
    pricingZoneId: 'zone-centre',
    pricingZoneName: 'Centre-ville',
    deliveryFeeAriary: 3_000,
    distanceFromCenterMeters: 1_200,
    pricingZoneMaxDistanceMeters: 4_000,
    pricingPolicyVersion: 1,
    effectiveWeightKg: 1,
    weightSource: 'DEFAULT_SETTING',
    status: 'PENDING',
    version: 0,
    createdAt: '2026-08-22T08:00:00Z',
    updatedAt: '2026-08-22T08:00:00Z',
  }],
}

describe('ClientPortal', () => {
  beforeEach(() => {
    vi.mocked(getClientProfile).mockResolvedValue(profile)
    vi.mocked(listDeliveryRequests).mockResolvedValue([request])
    vi.mocked(updateClientProfile).mockResolvedValue(profile)
    vi.mocked(updateClientContactNumbers).mockResolvedValue(profile)
    vi.mocked(quoteDeliveryPrice).mockResolvedValue({
      pricingZoneId: 'zone-centre',
      pricingZoneName: 'Centre-ville',
      deliveryFeeAriary: 3_000,
      distanceFromCenterMeters: 1_200,
      pricingZoneMaxDistanceMeters: 4_000,
      pricingPolicyVersion: 1,
    })
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('shows the client history and its useful package summary', async () => {
    render(<ClientPortal accessToken="client-token" />)

    expect(await screen.findByRole('heading', { name: 'Bonjour, Boutique Soa' })).toBeInTheDocument()
    expect(screen.getAllByText('Boutique Analakely').length).toBeGreaterThan(0)
    expect(screen.getByText('Demandes enregistrées').parentElement).toHaveTextContent('1')
    fireEvent.click(screen.getByText('Boutique Analakely', { selector: '.client-history-card__identity strong' }))
    expect(await screen.findByText('Rakoto')).toBeInTheDocument()
    expect(screen.getAllByText(/45.*000 Ar/, { selector: 'strong' }).length).toBeGreaterThan(0)
    expect(screen.getByText(/Zone Centre-ville/)).toBeInTheDocument()
    expect(screen.getByText(/dont 3.*000 Ar de livraison/)).toBeInTheDocument()
  })

  it('sépare les informations du compte et les numéros de versement en sous-onglets', async () => {
    render(<ClientPortal accessToken="client-token" />)

    await screen.findByRole('heading', { name: 'Bonjour, Boutique Soa' })
    fireEvent.click(screen.getByRole('button', { name: 'Mon profil' }))

    expect(await screen.findByRole('heading', { name: 'Coordonnées' })).toBeInTheDocument()
    const informationTab = screen.getByRole('tab', { name: /Informations/ })
    const payoutsTab = screen.getByRole('tab', { name: /Versements/ })
    expect(informationTab).toHaveAttribute('aria-selected', 'true')
    expect(payoutsTab).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByRole('heading', { name: 'Informations du compte' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Retrait par défaut' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Numéros de contact' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Numéros de versement' })).not.toBeInTheDocument()

    fireEvent.keyDown(informationTab, { key: 'ArrowRight' })

    expect(payoutsTab).toHaveAttribute('aria-selected', 'true')
    expect(payoutsTab).toHaveFocus()
    expect(screen.getByRole('heading', { name: 'Numéros de versement' })).toBeInTheDocument()
    expect(screen.getByDisplayValue('MVola')).toBeInTheDocument()
    expect(screen.getByDisplayValue('034 11 111 11')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Informations du compte' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Numéros de contact' })).not.toBeInTheDocument()

    fireEvent.keyDown(payoutsTab, { key: 'ArrowRight' })
    expect(informationTab).toHaveFocus()
    fireEvent.keyDown(informationTab, { key: 'ArrowLeft' })
    expect(payoutsTab).toHaveFocus()
    fireEvent.keyDown(payoutsTab, { key: 'Home' })
    expect(informationTab).toHaveFocus()
    expect(screen.getByRole('heading', { name: 'Informations du compte' })).toBeInTheDocument()
  })

  it('actualise le téléphone principal sans effacer un brouillon de versement', async () => {
    const updatedProfile = {
      ...profile,
      phone: '033 22 222 22',
      contactNumbers: [{ ...profile.contactNumbers![0], number: '033 22 222 22' }],
      version: 2,
    }
    vi.mocked(updateClientContactNumbers).mockResolvedValue(updatedProfile)

    render(<ClientPortal accessToken="client-token" />)
    await screen.findByRole('heading', { name: 'Bonjour, Boutique Soa' })
    fireEvent.click(screen.getByRole('button', { name: 'Mon profil' }))

    fireEvent.click(await screen.findByRole('tab', { name: /Versements/ }))
    fireEvent.change(screen.getByDisplayValue('MVola'), { target: { value: 'Orange Money' } })
    fireEvent.click(screen.getByRole('tab', { name: /Informations/ }))
    const contactsSection = screen.getByRole('heading', { name: 'Numéros de contact' }).closest('section')
    expect(contactsSection).not.toBeNull()
    fireEvent.change(within(contactsSection as HTMLElement).getByLabelText('Numéro'), { target: { value: '033 22 222 22' } })
    fireEvent.click(within(contactsSection as HTMLElement).getByRole('button', { name: 'Enregistrer les contacts' }))

    await waitFor(() => expect(screen.getByLabelText(/Téléphone principal/)).toHaveValue('033 22 222 22'))
    fireEvent.click(screen.getByRole('tab', { name: /Versements/ }))
    expect(screen.getByDisplayValue('Orange Money')).toBeInTheDocument()
  })

  it('verrouille un formulaire de numéros pendant son enregistrement', async () => {
    let resolveUpdate!: (saved: ClientProfile) => void
    vi.mocked(updateClientContactNumbers).mockReturnValue(new Promise((resolve) => { resolveUpdate = resolve }))

    render(<ClientPortal accessToken="client-token" />)
    await screen.findByRole('heading', { name: 'Bonjour, Boutique Soa' })
    fireEvent.click(screen.getByRole('button', { name: 'Mon profil' }))
    const contactsSection = (await screen.findByRole('heading', { name: 'Numéros de contact' })).closest('section') as HTMLElement
    const numberInput = within(contactsSection).getByLabelText('Numéro')
    fireEvent.change(numberInput, { target: { value: '033 22 222 22' } })
    fireEvent.click(within(contactsSection).getByRole('button', { name: 'Enregistrer les contacts' }))

    expect(numberInput).toBeDisabled()
    expect(within(contactsSection).getByRole('button', { name: 'Ajouter un numéro' })).toBeDisabled()
    expect(within(contactsSection).getByRole('button', { name: /Supprimer/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Enregistrer les modifications' })).toBeDisabled()
    fireEvent.click(screen.getByRole('tab', { name: /Versements/ }))
    expect(screen.getByDisplayValue('MVola')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()

    resolveUpdate(profile)
    await waitFor(() => expect(screen.getByDisplayValue('MVola')).not.toBeDisabled())
  })

  it('conserve le montant déclaré lorsque Mobile Money est signalé', async () => {
    vi.mocked(listDeliveryRequests).mockResolvedValue([{
      ...request,
      packages: [{ ...request.packages[0], mobileMoneyPaid: true }],
    }])

    render(<ClientPortal accessToken="client-token" />)

    expect(await screen.findByRole('heading', { name: 'Bonjour, Boutique Soa' })).toBeInTheDocument()
    expect(screen.getByText(/45.*000 Ar/, { selector: '.client-history-card__metric--amount strong' })).toBeInTheDocument()
  })

  it('bloque strictement une demande qui dépasse la capacité et indique combien retirer', async () => {
    vi.mocked(listDeliveryRequests).mockResolvedValue([])
    vi.mocked(createDeliveryRequest)
      .mockRejectedValueOnce(new DailyCapacityExceededError('Capacité dépassée.', '2026-08-24', 1, 1, 1, 2, 0, 1))

    render(<ClientPortal accessToken="client-token" />)
    await screen.findByRole('heading', { name: 'Bonjour, Boutique Soa' })
    fireEvent.click(screen.getByRole('button', { name: 'Nouvelle demande' }))
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un colis' }))

    fireEvent.change(screen.getByLabelText('Nom du client destinataire'), { target: { value: 'Rakoto' } })
    fireEvent.change(screen.getByLabelText('Contact client'), { target: { value: '034 00 000 00' } })
    fireEvent.change(screen.getByLabelText(/Libellé \/ repère de livraison/), { target: { value: 'Bureau Ampasampito' } })
    fireEvent.change(screen.getByLabelText('Total à récupérer (Ar)'), { target: { value: '45000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Choisir Lieu de livraison' }))
    expect(await screen.findByText(/3.*000 Ar de livraison/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer le colis' }))
    fireEvent.click(screen.getByRole('button', { name: 'Envoyer la demande' }))

    expect(await screen.findByText('Capacité de la journée dépassée')).toBeInTheDocument()
    expect(screen.getByText(/Retirez 1 colis/)).toBeInTheDocument()
    expect(vi.mocked(createDeliveryRequest).mock.calls[0][0].packages[0]).not.toHaveProperty('pricingQuote')
    expect(vi.mocked(createDeliveryRequest).mock.calls[0][0].packages[0]).not.toHaveProperty('deliveryFeeAriary')
    expect(screen.queryByRole('button', { name: 'Confirmer quand même' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Envoyer la demande' })).toBeDisabled()
    expect(createDeliveryRequest).toHaveBeenCalledTimes(1)
  })

  it('permet au client d’annuler un colis encore en attente', async () => {
    vi.mocked(cancelDeliveryPackage).mockResolvedValue(undefined)
    vi.mocked(listDeliveryRequests)
      .mockResolvedValueOnce([request])
      .mockResolvedValueOnce([{ ...request, packages: [{ ...request.packages[0], finalOutcome: 'CANCELLED' }] }])

    render(<ClientPortal accessToken="client-token" />)
    await screen.findByRole('heading', { name: 'Bonjour, Boutique Soa' })
    fireEvent.click(screen.getByText('Boutique Analakely', { selector: '.client-history-card__identity strong' }))
    fireEvent.click(screen.getByRole('button', { name: 'Annuler' }))
    fireEvent.click(screen.getByRole('button', { name: 'Annuler le colis' }))

    await waitFor(() => expect(cancelDeliveryPackage).toHaveBeenCalledWith('package-1', 0, 'client-token'))
    expect(await screen.findByText('Annulé')).toBeInTheDocument()
    expect(screen.getByText('Annulée')).toBeInTheDocument()
  })

  it('blocks a package whose destination is outside the configured coverage', async () => {
    vi.mocked(listDeliveryRequests).mockResolvedValue([])
    vi.mocked(quoteDeliveryPrice).mockRejectedValue(new ClientApiError(
      'Cette destination se trouve en dehors des zones couvertes.',
      'DELIVERY_DESTINATION_OUTSIDE_PRICING_AREA',
      422,
    ))

    render(<ClientPortal accessToken="client-token" />)
    await screen.findByRole('heading', { name: 'Bonjour, Boutique Soa' })
    fireEvent.click(screen.getByRole('button', { name: 'Nouvelle demande' }))
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un colis' }))
    fireEvent.click(screen.getByRole('button', { name: 'Choisir Lieu de livraison' }))

    expect(await screen.findByText('Zone non couverte')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer le colis' })).toBeDisabled()
  })
})
