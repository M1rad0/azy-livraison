import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { OptimizationComparisonResult, OptimizationResult } from '../api/optimization'
import type { MultiClientDeliveryPlan } from '../api/plans'
import { OptimizationComparisonResults } from './OptimizationComparisonResults'

vi.mock('react-leaflet', () => {
  const map = { fitBounds: vi.fn() }
  return {
    MapContainer: ({ children }: { children: ReactNode }) => <div data-testid="comparison-map">{children}</div>,
    TileLayer: () => null,
    CircleMarker: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    GeoJSON: () => <div data-testid="comparison-route" />,
    Popup: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    Tooltip: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    useMap: () => map,
  }
})

const plan: MultiClientDeliveryPlan = {
  schemaVersion: '2.1',
  pattern: 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY',
  planId: 'plan-comparaison',
  name: 'Journée test',
  departureAt: '2026-08-22T06:00:00+03:00',
  timezone: 'Indian/Antananarivo',
  settings: { returnToDepot: false, routingPreset: 'MAIN_ROADS' },
  redistributionHub: { label: 'Siège Ankorondrano', latitude: -18.879, longitude: 47.521 },
  couriers: [{
    id: 'livreur-1',
    name: 'Soa',
    vehicleType: 'MOTO',
    maxWeightKg: 30,
    averageSpeedKmh: 25,
    departureAt: '2026-08-22T06:00:00+03:00',
    departureLocation: { label: 'Alasora', latitude: -18.955, longitude: 47.567 },
  }],
  clients: [{
    id: 'client-1',
    name: 'Boutique A',
    deliveryGroups: [{
      id: 'retrait-1',
      name: 'Retrait Ampasampito',
      pickup: { label: 'Ampasampito', latitude: -18.895, longitude: 47.545 },
      packages: [{
        id: 'colis-1',
        label: 'Colis Analakely',
        weightKg: 2,
        destination: { label: 'Analakely', latitude: -18.908, longitude: 47.521 },
      }],
    }],
  }],
}

function result(stops: OptimizationResult['routes'][number]['stops']): OptimizationResult {
  return {
    schemaVersion: '2.0',
    sourcePlanId: plan.planId,
    generatedAt: '2026-08-22T08:00:00+03:00',
    status: 'SUCCESS',
    solverStatus: 'ROUTING_SUCCESS',
    computationTimeMillis: 120,
    dataContext: { routingProvider: 'OSRM', routingPreset: 'MAIN_ROADS', optimizer: 'OR_TOOLS', optimizerVersion: '9.15.6755' },
    summary: {
      packageCount: 1,
      assignedPackageCount: 1,
      unassignedPackageCount: 0,
      courierCount: 1,
      usedCourierCount: 1,
      totalAssignedWeightKg: 2,
      totalDistanceMeters: 4_500,
      totalDurationSeconds: 2_400,
    },
    routes: [{
      courierId: 'livreur-1',
      courierName: 'Soa',
      vehicleType: 'MOTO',
      loadedWeightKg: 2,
      maxWeightKg: 30,
      remainingCapacityKg: 28,
      capacityUtilizationPercent: 6.67,
      departureLocation: plan.couriers[0].departureLocation,
      departureAt: '2026-08-22T06:00:00+03:00',
      completedAt: '2026-08-22T07:10:00+03:00',
      distanceMeters: 4_500,
      durationSeconds: 4_200,
      stops,
    }],
    unassignedPackages: [],
    warnings: [],
  }
}

const pickupStop: OptimizationResult['routes'][number]['stops'][number] = {
  order: 1,
  type: 'PICKUP',
  clientId: 'client-1',
  clientName: 'Boutique A',
  deliveryGroupId: 'retrait-1',
  deliveryGroupName: 'Retrait Ampasampito',
  packageId: 'colis-1',
  packageLabel: 'Colis Analakely',
  weightKg: 2,
  destination: plan.clients[0].deliveryGroups[0].pickup,
  estimatedArrivalAt: '2026-08-22T06:20:00+03:00',
  distanceFromPreviousMeters: 2_000,
  durationFromPreviousSeconds: 600,
  cumulativeDistanceMeters: 2_000,
  cumulativeDurationSeconds: 1_200,
}

const deliveryStop: OptimizationResult['routes'][number]['stops'][number] = {
  ...pickupStop,
  order: 2,
  type: 'DELIVERY',
  destination: plan.clients[0].deliveryGroups[0].packages[0].destination,
  estimatedArrivalAt: '2026-08-22T07:10:00+03:00',
  distanceFromPreviousMeters: 2_500,
  cumulativeDistanceMeters: 4_500,
  cumulativeDurationSeconds: 4_200,
}

