import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createDispatchPlan,
  getAdminQueue,
  updateOperationalSettings,
  type AdminQueue,
  type DispatchPlan,
  type OperationalSettings,
} from './admin'

describe('API administrateur', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('charge la file d’une date avec le jeton d’accès', async () => {
    const queue = {
      date: '2026-08-22',
      stats: {
        dailyLimit: 30,
        pendingRequestCount: 1,
        partiallyDispatchedRequestCount: 0,
        pendingPackageCount: 2,
        remainingCapacity: 28,
        capacityExceeded: false,
      },
      requests: [],
    } satisfies AdminQueue
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => queue })
    vi.stubGlobal('fetch', fetchMock)

    await expect(getAdminQueue('2026-08-22', 'admin-token')).resolves.toBe(queue)
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/queue?date=2026-08-22', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer admin-token' }),
    }))
  })

  it('envoie un dispatch via siège avec une clé d’idempotence', async () => {
    const response = {
      plan: {
        id: 'plan-1',
        serviceDate: '2026-08-22',
        status: 'READY',
        strategy: 'VIA_HUB',
        hub: { latitude: -18.91, longitude: 47.52 },
        selectedPackageCount: 2,
        assignedPackageCount: 2,
        selectedCourierCount: 1,
        usedCourierCount: 1,
        createdAt: '2026-08-22T05:00:00Z',
      },
      packages: [],
    } satisfies DispatchPlan
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => response })
    vi.stubGlobal('fetch', fetchMock)

    const input = {
      serviceDate: '2026-08-22',
      courierIds: ['courier-1'],
      packageIds: ['package-1', 'package-2'],
    }
    await expect(createDispatchPlan(input, 'dispatch-key', 'admin-token')).resolves.toBe(response)
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/dispatch-plans', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({
        Authorization: 'Bearer admin-token',
        'Idempotency-Key': 'dispatch-key',
        'Content-Type': 'application/json',
      }),
      body: JSON.stringify(input),
    }))
  })

  it('n’envoie pas les dates de lecture lors de la mise à jour des paramètres', async () => {
    const settings = {
      dailyPackageLimit: 30,
      defaultHub: { label: 'Siège', latitude: -18.91, longitude: 47.52 },
      defaultPackageWeightKg: 1,
      pickupServiceMinutes: 10,
      hubServiceMinutes: 15,
      deliveryServiceMinutes: 5,
      earlyToleranceMinutes: 0,
      lateToleranceMinutes: 30,
      optimizationTimeLimitSeconds: 20,
      routingPreset: 'MAIN_ROADS',
      version: 2,
      createdAt: '2026-08-01T00:00:00Z',
      updatedAt: '2026-08-22T00:00:00Z',
    } satisfies OperationalSettings
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => settings })
    vi.stubGlobal('fetch', fetchMock)

    await updateOperationalSettings(settings, 'admin-token')
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(init.body as string)).toEqual(expect.objectContaining({
      dailyPackageLimit: 30,
      version: 2,
    }))
    expect(JSON.parse(init.body as string)).not.toHaveProperty('createdAt')
    expect(JSON.parse(init.body as string)).not.toHaveProperty('updatedAt')
  })

  it('remonte le message métier renvoyé par le backend', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ code: 'DISPATCH_CONFLICT', message: 'Ces colis sont déjà réservés.' }),
    }))

    await expect(getAdminQueue('2026-08-22', 'admin-token')).rejects.toMatchObject({
      message: 'Ces colis sont déjà réservés.',
      status: 409,
      code: 'DISPATCH_CONFLICT',
    })
  })
})
