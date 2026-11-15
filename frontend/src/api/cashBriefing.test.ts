import { afterEach, describe, expect, it, vi } from 'vitest'
import { getCashBriefingPlanning, prepareCashBriefing, saveManualCashBriefing } from './cashBriefing'

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
})
