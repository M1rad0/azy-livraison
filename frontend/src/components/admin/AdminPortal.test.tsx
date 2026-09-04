import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DispatchPlan } from '../../api/admin'
import { collectionMatrixUsage, DispatchResult } from './AdminDispatchPanel'
import { AdminPortal } from './AdminPortal'
import { antananarivoToday } from './adminHelpers'

function jsonResponse(payload: unknown) {
  return { ok: true, status: 200, json: async () => payload }
}

const TEST_TODAY = antananarivoToday()

const pricingPolicy = {
  id: 1,
  requestKind: 'NORMAL',
  clientSpecific: false,
  version: 1,
  center: { label: 'Centre tarifaire', destinationQuery: 'Analakely', latitude: -18.9089, longitude: 47.5212 },
  defaultWeightKg: 1,
  zones: [
    { id: 'zone-centre', name: 'Centre-ville', maxDistanceMeters: 4_000, deliveryFeeAriary: 3_000 },
    { id: 'zone-peripherie', name: 'Périphérie', maxDistanceMeters: 8_000, deliveryFeeAriary: 4_000 },
  ],
  weightTiers: [],
  timeSlots: [],
}

function requestFixture({
  requestId = 'request-1',
  packageId = 'package-1',
  requestedDate = TEST_TODAY,
  recipientName = 'Rado',
} = {}) {
  return {
    id: requestId,
    ownerAccountId: 'account-1',
    ownerDisplayName: 'Boutique Rado',
    ownerPhone: '034 11 222 33',
    requestedDate,
    pickup: { label: 'Magasin', query: 'Analakely', latitude: -18.91, longitude: 47.52 },
    capacity: { limit: 30, current: 0, requested: 1, projected: 1, warning: false, accepted: false },
    status: 'PENDING',
    version: 0,
    createdAt: '2026-08-21T12:00:00Z',
    updatedAt: '2026-08-21T12:00:00Z',
    packages: [{
      id: packageId,
      sequenceNumber: 1,
      recipientName,
      faritra: true,
      destination: { query: 'Ankorondrano', latitude: -18.87, longitude: 47.52 },
      contact: '034 00 000 00',
      amountToCollectAriary: 25000,
      mobileMoneyPaid: false,
      effectiveWeightKg: 1,
      weightSource: 'DEFAULT_SETTING',
      status: 'PENDING',
      version: 0,
      createdAt: '2026-08-21T12:00:00Z',
      updatedAt: '2026-08-21T12:00:00Z',
    }],
  }
}

function portalFetchMock(
  globalRequests = [requestFixture()],
  dayRequests: ReturnType<typeof requestFixture>[] = [],
  dispatchHistory: unknown[] = [],
) {
  return vi.fn().mockImplementation((input: string) => {
    if (input.startsWith('/api/admin/delivery-requests')) return Promise.resolve(jsonResponse(globalRequests))
    if (input.startsWith('/api/admin/queue?')) {
      const date = new URL(input, 'http://localhost').searchParams.get('date') ?? TEST_TODAY
      const pendingPackageCount = dayRequests.reduce((count, request) => count + request.packages.length, 0)
      return Promise.resolve(jsonResponse({
        date,
        stats: {
          dailyLimit: 30,
          pendingRequestCount: dayRequests.length,
          partiallyDispatchedRequestCount: 0,
          pendingPackageCount,
          remainingCapacity: Math.max(0, 30 - pendingPackageCount),
          capacityExceeded: false,
        },
        requests: dayRequests,
      }))
    }
    if (input.startsWith('/api/admin/dispatch-plans')) return Promise.resolve(jsonResponse(dispatchHistory))
    if (input === '/api/admin/pricing-policies') return Promise.resolve(jsonResponse([
      pricingPolicy,
      { ...pricingPolicy, id: 2, requestKind: 'EXPRESS' },
    ]))
    if (input === '/api/admin/clients') return Promise.resolve(jsonResponse([]))
    if (input === '/api/admin/settings/pricing') return Promise.resolve(jsonResponse(pricingPolicy))
    if (input === '/api/admin/couriers') return Promise.resolve(jsonResponse([]))
    if (input === '/api/admin/vehicle-presets') return Promise.resolve(jsonResponse([]))
    if (input === '/api/admin/settings') return Promise.resolve(jsonResponse({
      dailyPackageLimit: 30,
      defaultHub: { label: 'Siège', destinationQuery: 'Behoririka', latitude: -18.9, longitude: 47.52 },
      defaultPackageWeightKg: 1,
      pickupServiceMinutes: 10,
      hubServiceMinutes: 15,
      deliveryServiceMinutes: 5,
      earlyToleranceMinutes: 0,
      lateToleranceMinutes: 30,
      optimizationTimeLimitSeconds: 20,
      routingPreset: 'MAIN_ROADS',
      version: 0,
      createdAt: '2026-08-01T00:00:00Z',
      updatedAt: '2026-08-01T00:00:00Z',
    }))
    return Promise.reject(new Error(`URL inattendue: ${input}`))
  })
}

