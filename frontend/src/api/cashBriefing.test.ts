import { afterEach, describe, expect, it, vi } from 'vitest'
import { confirmCashBriefingCourierTransfer, getCashBriefingPlanning, listCashBriefingTransfers, prepareCashBriefing, saveManualCashBriefing } from './cashBriefing'

describe('cash payout planning API', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('loads the planning snapshot with its plan and abort signal', async () => {
    const response = { planId: 'plan/id', ledgerRevision: 3, sellers: [], couriers: [], batch: null }
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => response })
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()
    expect(await getCashBriefingPlanning('plan/id', 'token', controller.signal)).toEqual(response)
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/cash-briefings/planning?planId=plan%2Fid', expect.objectContaining({ signal: controller.signal, headers: { Accept: 'application/json', Authorization: 'Bearer token' } }))
  })

  it('sends a manual replacement with explicit expected revision, batch and idempotency', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'new-batch' }) })
    vi.stubGlobal('fetch', fetchMock)
    const input = { planId: 'plan', idempotencyKey: 'stable-key', expectedLedgerRevision: 4, expectedBatchId: 'previous', transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 5000 }] }
    await saveManualCashBriefing(input, 'token')
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/cash-briefings/manual', expect.objectContaining({ method: 'POST', body: JSON.stringify(input) }))
  })
  it('paginates transfers rather than batches with dates, query, and cancellation', async () => {
    const page = { items: [], totalElements: 0, totalPages: 0, page: 1, size: 20 }
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => page }); vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()
    expect(await listCashBriefingTransfers('token', { page: 1, size: 20, from: '2026-11-01', to: '2026-11-15', q: ' Jean ' }, controller.signal)).toEqual(page)
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/admin/cash-briefings/transfers?page=1&size=20&from=2026-11-01&to=2026-11-15&q=Jean', expect.objectContaining({ signal: controller.signal, headers: expect.objectContaining({ Authorization: 'Bearer token' }) }))
  })

  it('sends the selected profile destination without replacing account details', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'new-batch' }) })
    vi.stubGlobal('fetch', fetchMock)
    const input = { planId: 'plan', idempotencyKey: 'key', expectedLedgerRevision: 4, expectedBatchId: null, transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 5000, sellerPayoutDestinationId: 'destination-id' }] }
    await saveManualCashBriefing(input, 'token')
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/admin/cash-briefings/manual', expect.objectContaining({ method: 'POST', body: JSON.stringify(input) }))
  })

  it('sends both custom number and real holder for an individual planned transfer', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'new-batch' }) })
    vi.stubGlobal('fetch', fetchMock)
    const input = { planId: 'plan', idempotencyKey: 'key', expectedLedgerRevision: 4, transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 5000, sellerPayoutOperator: 'Orange Money', sellerPayoutNumber: '034 99 999 99', sellerPayoutAccountHolder: 'Titulaire vérifié' }] }
    await saveManualCashBriefing(input, 'token')
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/admin/cash-briefings/manual', expect.objectContaining({ method: 'POST', body: JSON.stringify(input) }))
  })

  it('replaces automatic proposals atomically without a separate release', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'automatic' }) })
    vi.stubGlobal('fetch', fetchMock)
    await prepareCashBriefing('plan', 'token', 'stable-key', { replaceExisting: true, expectedLedgerRevision: 8, expectedBatchId: null })
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/admin/cash-briefings/prepare', expect.objectContaining({ method: 'POST', body: JSON.stringify({ planId: 'plan', idempotencyKey: 'stable-key', replaceExisting: true, expectedLedgerRevision: 8, expectedBatchId: null }) }))
  })

  it('surfaces version conflicts instead of accepting a stale save', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409, json: async () => ({ message: 'Les caisses ont changé.', code: 'STALE_LEDGER' }) }))
    await expect(saveManualCashBriefing({ planId: 'plan', idempotencyKey: 'key', expectedLedgerRevision: 1, expectedBatchId: null, transfers: [] }, 'token')).rejects.toMatchObject({ message: 'Les caisses ont changé.', status: 409, code: 'STALE_LEDGER' })
  })

  it('saves internal handovers and complete seller payouts in the same replacement', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'new-batch', workflowVersion: 2 }) })
    vi.stubGlobal('fetch', fetchMock)
    const input = { planId: 'plan', idempotencyKey: 'key', expectedLedgerRevision: 4, transfers: [{ sellerAccountId: 'seller', courierAccountId: 'receiver', amountAriary: 25000 }], courierTransfers: [{ fromCourierAccountId: 'donor', toCourierAccountId: 'receiver', amountAriary: 10000 }] }
    await saveManualCashBriefing(input, 'token')
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/admin/cash-briefings/manual', expect.objectContaining({ method: 'POST', body: JSON.stringify(input) }))
  })

  it('confirms a courier handover through its own endpoint with optional proof', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'batch' }) })
    vi.stubGlobal('fetch', fetchMock)
    const proof = new File(['receipt'], 'receipt.png', { type: 'image/png' })
    const input = { paymentMethod: 'Espèces', expectedLedgerRevision: 4, note: 'Remis en main propre' }
    await confirmCashBriefingCourierTransfer('transfer/id', input, proof, 'token')
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/api/admin/cash-briefings/courier-transfers/transfer%2Fid/confirm', expect.objectContaining({ method: 'POST', body: expect.any(FormData) }))
    const body = fetchMock.mock.calls[0][1].body as FormData
    expect(body.get('proof')).toBe(proof)
    expect((body.get('transfer') as Blob).type).toBe('application/json')
  })
})
