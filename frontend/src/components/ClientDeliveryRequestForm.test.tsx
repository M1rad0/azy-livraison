import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ClientDefaultPickup, DeliveryRequestRecord } from '../api/client'
import { ClientDeliveryRequestForm } from './ClientDeliveryRequestForm'

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
})
