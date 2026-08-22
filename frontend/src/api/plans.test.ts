import { describe, expect, it } from 'vitest'
import {
  isDeliveryPlan,
  materializeVehicleDefaults,
  migrateMultiClientDeliveryPlan,
} from './plans'

function legacyDraft() {
  return {
    schemaVersion: '2.0',
    pattern: 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY',
    planId: 'multi-legacy',
    name: 'Ancien plan',
    departureAt: '2026-08-05T08:00:00+03:00',
    timezone: 'Indian/Antananarivo',
    settings: { returnToDepot: false, routingPreset: 'MAIN_ROADS' },
    clients: [],
    couriers: [{
      id: 'livreur-001',
      name: 'Soa',
      vehicleType: 'MOTO',
    }],
  }
}

describe('multi-client plan migration', () => {
  it('accepts a 2.0 draft and migrates it explicitly to the hub-aware 2.1 schema', () => {
    const draft: unknown = legacyDraft()
    expect(isDeliveryPlan(draft)).toBe(true)
    if (!isDeliveryPlan(draft) || draft.pattern !== 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY') {
      throw new Error('Legacy fixture should be a supported multi-client plan')
    }

    const migrated = materializeVehicleDefaults(migrateMultiClientDeliveryPlan(draft))

    expect(migrated).toMatchObject({
      schemaVersion: '2.1',
      redistributionHub: { destinationQuery: '' },
      couriers: [{
        id: 'livreur-001',
        maxWeightKg: 30,
        averageSpeedKmh: 25,
        departureAt: '2026-08-05T08:00:00+03:00',
        departureLocation: { destinationQuery: '' },
      }],
    })
  })

  it('requires the hub and courier departures in native 2.1 files', () => {
    const missingHub = { ...legacyDraft(), schemaVersion: '2.1' }
    expect(isDeliveryPlan(missingHub)).toBe(false)

    const missingCourierDeparture = {
      ...missingHub,
      redistributionHub: { destinationQuery: '' },
    }
    expect(isDeliveryPlan(missingCourierDeparture)).toBe(false)
  })
})
