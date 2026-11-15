import { cleanup, render, screen, within } from '@testing-library/react'
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
      morningSurchargeAriary: 500,
      afternoonSurchargeAriary: 500,
      keywordRules: [],
      zones: [{ id: 'zone-1', name: 'Centre', maxDistanceMeters: 5_000, deliveryFeeAriary: 5_000 }],
      weightTiers: [],
      timeSlots: [],
    }])

    render(<PricingTransparencyPanel accessToken="client-token" />)

    expect(await screen.findByText('Mode : distance routière')).toBeInTheDocument()
  })

  it('affiche une table de base par mot-clé et les suppléments exclusifs propres à chaque grille', async () => {
    const base = {
      id: 1, requestKind: 'NORMAL' as const, clientSpecific: true, version: 2, distanceMode: 'AIR_DISTANCE' as const,
      center: { label: 'Analakely', latitude: -18.9089, longitude: 47.5212 }, defaultWeightKg: 1,
      zones: [{ id: 'zone-1', name: 'Centre', maxDistanceMeters: 5_000, deliveryFeeAriary: 5_000 }],
      weightTiers: [], timeSlots: [], morningSurchargeAriary: 600, afternoonSurchargeAriary: 800,
      keywordRules: [{ id: 'keyword-1', keyword: 'Ivandry', deliveryFeeAriary: 3500 }],
    }
    vi.mocked(getClientPricingPolicies).mockResolvedValue([base, {
      ...base, id: 2, requestKind: 'EXPRESS', clientSpecific: false,
      morningSurchargeAriary: 1000, afternoonSurchargeAriary: 0, keywordRules: [],
    }])

    render(<PricingTransparencyPanel accessToken="client-token" />)

    const normal = (await screen.findByRole('heading', { name: 'Livraison normale' })).closest('article')!
    const express = screen.getByRole('heading', { name: 'Livraison express' }).closest('article')!
    expect(within(normal).getByRole('columnheader', { name: 'Mot-clé' })).toBeInTheDocument()
    expect(within(normal).getByRole('cell', { name: 'Ivandry' })).toBeInTheDocument()
    expect(within(normal).getByRole('cell', { name: /3\s500 Ar/ })).toBeInTheDocument()
    expect(within(normal).getByText('+ 600 Ar')).toBeInTheDocument()
    expect(within(normal).getByText('+ 800 Ar')).toBeInTheDocument()
    expect(within(express).getByText('+ 1 000 Ar')).toBeInTheDocument()
    expect(within(express).getByText('+ 0 Ar')).toBeInTheDocument()
    expect(within(express).queryByRole('table')).not.toBeInTheDocument()
    expect(within(normal).getByText(/remplace le tarif de zone/)).toBeInTheDocument()
    expect(within(normal).getByText(/Aucun supplément de période lorsqu’une heure exacte/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Modifier/ })).not.toBeInTheDocument()
  })
})
