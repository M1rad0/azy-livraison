import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { confirmCashBriefingTransfer, getCashBriefingPlanning, listCashBriefings, prepareCashBriefing, releaseCashBriefing, revalidateCashBriefing, saveManualCashBriefing, type CashBriefingBatch, type CashBriefingPlanning } from '../../api/cashBriefing'
import { CashBriefingPanel } from './CashBriefingPanel'
import { CashBriefingFinancePanel } from './CashBriefingFinancePanel'

vi.mock('../../api/cashBriefing', () => ({ getCashBriefingPlanning: vi.fn(), saveManualCashBriefing: vi.fn(), listCashBriefings: vi.fn(), prepareCashBriefing: vi.fn(), releaseCashBriefing: vi.fn(), revalidateCashBriefing: vi.fn(), confirmCashBriefingTransfer: vi.fn() }))
function batch(status: CashBriefingBatch['status'] = 'ACTIVE'): CashBriefingBatch {
  return { id: 'batch', planId: 'plan', serviceDate: '2026-11-15', status, ledgerRevision: 7, expiresAt: '2026-11-15T21:00:00Z', algorithm: 'exact', message: status === 'STALE' ? 'Les caisses ont changé.' : 'À confirmer dans Finance.', balances: [], transfers: [{ id: 'transfer', sellerAccountId: 'seller', sellerName: 'Boutique Soa', sellerPayoutNumber: '034 00 000 00', courierAccountId: 'courier', courierName: 'Baptiste', amountAriary: 25000, status: 'PENDING' }] }
}
function planning(current?: CashBriefingBatch): CashBriefingPlanning {
  return { planId: 'plan', serviceDate: '2026-11-15', ledgerRevision: 7, batch: current, sellers: [{ sellerAccountId: 'seller', sellerName: 'Boutique Soa', sellerPayoutNumber: '034 00 000 00', balanceAriary: 25000, plannedAriary: current ? 25000 : 0, availableAriary: 25000 }, { sellerAccountId: 'zero', sellerName: 'Vendeur sans solde', balanceAriary: 0, plannedAriary: 0, availableAriary: 0 }], couriers: [{ courierAccountId: 'courier', courierName: 'Baptiste', balanceAriary: 30000, reservedElsewhereAriary: 0, capacityAriary: 30000, plannedAriary: current ? 25000 : 0, remainingAriary: current ? 5000 : 30000 }] }
}
async function addManual(amount = '12000') {
  fireEvent.click(await screen.findByRole('button', { name: 'Ajouter un versement' }))
  const seller = screen.getByRole('combobox', { name: 'Vendeur du versement 1' })
  fireEvent.focus(seller)
  fireEvent.change(seller, { target: { value: 'Soa' } })
  fireEvent.mouseDown(screen.getByRole('option', { name: /Boutique Soa/ }))
  fireEvent.focus(screen.getByRole('combobox', { name: 'Livreur du versement 1' }))
  fireEvent.mouseDown(screen.getByRole('option', { name: /Baptiste/ }))
  fireEvent.change(screen.getByRole('spinbutton', { name: 'Montant du versement 1' }), { target: { value: amount } })
}

