import { afterEach, describe, expect, it, vi } from 'vitest'
import { compareOptimizations, type OptimizationComparisonResult } from './optimization'
import type { MultiClientDeliveryPlan } from './plans'

describe('compareOptimizations', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('posts the plan to the comparison endpoint', async () => {
    const plan = {
      schemaVersion: '2.1',
      pattern: 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY',
      planId: 'plan-1',
      name: 'Plan',
      departureAt: '2026-08-22T06:00:00+03:00',
      timezone: 'Indian/Antananarivo',
      settings: { returnToDepot: false, routingPreset: 'MAIN_ROADS' },
      redistributionHub: { latitude: -18.879, longitude: 47.521 },
      couriers: [],
      clients: [],
    } satisfies MultiClientDeliveryPlan
    const payload = {
      direct: {},
      viaHub: {},
    } as OptimizationComparisonResult
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => payload })
    vi.stubGlobal('fetch', fetchMock)

    expect(await compareOptimizations(plan, 'access-token')).toBe(payload)
    expect(fetchMock).toHaveBeenCalledWith('/api/plans/compare', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ Authorization: 'Bearer access-token' }),
      body: JSON.stringify(plan),
    }))
  })
})
