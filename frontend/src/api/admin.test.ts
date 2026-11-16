import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  cancelAdminDeliveryPackage,
  closeDeliveryClient,
  closeDeliveryDay,
  confirmExpressDelivery,
  createDispatchPlan,
  downloadCourierStatement,
  downloadGeneralDailyReport,
  downloadClientDailyReport,
  finalizeCourierCompensations,
  getClientClosures,
  getCourierCompensations,
  getDeliveryDay,
  getDeliveryPricingPolicy,
  getAdminQueue,
  listDeliveryRequests,
  recalculateExpressFuel,
  reopenDeliveryClient,
  updateExpressFuel,
  updateOperationalSettings,
  updateDeliveryPricingPolicy,
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

  it('encode les filtres de la file globale en attente', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] })
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()

    await listDeliveryRequests('admin-token', {
      from: '2026-08-24',
      to: '2026-08-30',
      client: '  Boutique Soa  ',
      pendingOnly: true,
    }, controller.signal)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/delivery-requests?from=2026-08-24&to=2026-08-30&client=Boutique+Soa&pendingOnly=true',
      expect.objectContaining({
        signal: controller.signal,
        headers: expect.objectContaining({ Authorization: 'Bearer admin-token' }),
      }),
    )
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

  it('raccorde les actions du compte rendu quotidien', async () => {
    const day = {
      serviceDate: '2026-08-22', status: 'DISPATCHED', admissionsClosed: true,
      dispatchPlanId: 'plan-1', closureRevision: 0, version: 2,
      counts: { total: 1, finalized: 1, awaitingOutcome: 0, delivered: 1, cancelled: 0, postponed: 0, returnToSender: 0, returnsCompleted: 0 },
      totals: { recoveredAriary: 25000, deliveryFeesAriary: 3000, amountToRemitAriary: 22000 },
      clients: [], packages: [],
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => day })
      .mockResolvedValueOnce({ ok: true, status: 204, json: async () => undefined })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ ...day, status: 'CLOSED', version: 3 }) })
    vi.stubGlobal('fetch', fetchMock)

    await getDeliveryDay('2026-08-22', 'admin-token')
    await cancelAdminDeliveryPackage('package-1', 4, 'admin-token')
    await closeDeliveryDay('2026-08-22', 2, 'admin-token')

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/admin/delivery-days/2026-08-22', expect.anything())
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/admin/delivery-packages/package-1/cancel', expect.objectContaining({ body: JSON.stringify({ version: 4 }) }))
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/admin/delivery-days/2026-08-22/close', expect.objectContaining({ body: JSON.stringify({ version: 2 }) }))
  })

  it('raccorde la clôture progressive et les rémunérations d’un client', async () => {
    const overview = {
      serviceDate: '2026-08-22', dayStatus: 'DISPATCHED', dayVersion: 7,
      currentClosureRevision: 2, clientCount: 2, closedClientCount: 1,
      allClientsClosed: false, clients: [],
    }
    const compensation = { serviceDate: '2026-08-22', closureRevision: 2, lines: [] }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => overview })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => overview })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => overview })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => compensation })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => compensation })
    vi.stubGlobal('fetch', fetchMock)

    await getClientClosures('2026-08-22', 'admin-token')
    await closeDeliveryClient('2026-08-22', 'client/soa', 7, 'admin-token')
    await reopenDeliveryClient('2026-08-22', 'client/soa', 3, 'Correction requise', 'admin-token')
    await getCourierCompensations('2026-08-22', 'admin-token', undefined, 'client/soa')
    await finalizeCourierCompensations('2026-08-22', 2, [], 'admin-token', 'client/soa')

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/admin/delivery-days/2026-08-22/client-closures', expect.anything())
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/admin/delivery-days/2026-08-22/clients/client%2Fsoa/close', expect.objectContaining({ body: JSON.stringify({ version: 7 }) }))
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/admin/delivery-days/2026-08-22/clients/client%2Fsoa/reopen', expect.objectContaining({ body: JSON.stringify({ version: 3, reason: 'Correction requise' }) }))
    expect(fetchMock).toHaveBeenNthCalledWith(4, '/api/admin/finance/delivery-days/2026-08-22/clients/client%2Fsoa/courier-compensations', expect.anything())
    expect(fetchMock).toHaveBeenNthCalledWith(5, '/api/admin/finance/delivery-days/2026-08-22/clients/client%2Fsoa/courier-compensations/finalize', expect.objectContaining({ body: JSON.stringify({ closureRevision: 2, overrides: [] }) }))
  })

  it('fige le choix de retour lors de la confirmation d’une express', async () => {
    const express = { request: { id: 'express-1' } }
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => express })
    vi.stubGlobal('fetch', fetchMock)

    await confirmExpressDelivery('express-1', ['courier-1', 'courier-2'], 4, false, 'admin-token')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/express-deliveries/express-1/confirm',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          courierIds: ['courier-1', 'courier-2'],
          version: 4,
          returnToPickup: false,
        }),
      }),
    )
  })

  it('raccorde le recalcul et la correction manuelle du carburant express', async () => {
    const calculation = { requestId: 'express-1', version: 7 }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => calculation })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => calculation })
    vi.stubGlobal('fetch', fetchMock)

    await recalculateExpressFuel('express-1', 6, 'admin-token')
    await updateExpressFuel('express-1', 7, [{
      courierId: 'courier-1',
      finalDistanceMeters: 12_300,
      finalFuelAmountAriary: 1_540,
      reason: 'Détour constaté',
    }], 'admin-token')

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/admin/express-deliveries/express-1/fuel/recalculate',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ version: 6 }) }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/admin/express-deliveries/express-1/fuel',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({
          version: 7,
          lines: [{
            courierId: 'courier-1',
            finalDistanceMeters: 12_300,
            finalFuelAmountAriary: 1_540,
            reason: 'Détour constaté',
          }],
        }),
      }),
    )
  })

  it('télécharge un rapport binaire avec le nom fourni par le serveur', async () => {
    const blob = new Blob(['rapport'], { type: 'application/pdf' })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Disposition': 'attachment; filename="rapport-azy.pdf"' }),
      blob: async () => blob,
    }))

    await expect(downloadGeneralDailyReport('2026-08-22', 'pdf', 'admin-token')).resolves.toEqual({
      blob,
      filename: 'rapport-azy.pdf',
    })
  })

  it.each(['png', 'zip'])('downloads client report photos as the actual %s attachment', async (extension) => {
    const filename = `soa-2026-11-16.${extension}`
    const blob = new Blob(['report'])
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      headers: new Headers({ 'Content-Disposition': `attachment; filename="${filename}"` }),
      blob: async () => blob,
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(downloadClientDailyReport('2026-11-16', 'account-1', 'image', 'admin-token'))
      .resolves.toEqual({ blob, filename })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/delivery-days/2026-11-16/reports/clients/account-1.image',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer admin-token' }) }),
    )
  })

  it('keeps a real ZIP extension for general report photos even without a disposition header', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      headers: new Headers({ 'Content-Type': 'application/zip' }),
      blob: async () => new Blob(),
    }))
    expect((await downloadGeneralDailyReport('2026-11-16', 'image', 'admin-token')).filename)
      .toBe('general-2026-11-16.zip')
  })

  it('télécharge le relevé livreur avec les filtres de période', async () => {
    const blob = new Blob(['relevé'], { type: 'application/pdf' })
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Disposition': 'attachment; filename="releve-tiana.pdf"' }),
      blob: async () => blob,
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(downloadCourierStatement(
      'courier-1',
      'pdf',
      { from: '2026-08-01', to: '2026-08-24' },
      'admin-token',
    )).resolves.toEqual({ blob, filename: 'releve-tiana.pdf' })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/finance/couriers/courier-1/statement.pdf?from=2026-08-01&to=2026-08-24',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer admin-token' }),
      }),
    )
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
      defaultCourierCommissionNumerator: 1,
      defaultCourierCommissionDenominator: 3,
      defaultFuelPricePerLiterAriary: 5_000,
      defaultExpressReturnToPickup: true,
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

  it('charge et remplace atomiquement la politique tarifaire', async () => {
    const policy = {
      version: 3,
      center: { label: 'Centre tarifaire', latitude: -18.9, longitude: 47.52 },
      zones: [{ id: 'zone-centre', name: 'Centre-ville', maxDistanceMeters: 4_000, deliveryFeeAriary: 3_000 }],
      createdAt: '2026-08-01T00:00:00Z',
      updatedAt: '2026-08-23T00:00:00Z',
    }
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => policy })
    vi.stubGlobal('fetch', fetchMock)

    await getDeliveryPricingPolicy('admin-token')
    await updateDeliveryPricingPolicy({
      version: policy.version,
      distanceMode: 'AIR_DISTANCE',
      center: policy.center,
      zones: policy.zones,
    }, 'admin-token')

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/admin/settings/pricing', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer admin-token' }),
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/admin/settings/pricing', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ version: policy.version, distanceMode: 'AIR_DISTANCE', center: policy.center, zones: policy.zones }),
    }))
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
