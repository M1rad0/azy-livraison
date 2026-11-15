import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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

  it('autorise une fin libre au lieu d’imposer une heure', () => {
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
    expect(end).not.toHaveAttribute('readonly')
    fireEvent.change(end, { target: { value: '18:00' } })
    expect(end).toHaveValue('18:00')
  })

  it('regroupe montant et poids séparément du créneau sans changer leurs valeurs', () => {
    const initialValue = { ...emptyClientPackage('amounts'), amountToCollectAriary: '25000', weightKg: '2.5' }
    render(<ClientPackageDialog accessToken="token" initialValue={initialValue} packageNumber={1} onSave={vi.fn()} onClose={vi.fn()} />)
    const values = screen.getByRole('group', { name: 'Montant et poids' })
    expect(within(values).getByLabelText('Total à récupérer (Ar)')).toHaveValue(25000)
    expect(within(values).getByLabelText('Poids (kg) · facultatif')).toHaveValue(2.5)
    expect(within(values).queryByLabelText('Début du créneau')).not.toBeInTheDocument()
    const schedule = screen.getByRole('group', { name: 'Créneau de livraison · facultatif' })
    expect(within(schedule).getByLabelText('Début du créneau')).toBeInTheDocument()
    fireEvent.change(within(values).getByLabelText('Total à récupérer (Ar)'), { target: { value: '30000' } })
    fireEvent.change(within(values).getByLabelText('Poids (kg) · facultatif'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: 'Matin' }))
    expect(within(values).getByLabelText('Total à récupérer (Ar)')).toHaveValue(30000)
    expect(within(values).getByLabelText('Poids (kg) · facultatif')).toHaveValue(3)
  })

  it('affiche le tarif convenu sans une décomposition qui ne correspond plus au montant', async () => {
    const quotePrice = vi.fn().mockResolvedValue({
      pricingZoneId: 'zone-1', pricingZoneName: 'Centre', deliveryFeeAriary: 2_000,
      distanceFromCenterMeters: 1_000, pricingZoneMaxDistanceMeters: 5_000,
      pricingPolicyVersion: 1, distanceMode: 'ROAD_DISTANCE',
      baseDeliveryFeeAriary: 3_000, timeSurchargeAriary: 500, finalFeeOverridden: true,
    })
    render(<ClientPackageDialog accessToken="token" initialValue={{ ...emptyClientPackage('agreed'),
      destination: { label: 'Agence', destinationQuery: 'Analakely', latitude: -18.8792, longitude: 47.5176 },
    }} packageNumber={1} quotePrice={quotePrice} onSave={vi.fn()} onClose={vi.fn()} />)
    await waitFor(() => expect(screen.getByText('Tarif convenu conservé')).toBeInTheDocument())
    expect(screen.getByText(/2\s000 Ar de livraison/)).toBeInTheDocument()
    expect(screen.queryByText(/Base .*Ar/)).not.toBeInTheDocument()
  })

  it('rend Matin et Soir exclusifs et désactive les deux champs horaires', () => {
    render(<ClientPackageDialog accessToken="token" initialValue={emptyClientPackage('period')} packageNumber={1} onSave={vi.fn()} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Matin' }))
    expect(screen.getByRole('button', { name: 'Matin' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByLabelText('Début du créneau')).toBeDisabled()
    expect(screen.getByLabelText('Fin du créneau')).toHaveValue('12:00')
    fireEvent.click(screen.getByRole('button', { name: 'Soir' }))
    expect(screen.getByRole('button', { name: 'Matin' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByLabelText('Début du créneau')).toHaveValue('12:00')
    fireEvent.click(screen.getByRole('button', { name: 'Heures précises' }))
    expect(screen.getByLabelText('Début du créneau')).toBeEnabled()
    expect(screen.getByLabelText('Fin du créneau')).toBeEnabled()
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
