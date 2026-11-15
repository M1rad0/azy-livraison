import { afterEach, describe, expect, it, vi } from 'vitest'
import { adjustCashAccount, createCashTransfer, downloadCashMovementProof, getCashLedger, voidCashMovement } from './cashLedger'

const input = { idempotencyKey: 'attempt-1', fromKey: 'COURIER:c1', toKey: 'SELLER:s1', amountAriary: 25000, paymentMethod: 'MVOLA', expectedRevision: 4 }
describe('API des caisses', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })
  it('charge le nouveau registre avec authentification et annulation', async () => {
    const ledger = { revision: 4, accounts: [], obligations: [], movements: [] }
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ledger })
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()
    expect(await getCashLedger('token', controller.signal)).toEqual(ledger)
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/cash-ledger', expect.objectContaining({ signal: controller.signal, headers: expect.objectContaining({ Authorization: 'Bearer token' }) }))
  })
  it('envoie un transfert JSON avec révision et clé de tentative', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true }); vi.stubGlobal('fetch', fetchMock)
    await createCashTransfer(input, null, 'token')
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/cash-ledger/transfers', expect.objectContaining({ method: 'POST', body: JSON.stringify(input), headers: expect.objectContaining({ 'Content-Type': 'application/json' }) }))
  })
  it('joint le justificatif sans imposer un Content-Type au multipart', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true }); vi.stubGlobal('fetch', fetchMock)
    const proof = new File(['preuve'], 'preuve.pdf', { type: 'application/pdf' })
    await createCashTransfer(input, proof, 'token')
    const request = fetchMock.mock.calls[0][1] as RequestInit
    expect(request.body).toBeInstanceOf(FormData)
    const body = request.body as FormData
    expect(body.get('proof')).toBe(proof)
    expect((body.get('transfer') as Blob).type).toBe('application/json')
    expect(request.headers).not.toHaveProperty('Content-Type')
  })
  it('envoie les corrections et annulations motivées avec la révision courante', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true }); vi.stubGlobal('fetch', fetchMock)
    const adjustment = { idempotencyKey: 'zero-1', accountKey: 'COURIER:c1', deltaAriary: -5000, reason: 'Remise en caisse', expectedRevision: 4 }
    await adjustCashAccount(adjustment, 'token')
    await voidCashMovement('id/avec espace', 'Erreur de saisie', 5, 'token')
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/admin/cash-ledger/adjustments', expect.objectContaining({ body: JSON.stringify(adjustment) }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/admin/cash-ledger/movements/id%2Favec%20espace/void', expect.objectContaining({ body: JSON.stringify({ reason: 'Erreur de saisie', expectedRevision: 5 }) }))
  })
  it('remonte une erreur métier de concurrence sans la masquer', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409, json: async () => ({ code: 'STALE_REVISION', message: 'Les caisses ont changé. Actualisez.' }) }))
    await expect(createCashTransfer(input, null, 'token')).rejects.toMatchObject({ status: 409, code: 'STALE_REVISION', message: 'Les caisses ont changé. Actualisez.' })
  })
  it('télécharge un justificatif avec le nom UTF-8 fourni par le serveur', async () => {
    const blob = new Blob(['preuve'])
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, headers: new Headers({ 'Content-Disposition': "attachment; filename*=UTF-8''justificatif%20%C3%A0%20garder.pdf" }), blob: async () => blob }))
    expect(await downloadCashMovementProof('move-1', 'token')).toEqual({ blob, filename: 'justificatif à garder.pdf' })
  })
})
