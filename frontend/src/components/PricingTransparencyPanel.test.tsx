import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { getClientPricingPolicies } from '../api/client'
import { PricingTransparencyPanel } from './PricingTransparencyPanel'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, getClientPricingPolicies: vi.fn() }
})

describe('PricingTransparencyPanel', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('rend le mode de distance de la grille visible au client', async () => {
    vi.mocked(getClientPricingPolicies).mockResolvedValue([{
      id: 1,
      requestKind: 'NORMAL',
      clientSpecific: true,
      version: 2,
      distanceMode: 'ROAD_DISTANCE',
      center: { label: 'Analakely', latitude: -18.9089, longitude: 47.5212 },
      defaultWeightKg: 1,
      zones: [{ id: 'zone-1', name: 'Centre', maxDistanceMeters: 5_000, deliveryFeeAriary: 5_000 }],
      weightTiers: [],
      timeSlots: [],
    }])

    render(<PricingTransparencyPanel accessToken="client-token" />)

    expect(await screen.findByText('Mode : distance routière')).toBeInTheDocument()
  })
})