describe('AdminPortal', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('présente la file métier puis le dispatch via siège', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string) => {
      if (input.startsWith('/api/admin/delivery-requests')) {
        return Promise.resolve(jsonResponse([requestFixture({ requestedDate: '2026-08-22' })]))
      }
      if (input.startsWith('/api/admin/queue?')) {
        return Promise.resolve(jsonResponse({
          date: '2026-08-22',
          stats: {
            dailyLimit: 30,
            pendingRequestCount: 1,
            partiallyDispatchedRequestCount: 0,
            pendingPackageCount: 1,
            remainingCapacity: 29,
            capacityExceeded: false,
          },
          requests: [{
            id: 'request-1',
            ownerAccountId: 'account-1',
            ownerDisplayName: 'Boutique Rado',
            ownerPhone: '034 11 222 33',
            requestedDate: '2026-08-22',
            pickup: { label: 'Magasin', query: 'Analakely', latitude: -18.91, longitude: 47.52 },
            capacity: { limit: 30, current: 0, requested: 1, projected: 1, warning: false, accepted: false },
            status: 'PENDING',
            version: 0,
            createdAt: '2026-08-21T12:00:00Z',
            updatedAt: '2026-08-21T12:00:00Z',
            packages: [{
              id: 'package-1',
              sequenceNumber: 1,
              recipientName: 'Rado',
              faritra: true,
              destination: { query: 'Ankorondrano', latitude: -18.87, longitude: 47.52 },
              contact: '034 00 000 00',
              amountToCollectAriary: 25000,
              mobileMoneyPaid: false,
              effectiveWeightKg: 1,
              weightSource: 'DEFAULT_SETTING',
              status: 'PENDING',
              version: 0,
              createdAt: '2026-08-21T12:00:00Z',
              updatedAt: '2026-08-21T12:00:00Z',
            }],
          }],
        }))
      }
      if (input.startsWith('/api/admin/dispatch-plans')) return Promise.resolve(jsonResponse([]))
      if (input === '/api/admin/couriers') return Promise.resolve(jsonResponse([]))
      if (input === '/api/admin/vehicle-presets') return Promise.resolve(jsonResponse([]))
      if (input === '/api/admin/settings') {
        return Promise.resolve(jsonResponse({
          dailyPackageLimit: 30,
          defaultHub: { label: 'Siège', destinationQuery: 'Behoririka', latitude: -18.9, longitude: 47.52 },
          defaultPackageWeightKg: 1,
          pickupServiceMinutes: 10,
          hubServiceMinutes: 15,
          deliveryServiceMinutes: 5,
          earlyToleranceMinutes: 0,
          lateToleranceMinutes: 30,
          optimizationTimeLimitSeconds: 20,
          routingPreset: 'MAIN_ROADS',
          version: 0,
          createdAt: '2026-08-01T00:00:00Z',
          updatedAt: '2026-08-01T00:00:00Z',
        }))
      }
      return Promise.reject(new Error(`URL inattendue: ${input}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminPortal accessToken="admin-token" />)

    expect(await screen.findByText('Boutique Rado')).toBeInTheDocument()
    expect(screen.getByText('034 11 222 33')).toBeInTheDocument()
    expect(screen.getByText(/25.000 Ar/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/delivery-requests?pendingOnly=true', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer admin-token' }),
    }))
    expect(fetchMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/admin\/queue\?date=/), expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer admin-token' }),
    }))

    fireEvent.click(screen.getByRole('button', { name: /Dispatch/ }))
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Préparer le dispatch' })).toBeInTheDocument())
    expect(screen.getByText('Une seule stratégie · via siège')).toBeInTheDocument()
    expect(screen.queryByText(/stratégie directe/i)).not.toBeInTheDocument()
  })

  it('filtre la file globale par période et par client sans modifier la date du dispatch', async () => {
    const fetchMock = portalFetchMock()
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminPortal accessToken="admin-token" />)

    expect(await screen.findByText('Boutique Rado')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Une période' }))
    fireEvent.change(screen.getByLabelText('Du'), { target: { value: '2026-08-24' } })
    fireEvent.change(screen.getByLabelText('Au'), { target: { value: '2026-08-30' } })
    fireEvent.change(screen.getByRole('searchbox', { name: 'Rechercher un client' }), {
      target: { value: '  Boutique Soa  ' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Appliquer' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/delivery-requests?from=2026-08-24&to=2026-08-30&client=Boutique+Soa&pendingOnly=true',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer admin-token' }) }),
    ))
    const datedQueueCalls = fetchMock.mock.calls.filter(([input]) => String(input).startsWith('/api/admin/queue?date='))
    expect(datedQueueCalls).toHaveLength(1)
    expect(datedQueueCalls[0]?.[0]).toBe(`/api/admin/queue?date=${TEST_TODAY}`)
  })

  it('choisit une date d’activité dans Dispatch indépendamment de la vue globale', async () => {
    const fetchMock = portalFetchMock()
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminPortal accessToken="admin-token" />)

    expect(await screen.findByText('Boutique Rado')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Dispatch/ }))
    expect(await screen.findByRole('heading', { name: 'Préparer le dispatch' })).toBeInTheDocument()

    const activityDate = screen.getByLabelText('Date d’activité') as HTMLInputElement
    expect(activityDate).toHaveValue(TEST_TODAY)
    fireEvent.change(activityDate, { target: { value: '2099-12-30' } })

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith('/api/admin/queue?date=2099-12-30', expect.anything())
      expect(fetchMock).toHaveBeenCalledWith('/api/admin/dispatch-plans?date=2099-12-30', expect.anything())
    })
    expect(activityDate).toHaveValue('2099-12-30')
    expect(fetchMock.mock.calls.filter(([input]) => String(input).startsWith('/api/admin/delivery-requests')))
      .toHaveLength(1)
  })

  it('prépare automatiquement toute la file admissible sans sélection manuelle', async () => {
    const todayRequest = requestFixture({
      requestId: 'request-today',
      packageId: 'package-today',
      recipientName: 'Colis du jour',
    })
    const fetchMock = portalFetchMock([todayRequest], [todayRequest])
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminPortal accessToken="admin-token" />)

    await screen.findByText('Colis du jour')
    expect(screen.queryByRole('checkbox', { name: /Sélectionner le colis/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Préparer la journée' }))

    expect(await screen.findByText(/1 colis inclus selon leur ordre d’arrivée/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Sélection/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Dispatcher automatiquement 1 colis' })).toBeEnabled()
  })

  it('permet de relancer la journée après un dispatch échoué', async () => {
    const todayRequest = requestFixture({
      requestId: 'request-retry',
      packageId: 'package-retry',
      recipientName: 'Colis à relancer',
    })
    const fetchMock = portalFetchMock([todayRequest], [todayRequest], [{
      id: 'plan-failed',
      serviceDate: TEST_TODAY,
      status: 'FAILED',
      strategy: 'VIA_HUB',
      hub: { label: 'Siège', latitude: -18.9, longitude: 47.52 },
      selectedPackageCount: 1,
      assignedPackageCount: 0,
      unassignedPackageCount: 0,
      selectedCourierCount: 1,
      usedCourierCount: 0,
      failureMessage: 'Calcul interrompu',
      createdAt: '2026-08-23T05:00:00Z',
    }])
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminPortal accessToken="admin-token" />)

    await screen.findByText('Colis à relancer')
    fireEvent.click(screen.getByRole('button', { name: 'Préparer la journée' }))

    expect(await screen.findByRole('button', { name: 'Dispatcher automatiquement 1 colis' })).toBeEnabled()
  })

  it('permet de modifier les rayons et prix dans un onglet dédié', async () => {
    const fetchMock = portalFetchMock()
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminPortal accessToken="admin-token" />)
    await screen.findByText('Boutique Rado')
    fireEvent.click(screen.getByRole('button', { name: /Tarification/ }))

    expect(await screen.findByRole('heading', { name: 'Tarification' })).toBeInTheDocument()
    expect(screen.getByDisplayValue('Centre-ville')).toBeInTheDocument()
    fireEvent.change(screen.getAllByDisplayValue('3000')[0], { target: { value: '3500' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cette grille' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/admin/pricing-policies', expect.objectContaining({
      method: 'PUT',
      body: expect.stringContaining('"deliveryFeeAriary":3500'),
    })))
  })

  it('restitue le planning détaillé des collectes avant le siège', () => {
    const plan = {
      plan: {
        id: 'plan-collecte',
        serviceDate: '2026-08-22',
        status: 'READY',
        strategy: 'VIA_HUB',
        hub: { label: 'Siège', latitude: -18.9, longitude: 47.52 },
        selectedPackageCount: 2,
        assignedPackageCount: 2,
        unassignedPackageCount: 0,
        selectedCourierCount: 1,
        usedCourierCount: 1,
        meetingAt: '2026-08-22T08:00:00+03:00',
        redistributionCompletedAt: '2026-08-22T08:15:00+03:00',
        completedAt: '2026-08-22T09:00:00+03:00',
        createdAt: '2026-08-22T05:00:00Z',
      },
      result: {
        routes: [{
          courierId: 'courier-1',
          courierName: 'Soa',
          departureLocation: { label: 'Alasora', destinationQuery: 'Alasora', latitude: -18.95, longitude: 47.57 },
          departureAt: '2026-08-22T06:00:00+03:00',
          stops: [
            {
              order: 1,
              type: 'PICKUP',
              clientName: 'Compte Boutique',
              deliveryGroupId: 'request-1',
              deliveryGroupName: 'Boutique Analakely',
              packageId: 'package-1',
              packageLabel: 'Colis A',
              destination: { label: 'Boutique Analakely', destinationQuery: 'Analakely', latitude: -18.91, longitude: 47.52 },
              estimatedArrivalAt: '2026-08-22T07:10:00+03:00',
            },
            {
              order: 2,
              type: 'PICKUP',
              clientName: 'Compte Boutique',
              deliveryGroupId: 'request-1',
              deliveryGroupName: 'Boutique Analakely',
              packageId: 'package-2',
              packageLabel: 'Colis B',
              destination: { label: 'Boutique Analakely', destinationQuery: 'Analakely', latitude: -18.91, longitude: 47.52 },
              estimatedArrivalAt: '2026-08-22T07:10:00+03:00',
            },
          ],
        }],
      },
      hubExecution: {
        manifests: [{ courierId: 'courier-1', courierName: 'Soa', arrivedAt: '2026-08-22T08:00:00+03:00' }],
        packageTransfers: [],
      },
      packages: [],
    } as unknown as DispatchPlan

    render(<DispatchResult plan={plan} couriers={[]} />)

    expect(screen.getByText('Planning des collectes avant le siège')).toBeInTheDocument()
    expect(screen.getByText('Boutique Analakely')).toBeInTheDocument()
    expect(screen.getByText(/Analakely · Compte Boutique/)).toBeInTheDocument()
    expect(screen.getByText('Colis A · Colis B')).toBeInTheDocument()
    expect(screen.getByText(/Départ 06:00 · Alasora/)).toBeInTheDocument()
    expect(screen.getByText('Siège 08:00')).toBeInTheDocument()
  })

  it('compte aussi les retraits et les livreurs dans la matrice de collecte', () => {
    const queue = {
      requests: [
        { id: 'request-1', packages: [{ id: 'package-1', status: 'PENDING' }] },
        { id: 'request-2', packages: [{ id: 'package-2', status: 'PENDING' }] },
      ],
    } as unknown as import('../../api/admin').AdminQueue

    expect(collectionMatrixUsage(queue, 98)).toEqual({
      pickupCount: 2,
      pointCount: 101,
    })
  })
})
