import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DispatchPlan } from '../../api/admin'
import { collectionMatrixUsage, DispatchResult } from './AdminDispatchPanel'
import { AdminPortal } from './AdminPortal'

function jsonResponse(payload: unknown) {
  return { ok: true, status: 200, json: async () => payload }
}

describe('AdminPortal', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('présente la file métier puis le dispatch via siège', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string) => {
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
    expect(fetchMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/admin\/queue\?date=/), expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer admin-token' }),
    }))

    fireEvent.click(screen.getByRole('button', { name: /Dispatch/ }))
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Préparer le dispatch' })).toBeInTheDocument())
    expect(screen.getByText('Une seule stratégie · via siège')).toBeInTheDocument()
    expect(screen.queryByText(/stratégie directe/i)).not.toBeInTheDocument()
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

    expect(collectionMatrixUsage(queue, new Set(['package-1', 'package-2']), 98)).toEqual({
      pickupCount: 2,
      pointCount: 101,
    })
  })
})
