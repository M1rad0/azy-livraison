import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { quoteDeliveryPrice } from '../api/client'
import { ClientPackageDialog, emptyClientPackage } from './ClientPackageDialog'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, quoteDeliveryPrice: vi.fn() }
})

vi.mock('./LocationPicker', () => ({
  LocationPicker: ({ onChange }: { onChange: (value: Record<string, unknown>) => void }) => (
    <button type="button" onClick={() => onChange({
      label: 'Lot II M 42, rue très longue retournée par OSM, Antananarivo',
      destinationQuery: 'Lot II M 42, rue très longue retournée par OSM, Antananarivo',
      latitude: -18.8792,
      longitude: 47.5176,
    })}>Choisir une adresse OSM</button>
  ),
}))

describe('ClientPackageDialog', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('conserve le repère saisi séparément de l’adresse géocodée', async () => {
    vi.mocked(quoteDeliveryPrice).mockResolvedValue({
      pricingZoneId: 'zone-1',
      pricingZoneName: 'Centre',
      deliveryFeeAriary: 3_000,
      distanceFromCenterMeters: 1_000,
      pricingZoneMaxDistanceMeters: 5_000,
      pricingPolicyVersion: 1,
    })
    const onSave = vi.fn()
    render(<ClientPackageDialog
      accessToken="client-token"
      initialValue={emptyClientPackage('package-1')}
      packageNumber={1}
      onSave={onSave}
      onClose={vi.fn()}
    />)

    fireEvent.change(screen.getByLabelText('Nom du client destinataire'), { target: { value: 'Miora' } })
    fireEvent.change(screen.getByLabelText('Contact client'), { target: { value: '034 00 000 00' } })
    fireEvent.change(screen.getByLabelText(/Libellé \/ repère de livraison/), { target: { value: 'Bureau Ankorondrano' } })
    fireEvent.click(screen.getByRole('button', { name: 'Choisir une adresse OSM' }))

    expect(screen.getByLabelText(/Libellé \/ repère de livraison/)).toHaveValue('Bureau Ankorondrano')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Enregistrer le colis' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer le colis' }))

    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      destination: expect.objectContaining({
        label: 'Bureau Ankorondrano',
        destinationQuery: 'Lot II M 42, rue très longue retournée par OSM, Antananarivo',
      }),
    }))
  })

  it('ne propose pas d’heure souhaitée pour une demande normale', () => {
    render(<ClientPackageDialog
      accessToken="client-token"
      initialValue={emptyClientPackage('package-normal')}
      packageNumber={1}
      requestKind="NORMAL"
      onSave={vi.fn()}
      onClose={vi.fn()}
    />)

    expect(screen.queryByLabelText(/Heure souhaitée/)).not.toBeInTheDocument()
  })

  it('réserve l’heure souhaitée aux demandes expresses et impose une heure future', () => {
    render(<ClientPackageDialog
      accessToken="client-token"
      initialValue={emptyClientPackage('package-express')}
      packageNumber={1}
      requestKind="EXPRESS"
      onSave={vi.fn()}
      onClose={vi.fn()}
    />)

    const desiredTime = screen.getByLabelText(/Heure souhaitée/)
    expect(desiredTime).toHaveAttribute('type', 'time')
    expect(desiredTime).toHaveAttribute('min', expect.stringMatching(/^\d{2}:\d{2}$/))
    expect(screen.getByText(/postérieure à maintenant/)).toBeInTheDocument()
  })
})