describe('payout planning and shared cash proposals', () => {
  beforeEach(() => {
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(planning())
    vi.mocked(listCashBriefings).mockResolvedValue([])
    vi.mocked(saveManualCashBriefing).mockResolvedValue(batch())
    vi.mocked(prepareCashBriefing).mockResolvedValue(batch())
    vi.mocked(revalidateCashBriefing).mockResolvedValue(batch())
    vi.mocked(releaseCashBriefing).mockResolvedValue(batch('RELEASED'))
    vi.mocked(confirmCashBriefingTransfer).mockResolvedValue({ ...batch('COMPLETED'), transfers: [{ ...batch().transfers[0], status: 'CONFIRMED' }] })
  })
  afterEach(() => { cleanup(); vi.clearAllMocks() })

  it('loads seller balances and only dispatch couriers without creating any proposal', async () => {
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await screen.findByText('Boutique Soa')
    expect(getCashBriefingPlanning).toHaveBeenCalledWith('plan', 'token', expect.any(AbortSignal))
    expect(screen.getByText('Vendeur sans solde')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Baptiste' })).toBeInTheDocument()
    expect(prepareCashBriefing).not.toHaveBeenCalled()
    expect(saveManualCashBriefing).not.toHaveBeenCalled()
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })

  it('explicitly generates an automatic proposal with optimistic guards', async () => {
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await screen.findByText('Boutique Soa')
    fireEvent.click(screen.getByRole('button', { name: 'Répartir automatiquement' }))
    await waitFor(() => expect(prepareCashBriefing).toHaveBeenCalledWith('plan', 'token', expect.any(String), { replaceExisting: true, expectedLedgerRevision: 7, expectedBatchId: null }))
    expect(releaseCashBriefing).not.toHaveBeenCalled()
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })

  it('creates partial manual plans without validating a real payment', async () => {
    const onBatchChange = vi.fn()
    render(<CashBriefingPanel accessToken="token" planId="plan" onBatchChange={onBatchChange} />)
    await addManual()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith({ planId: 'plan', expectedLedgerRevision: 7, expectedBatchId: null, idempotencyKey: expect.any(String), transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 12000 }] }, 'token'))
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })

  it('blocks cash and seller debt over-allocation before saving', async () => {
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual('40000')
    expect(screen.getByRole('alert')).toHaveTextContent('dépassent la caisse disponible')
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '26000' } })
    expect(screen.getByRole('alert')).toHaveTextContent('dépassent le solde disponible')
    expect(saveManualCashBriefing).not.toHaveBeenCalled()
  })

  it('requires confirmation before replacing manual edits with automatic planning', async () => {
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    fireEvent.click(screen.getByRole('button', { name: 'Répartir automatiquement' }))
    expect(prepareCashBriefing).not.toHaveBeenCalled()
    const dialog = screen.getByRole('dialog', { name: 'Répartir automatiquement ?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Générer la répartition' }))
    await waitFor(() => expect(prepareCashBriefing).toHaveBeenCalledOnce())
  })

  it('never silently discards unsaved edits on refresh', async () => {
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    fireEvent.click(screen.getByRole('button', { name: 'Actualiser les versements' }))
    expect(getCashBriefingPlanning).toHaveBeenCalledTimes(1)
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Annuler' }))
    expect(screen.getByRole('spinbutton')).toHaveValue(12000)
  })

  it('retains the same idempotency key after an uncertain manual save', async () => {
    vi.mocked(saveManualCashBriefing).mockRejectedValue(new Error('Connexion interrompue'))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await screen.findByText('Connexion interrompue')
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledTimes(2))
    expect(vi.mocked(saveManualCashBriefing).mock.calls[0][0]).toEqual(vi.mocked(saveManualCashBriefing).mock.calls[1][0])
  })

  it('revalidates stale balances and preserves manual edits', async () => {
    vi.mocked(getCashBriefingPlanning).mockResolvedValueOnce(planning(batch('STALE'))).mockResolvedValue(planning(batch()))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    const amount = await screen.findByRole('spinbutton')
    fireEvent.change(amount, { target: { value: '10000' } })
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Revalider les caisses' }))
    await waitFor(() => expect(revalidateCashBriefing).toHaveBeenCalledWith('batch', 'token'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled())
    expect(screen.getByRole('spinbutton')).toHaveValue(10000)
  })

  it('can fix an over-budget stale proposal without revalidating the invalid old amounts', async () => {
    const state = planning(batch('STALE'))
    state.ledgerRevision = 8
    state.couriers[0].capacityAriary = 5000
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    const onBlockedChange = vi.fn()
    render(<CashBriefingPanel accessToken="token" planId="plan" onBlockedChange={onBlockedChange} />)
    fireEvent.change(await screen.findByRole('spinbutton'), { target: { value: '5000' } })
    expect(onBlockedChange).toHaveBeenLastCalledWith(true)
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({ expectedLedgerRevision: 8, expectedBatchId: 'batch', transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 5000 }] }), 'token'))
    expect(revalidateCashBriefing).not.toHaveBeenCalled()
  })

  it('keeps edits and the same key if saving succeeds but refreshing the response fails', async () => {
    vi.mocked(getCashBriefingPlanning).mockResolvedValueOnce(planning()).mockRejectedValueOnce(new Error('Actualisation interrompue')).mockResolvedValue(planning(batch()))
    const onBlockedChange = vi.fn(), onDirtyChange = vi.fn()
    render(<CashBriefingPanel accessToken="token" planId="plan" onBlockedChange={onBlockedChange} onDirtyChange={onDirtyChange} />)
    await addManual()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await screen.findByText('Actualisation interrompue')
    expect(screen.getByRole('spinbutton')).toHaveValue(12000)
    expect(onBlockedChange).toHaveBeenLastCalledWith(true)
    expect(onDirtyChange).toHaveBeenLastCalledWith(true)
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledTimes(2))
    expect(vi.mocked(saveManualCashBriefing).mock.calls[0][0]).toEqual(vi.mocked(saveManualCashBriefing).mock.calls[1][0])
    await waitFor(() => expect(onBlockedChange).toHaveBeenLastCalledWith(false))
    expect(onDirtyChange).toHaveBeenLastCalledWith(false)
  })

  it('allows clearing all pending planned transfers explicitly', async () => {
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(planning(batch()))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Retirer le versement 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({ expectedBatchId: 'batch', transfers: [] }), 'token'))
  })

  it('disables mutations when balances cannot be loaded', async () => {
    vi.mocked(getCashBriefingPlanning).mockRejectedValue(new Error('Caisses indisponibles'))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Caisses indisponibles')
    expect(screen.getByRole('button', { name: 'Répartir automatiquement' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    expect(prepareCashBriefing).not.toHaveBeenCalled()
  })

  it('confirms a real transfer only in Finance after an explicit confirmation', async () => {
    vi.mocked(listCashBriefings).mockResolvedValue([batch()])
    const onChanged = vi.fn()
    render(<CashBriefingFinancePanel accessToken="token" onChanged={onChanged} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Confirmer le versement' }))
    const dialog = screen.getByRole('dialog', { name: 'Confirmer le versement réel' })
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
    fireEvent.change(within(dialog).getByLabelText('Référence MVola (facultative)'), { target: { value: 'ref-mvola-1' } })
    fireEvent.change(within(dialog).getByLabelText('Note (facultative)'), { target: { value: 'Envoyé et vérifié' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirmer le transfert réel' }))
    await waitFor(() => expect(confirmCashBriefingTransfer).toHaveBeenCalledWith('transfer', { paymentMethod: 'MVola', reference: 'ref-mvola-1', note: 'Envoyé et vérifié', expectedLedgerRevision: 7 }, null, 'token'))
    expect(onChanged).toHaveBeenCalledOnce()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('disables finance confirmation for stale cash and offers revalidation', async () => {
    vi.mocked(listCashBriefings).mockResolvedValue([batch('STALE')])
    render(<CashBriefingFinancePanel accessToken="token" />)
    expect(await screen.findByRole('button', { name: 'Confirmer le versement' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Revalider' }))
    await waitFor(() => expect(revalidateCashBriefing).toHaveBeenCalledWith('batch', 'token'))
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })
})
