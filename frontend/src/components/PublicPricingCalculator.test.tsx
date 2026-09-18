import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { quotePublicDeliveryPrice } from '../api/client'
import { PublicPricingCalculator } from './PublicPricingCalculator'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, quotePublicDeliveryPrice: vi.fn() }
})

vi.mock('./LocationPicker', () => ({
  LocationPicker: ({ onChange }: { onChange: (value: Record<string, unknown>) => void }) => (
    <button type="button" onClick={() => onChange({
      label: 'Ankorondrano',
      destinationQuery: 'Ankorondrano',
      latitude: -18.8792,
      longitude: 47.5176,
    })}>Choisir Ankorondrano</button>
  ),
}))

describe('PublicPricingCalculator', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('justifie le tarif avec la distance routière arrondie à un dixième', async () => {
    vi.mocked(quotePublicDeliveryPrice).mockResolvedValue({
      pricingZoneId: 'zone-1',
      pricingZoneName: 'Centre',
      deliveryFeeAriary: 5_000,
      distanceFromCenterMeters: 2_946,
      pricingZoneMaxDistanceMeters: 5_000,
      pricingPolicyVersion: 1,
      distanceMode: 'ROAD_DISTANCE',
    })

    render(<PublicPricingCalculator onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Choisir Ankorondrano' }))

    await waitFor(() => expect(screen.getByText('2,9 km · distance routière')).toBeInTheDocument())
    expect(screen.getByText('Zone Centre')).toBeInTheDocument()
    expect(screen.getByText(/5.*000 Ar/)).toBeInTheDocument()
  })
})
