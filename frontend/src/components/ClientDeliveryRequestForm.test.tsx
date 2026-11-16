import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ClientDefaultPickup, DeliveryRequestRecord } from '../api/client'
import { searchAddress } from '../api/geocoding'
import { CLIENT_PACKAGE_CSV_TEMPLATE } from '../clientPackageCsv'
import { ClientDeliveryRequestForm } from './ClientDeliveryRequestForm'

vi.mock('../api/geocoding', () => ({ searchAddress: vi.fn() }))

vi.mock('./LocationPicker', () => ({
  LocationPicker: ({ title, onChange }: {
    title: string
    onChange: (location: {
      label: string
      destinationQuery: string
      latitude: number
      longitude: number
    }) => void
  }) => (
    <button type="button" onClick={() => onChange({
      label: title,
      destinationQuery: `${title}, Antananarivo`,
      latitude: -18.9,
      longitude: 47.52,
    })}>Confirmer {title}</button>
  ),
}))

const defaultPickup: ClientDefaultPickup = {
  label: 'Boutique Analakely',
  destinationQuery: 'Analakely, Antananarivo',
  latitude: -18.9089,
  longitude: 47.5212,
}

const createdRequest = {
  id: 'request-1',
  ownerAccountId: 'client-1',
  ownerDisplayName: 'Boutique Soa',
  requestKind: 'NORMAL',
  requestedDate: '2026-09-17',
  pickup: { label: 'Boutique Analakely', latitude: -18.9089, longitude: 47.5212 },
  capacity: { limit: 50, current: 0, requested: 1, projected: 1, warning: false, accepted: false },
  status: 'PENDING',
  version: 0,
  createdAt: '2026-09-16T08:00:00Z',
  updatedAt: '2026-09-16T08:00:00Z',
  packages: [],
} as DeliveryRequestRecord

