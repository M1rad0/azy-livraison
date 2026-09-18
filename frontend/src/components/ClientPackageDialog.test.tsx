import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { quoteDeliveryPrice } from '../api/client'
import { endOfOneHourWindow } from '../deliveryWindows'
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
      distanceMode: 'ROAD_DISTANCE',
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
    fireEvent.change(screen.getByLabelText('Début du créneau'), { target: { value: '18:30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Choisir une adresse OSM' }))

    expect(screen.getByLabelText(/Libellé \/ repère de livraison/)).toHaveValue('Bureau Ankorondrano')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Enregistrer le colis' })).toBeEnabled())
    expect(quoteDeliveryPrice).toHaveBeenCalledWith(
      expect.objectContaining({ latitude: -18.8792, longitude: 47.5176 }),
      'client-token',
      expect.any(AbortSignal),
      expect.objectContaining({ requestKind: 'NORMAL', desiredDeliveryTime: '18:30' }),
    )
    expect(screen.getByText('1,0 km · distance routière')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer le colis' }))

    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      desiredDeliveryTime: '18:30',
      destination: expect.objectContaining({
        label: 'Bureau Ankorondrano',
        destinationQuery: 'Lot II M 42, rue très longue retournée par OSM, Antananarivo',
      }),
    }))
  })

  it('propose aussi un créneau d’une heure pour une demande normale', () => {
    render(<ClientPackageDialog
      accessToken="client-token"
      initialValue={emptyClientPackage('package-normal')}
      packageNumber={1}
      requestKind="NORMAL"
      onSave={vi.fn()}
      onClose={vi.fn()}
    />)

    const start = screen.getByLabelText('Début du créneau')
    const end = screen.getByLabelText('Fin du créneau')
    fireEvent.change(start, { target: { value: '14:00' } })

    expect(end).toHaveValue('15:00')
    expect(end).toHaveAttribute('readonly')
  })

  it('impose un début futur pour le créneau express', () => {
    render(<ClientPackageDialog
      accessToken="client-token"
      initialValue={emptyClientPackage('package-express')}
      packageNumber={1}
      requestKind="EXPRESS"
      onSave={vi.fn()}
      onClose={vi.fn()}
    />)

    const desiredTime = screen.getByLabelText('Début du créneau')
    expect(desiredTime).toHaveAttribute('type', 'time')
    expect(desiredTime).toHaveAttribute('min', expect.stringMatching(/^\d{2}:\d{2}$/))
    expect(screen.getByText(/postérieur à maintenant|Aucun nouveau créneau/)).toBeInTheDocument()
  })

  it('calcule la fin du créneau y compris à minuit', () => {
    expect(endOfOneHourWindow('08:00')).toBe('09:00')
    expect(endOfOneHourWindow('18:30')).toBe('19:30')
    expect(endOfOneHourWindow('23:00')).toBe('00:00')
  })
})