const comparison: OptimizationComparisonResult = {
  schemaVersion: '1.0',
  sourcePlanId: plan.planId,
  generatedAt: '2026-08-22T08:00:00+03:00',
  recommendedStrategy: 'VIA_HUB',
  recommendationCriterion: 'LOWER_DISTANCE',
  recommendationReason: 'Le passage au siège réduit la distance totale tout en livrant tous les colis.',
  operationalPolicy: {
    pickupServiceSeconds: 600,
    hubTransferSeconds: 900,
    deliveryServiceSeconds: 300,
    deliveryWindowEarlyToleranceSeconds: 0,
    deliveryWindowLateToleranceSeconds: 1_800,
    windowAppliesTo: 'ARRIVAL',
  },
  direct: {
    strategy: 'DIRECT',
    result: result([pickupStop, deliveryStop]),
    metrics: {
      packageCount: 1,
      assignedPackageCount: 1,
      onTimePackageCount: 1,
      latePackageCount: 0,
      totalLatenessSeconds: 0,
      maxLatenessSeconds: 0,
      totalDistanceMeters: 5_200,
      completedAt: '2026-08-22T07:15:00+03:00',
      usedCourierCount: 1,
      drivingSeconds: 2_000,
      waitingSeconds: 0,
      serviceSeconds: 900,
    },
  },
  viaHub: {
    strategy: 'VIA_HUB',
    result: result([pickupStop, deliveryStop]),
    metrics: {
      packageCount: 1,
      assignedPackageCount: 1,
      onTimePackageCount: 1,
      latePackageCount: 0,
      totalLatenessSeconds: 0,
      maxLatenessSeconds: 0,
      totalDistanceMeters: 4_500,
      completedAt: '2026-08-22T07:10:00+03:00',
      usedCourierCount: 1,
      drivingSeconds: 1_800,
      waitingSeconds: 300,
      serviceSeconds: 1_800,
    },
  },
  delta: {
    assignedPackageCount: 0,
    onTimePackageCount: 0,
    totalLatenessSeconds: 0,
    totalDistanceMeters: -700,
    completionSeconds: -300,
    usedCourierCount: 0,
  },
  hubExecution: {
    hub: plan.redistributionHub,
    meetingAt: '2026-08-22T06:35:00+03:00',
    redistributionCompletedAt: '2026-08-22T06:50:00+03:00',
    collectionDistanceMeters: 3_000,
    distributorApproachDistanceMeters: 0,
    distributionDistanceMeters: 1_500,
    hubWaitingSeconds: 300,
    packageTransfers: [{
      deliveryPackage: {
        packageId: 'colis-1',
        packageLabel: 'Colis Analakely',
        weightKg: 2,
        clientId: 'client-1',
        clientName: 'Boutique A',
        deliveryGroupId: 'retrait-1',
        deliveryGroupName: 'Retrait Ampasampito',
      },
      collectorCourierId: 'livreur-1',
      collectorCourierName: 'Soa',
      distributorCourierId: 'livreur-2',
      distributorCourierName: 'Tiana',
      transferred: true,
      arrivedAtHub: '2026-08-22T06:35:00+03:00',
      departedHub: '2026-08-22T06:50:00+03:00',
    }],
    manifests: [{
      courierId: 'livreur-1',
      courierName: 'Soa',
      roles: ['COLLECTION'],
      arrivedAt: '2026-08-22T06:35:00+03:00',
      waitingSeconds: 900,
      loadAtArrivalKg: 2,
      loadAtDepartureKg: 0,
      collectedPackages: [],
      keptPackages: [],
      transferredOutPackages: [],
      receivedPackages: [],
      deliveredPackages: [],
    }],
  },
}

describe('OptimizationComparisonResults', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('shows both options, defaults to the recommendation and explains transfers', () => {
    render(<OptimizationComparisonResults comparison={comparison} plan={plan} accessToken="token" onBack={vi.fn()} />)

    expect(screen.getByRole('heading', { name: 'Direct' })).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { name: 'Via le siège' }).length).toBeGreaterThan(0)
    expect(screen.getByRole('tab', { name: 'Via le siège' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('Qui remet, garde ou prend chaque colis')).toBeInTheDocument()
    expect(screen.getByText('remet')).toBeInTheDocument()
    expect(screen.getByText('prend')).toBeInTheDocument()
    expect(screen.getByText('Tiana')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('tab', { name: 'Direct' }))
    expect(screen.getByRole('tab', { name: 'Direct' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByText('Qui remet, garde ou prend chaque colis')).not.toBeInTheDocument()
  })

  it('exports the complete result and comparison CSV', () => {
    const createObjectUrl = vi.fn().mockReturnValue('blob:comparison')
    const revokeObjectUrl = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL: createObjectUrl, revokeObjectURL: revokeObjectUrl })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    render(<OptimizationComparisonResults comparison={comparison} plan={plan} accessToken="token" onBack={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: 'Comparatif CSV' }))
    fireEvent.click(screen.getByRole('button', { name: 'Résultat complet' }))

    expect(createObjectUrl).toHaveBeenCalledTimes(2)
    expect(createObjectUrl).toHaveBeenCalledWith(expect.any(Blob))
    expect(revokeObjectUrl).toHaveBeenCalledTimes(2)
  })

  it('places the hub between pickup and delivery on the selected hub map', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        provider: 'OSRM',
        preset: 'MAIN_ROADS',
        pointCount: 4,
        distanceMeters: 4_500,
        durationSeconds: 1_800,
        geometry: { type: 'LineString', coordinates: [] },
        waypoints: [],
      }),
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<OptimizationComparisonResults comparison={comparison} plan={plan} accessToken="token" onBack={vi.fn()} />)

    fireEvent.click(screen.getByRole('tab', { name: 'Détails' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/routing/route',
      expect.objectContaining({ method: 'POST' }),
    ))
    const routeCall = fetchMock.mock.calls.find(([input]) => input === '/api/routing/route')
    expect(JSON.parse(String(routeCall?.[1]?.body))).toEqual({ points: [
      { id: 'start-livreur-1', latitude: -18.955, longitude: 47.567 },
      { id: 'livreur-1-PICKUP-colis-1-1', latitude: -18.895, longitude: 47.545 },
      { id: 'hub-livreur-1', latitude: -18.879, longitude: 47.521 },
      { id: 'livreur-1-DELIVERY-colis-1-2', latitude: -18.908, longitude: 47.521 },
    ] })
  })
})
