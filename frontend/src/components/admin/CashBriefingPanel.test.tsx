import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { confirmCashBriefingTransfer, listCashBriefings, prepareCashBriefing, releaseCashBriefing, revalidateCashBriefing, type CashBriefingBatch } from '../../api/cashBriefing'
import { CashBriefingPanel } from './CashBriefingPanel'
import { CashBriefingFinancePanel } from './CashBriefingFinancePanel'

vi.mock('../../api/cashBriefing', () => ({ listCashBriefings: vi.fn(), prepareCashBriefing: vi.fn(), releaseCashBriefing: vi.fn(), revalidateCashBriefing: vi.fn(), confirmCashBriefingTransfer: vi.fn() }))
function batch(status: CashBriefingBatch['status'] = 'ACTIVE'): CashBriefingBatch {
  return { id: 'batch', planId: 'plan', serviceDate: '2026-11-15', status, ledgerRevision: 7, expiresAt: '2026-11-15T21:00:00Z', algorithm: 'exact', message: status === 'STALE' ? 'Les caisses ont changé.' : 'À confirmer dans Finance.', balances: [], transfers: [{ id: 'transfer', sellerAccountId: 'seller', sellerName: 'Boutique Soa', sellerPayoutNumber: '034 00 000 00', courierAccountId: 'courier', courierName: 'Baptiste', amountAriary: 25000, status: 'PENDING' }] }
}
describe('shared briefing cash proposals', () => {
  beforeEach(() => {
    vi.mocked(listCashBriefings).mockResolvedValue([])
    vi.mocked(prepareCashBriefing).mockResolvedValue(batch())
    vi.mocked(revalidateCashBriefing).mockResolvedValue(batch())
    vi.mocked(releaseCashBriefing).mockResolvedValue(batch('RELEASED'))
    vi.mocked(confirmCashBriefingTransfer).mockResolvedValue({ ...batch('COMPLETED'), transfers: [{ ...batch().transfers[0], status: 'CONFIRMED' }] })
  })
  afterEach(() => { cleanup(); vi.clearAllMocks() })

  it('reads the shared proposal but reserves money only after an explicit prepare click', async () => {
    const onBatchChange = vi.fn()
    render(<CashBriefingPanel accessToken="token" planId="plan" onBatchChange={onBatchChange} />)
    await waitFor(() => expect(listCashBriefings).toHaveBeenCalledWith('token', 'plan', expect.any(AbortSignal)))
    expect(prepareCashBriefing).not.toHaveBeenCalled()
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Préparer les versements' }))
    await screen.findByText('Boutique Soa', { exact: false })
    expect(prepareCashBriefing).toHaveBeenCalledWith('plan', 'token')
    expect(onBatchChange).toHaveBeenLastCalledWith(batch())
    expect(screen.queryByRole('button', { name: 'Confirmer le versement' })).not.toBeInTheDocument()
  })

  it('revalidates stale proposals without treating any transfer as confirmed', async () => {
    vi.mocked(listCashBriefings).mockResolvedValue([batch('STALE')])
    const onBatchChange = vi.fn()
    render(<CashBriefingPanel accessToken="token" planId="plan" onBatchChange={onBatchChange} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Revalider les caisses' }))
    await waitFor(() => expect(revalidateCashBriefing).toHaveBeenCalledWith('batch', 'token'))
    await waitFor(() => expect(onBatchChange).toHaveBeenLastCalledWith(batch()))
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })

  it('releases only the reservation and clears the generated cash instructions', async () => {
    vi.mocked(listCashBriefings).mockResolvedValue([batch()])
    const onBatchChange = vi.fn()
    render(<CashBriefingPanel accessToken="token" planId="plan" onBatchChange={onBatchChange} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Libérer la proposition' }))
    await waitFor(() => expect(releaseCashBriefing).toHaveBeenCalledWith('batch', 'token'))
    await waitFor(() => expect(onBatchChange).toHaveBeenLastCalledWith(undefined))
    expect(await screen.findByRole('button', { name: 'Préparer les versements' })).toBeEnabled()
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })

  it('shows finance loading errors locally and does not create a fallback proposal', async () => {
    vi.mocked(listCashBriefings).mockRejectedValue(new Error('Caisses indisponibles'))
    render(<CashBriefingPanel accessToken="token" planId="plan" onBatchChange={vi.fn()} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Caisses indisponibles')
    expect(prepareCashBriefing).not.toHaveBeenCalled()
  })

  it('confirms a real transfer only in Finance after an explicit dialog confirmation', async () => {
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

  it('disables confirmation for stale cash and offers a revalidation instead', async () => {
    vi.mocked(listCashBriefings).mockResolvedValue([batch('STALE')])
    render(<CashBriefingFinancePanel accessToken="token" />)
    expect(await screen.findByRole('button', { name: 'Confirmer le versement' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Revalider' }))
    await waitFor(() => expect(revalidateCashBriefing).toHaveBeenCalledWith('batch', 'token'))
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })
})