describe('ClientDeliveryRequestForm', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('revient à l’historique pour une demande depuis l’espace client', () => {
    const onCancel = vi.fn()
    render(<ClientDeliveryRequestForm accessToken="token" defaultPickup={defaultPickup} onCreated={vi.fn()} onCancel={onCancel} />)
    fireEvent.click(screen.getByRole('button', { name: 'Retour à l’historique' }))
    expect(onCancel).toHaveBeenCalledOnce()
  })

  it('affiche la destination de retour et le suivi adaptés au contexte admin', () => {
    const onCancel = vi.fn()
    render(<ClientDeliveryRequestForm accessToken="token" defaultPickup={defaultPickup} clientAccountId="client-1" backLabel="Retour à la liste des clients" onCreated={vi.fn()} onCancel={onCancel} />)
    expect(screen.queryByText(/l’historique/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retour à la liste des clients' }))
    expect(onCancel).toHaveBeenCalledOnce()
  })

  it('transmet le début du créneau pour une demande normale', async () => {
    const createRequest = vi.fn().mockResolvedValue(createdRequest)
    const quotePrice = vi.fn().mockResolvedValue({
      pricingZoneId: 'zone-centre',
      pricingZoneName: 'Centre',
      deliveryFeeAriary: 3_000,
      distanceFromCenterMeters: 1_000,
      pricingZoneMaxDistanceMeters: 5_000,
      pricingPolicyVersion: 1,
      distanceMode: 'AIR_DISTANCE',
    })

    render(<ClientDeliveryRequestForm
      accessToken="client-token"
      defaultPickup={defaultPickup}
      createRequest={createRequest}
      quotePrice={quotePrice}
      onCreated={vi.fn()}
      onCancel={vi.fn()}
    />)

    fireEvent.click(screen.getByRole('button', { name: /Ajouter un colis/ }))
    fireEvent.change(screen.getByLabelText('Nom du client destinataire'), { target: { value: 'Miora' } })
    fireEvent.change(screen.getByLabelText('Contact client'), { target: { value: '034 00 000 00' } })
    fireEvent.change(screen.getByLabelText(/Libellé \/ repère de livraison/), { target: { value: 'Bureau Ankorondrano' } })
    fireEvent.change(screen.getByLabelText('Début du créneau'), { target: { value: '14:30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer Lieu de livraison' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Enregistrer le colis' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer le colis' }))
    fireEvent.click(screen.getByRole('button', { name: 'Envoyer la demande' }))

    await waitFor(() => expect(createRequest).toHaveBeenCalledWith(expect.objectContaining({
      requestKind: 'NORMAL',
      pickup: {
        label: 'Boutique Analakely',
        query: 'Analakely, Antananarivo',
        latitude: -18.9089,
        longitude: 47.5212,
      },
      packages: [expect.objectContaining({ desiredDeliveryTime: '14:30' })],
    })))
  })

  it('fixe visuellement une demande express à aujourd’hui sans champ de date', () => {
    render(<ClientDeliveryRequestForm
      accessToken="client-token"
      defaultPickup={defaultPickup}
      requestKind="EXPRESS"
      onCreated={vi.fn()}
      onCancel={vi.fn()}
    />)

    const dateSummary = screen.getByRole('group', { name: 'Date de la demande express' })
    expect(within(dateSummary).getByText('Aujourd’hui')).toBeInTheDocument()
    expect(within(dateSummary).queryByDisplayValue(/^\d{4}-\d{2}-\d{2}$/)).not.toBeInTheDocument()
    expect(screen.queryByText('Date souhaitée')).not.toBeInTheDocument()
  })

  it('importe tous les champs dans la liste pour contrôle et n’enregistre rien avant envoi', async () => {
    vi.mocked(searchAddress).mockResolvedValue({
      query: 'Ankorondrano', provider: 'NOMINATIM', cached: false, resultCount: 1,
      candidates: [{ id: 'place', osmId: 1, osmType: 'node', displayName: 'Ankorondrano, Antananarivo, Madagascar', latitude: -18.88, longitude: 47.52 }],
    })
    const createRequest = vi.fn().mockResolvedValue(createdRequest)
    const quotePrice = vi.fn().mockResolvedValue({
      pricingZoneId: 'centre', pricingZoneName: 'Centre', deliveryFeeAriary: 3000,
      distanceFromCenterMeters: 1000, pricingZoneMaxDistanceMeters: 5000, pricingPolicyVersion: 1, distanceMode: 'AIR_DISTANCE',
    })
    render(<ClientDeliveryRequestForm accessToken="admin-token" clientAccountId="client-42" defaultPickup={defaultPickup} createRequest={createRequest} quotePrice={quotePrice} onCreated={vi.fn()} onCancel={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Importer un fichier CSV' }))
    const file = new File([CLIENT_PACKAGE_CSV_TEMPLATE], 'colis.csv', { type: 'text/csv' })
    Object.defineProperty(file, 'arrayBuffer', { value: async () => new TextEncoder().encode(CLIENT_PACKAGE_CSV_TEMPLATE).buffer })
    fireEvent.change(screen.getByLabelText('Fichier de colis · CSV UTF-8'), { target: { files: [file] } })
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter les colis au formulaire' }))
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2))
    const packageList = screen.getByRole('list', { name: 'Colis de la demande' })
    const columnHeadings = packageList.querySelector('.client-package-list__header')!
    expect(Array.from(columnHeadings.children).map((cell) => cell.textContent)).toEqual([
      'N°', 'Destinataire', 'Destination', 'Encaissement', 'Options', '',
    ])
    within(packageList).getAllByRole('listitem').forEach((row, index) => {
      expect(row.children).toHaveLength(columnHeadings.children.length)
      expect(row.firstElementChild).toHaveClass('client-package-row__index')
      expect(row.firstElementChild).toHaveTextContent(String(index + 1))
      expect(row.children[1]).toHaveClass('client-package-row__recipient')
      expect(row.children[2]).toHaveClass('client-package-row__destination')
      expect(row.children[3]).toHaveClass('client-package-row__amount')
      expect(row.lastElementChild).toHaveClass('client-package-row__actions')
    })
    expect(screen.getByText('Bureau Ankorondrano')).toBeInTheDocument()
    expect(screen.getByText('Lieu retenu : Ankorondrano, Antananarivo')).toBeInTheDocument()
    expect(screen.getByText(/Vérifiez les destinataires, les lieux retenus/)).toBeInTheDocument()
    expect(createRequest).not.toHaveBeenCalled()
    expect(searchAddress).toHaveBeenCalledWith(expect.any(String), 'admin-token', expect.any(AbortSignal), { purpose: 'DESTINATION', requestKind: 'NORMAL', clientAccountId: 'client-42' })
    fireEvent.click(screen.getByRole('button', { name: 'Envoyer la demande' }))
    await waitFor(() => expect(createRequest).toHaveBeenCalledWith(expect.objectContaining({ packages: [
      expect.objectContaining({ recipientName: 'Miora', contact: '0340000000', amountToCollectAriary: 25000, faritra: false, mobileMoneyPaid: false, weightKg: 1.5, desiredDeliveryTime: '14:00', destination: expect.objectContaining({ label: 'Bureau Ankorondrano', latitude: -18.88, query: 'Ankorondrano, Antananarivo, Madagascar' }) }),
      expect.objectContaining({ recipientName: 'Rakoto', mobileMoneyPaid: true, destination: expect.objectContaining({ latitude: -18.9089 }) }),
    ] })))
  })

  it('garde les lieux introuvables visibles et bloque l’envoi jusqu’à correction', async () => {
    vi.mocked(searchAddress).mockResolvedValue({ query: 'Ankorondrano', provider: 'NOMINATIM', cached: false, resultCount: 0, candidates: [] })
    const createRequest = vi.fn()
    const quotePrice = vi.fn().mockResolvedValue({
      pricingZoneId: 'centre', pricingZoneName: 'Centre', deliveryFeeAriary: 3000,
      distanceFromCenterMeters: 1000, pricingZoneMaxDistanceMeters: 5000, pricingPolicyVersion: 1, distanceMode: 'AIR_DISTANCE',
    })
    render(<ClientDeliveryRequestForm accessToken="token" defaultPickup={defaultPickup} createRequest={createRequest} quotePrice={quotePrice} onCreated={vi.fn()} onCancel={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Importer un fichier CSV' }))
    const file = new File([CLIENT_PACKAGE_CSV_TEMPLATE], 'colis.csv', { type: 'text/csv' })
    Object.defineProperty(file, 'arrayBuffer', { value: async () => new TextEncoder().encode(CLIENT_PACKAGE_CSV_TEMPLATE).buffer })
    fireEvent.change(screen.getByLabelText('Fichier de colis · CSV UTF-8'), { target: { files: [file] } })
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter les colis au formulaire' }))
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2))
    expect(screen.getByText(/1 colis à corriger/)).toBeInTheDocument()
    expect(screen.getByText(/Aucun lieu trouvé/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Envoyer la demande' })).toBeDisabled()
    expect(createRequest).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Modifier le colis 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer Lieu de livraison' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Enregistrer le colis' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer le colis' }))
    expect(screen.getByRole('button', { name: 'Envoyer la demande' })).toBeEnabled()
    expect(screen.queryByText(/Aucun lieu trouvé/)).not.toBeInTheDocument()
  })
})
