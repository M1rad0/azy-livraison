import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { confirmCashBriefingCourierTransfer, confirmCashBriefingTransfer, listCashBriefingTransfers, revalidateCashBriefing, type CashBriefingBatch, type CashBriefingTransferRow } from '../../api/cashBriefing'
import { CashBriefingFinancePanel } from './CashBriefingFinancePanel'

vi.mock('../../api/cashBriefing', () => ({ listCashBriefingTransfers: vi.fn(), revalidateCashBriefing: vi.fn(), confirmCashBriefingTransfer: vi.fn(), confirmCashBriefingCourierTransfer: vi.fn() }))

const batch = (): CashBriefingBatch => ({
  id: 'batch', planId: 'plan', serviceDate: '2026-11-15', status: 'ACTIVE', ledgerRevision: 4,
  expiresAt: '2026-11-15T21:00:00Z', algorithm: 'manual', message: '', balances: [],
  transfers: [{ id: 'transfer', sellerAccountId: 'seller', sellerName: 'Boutique Soa', courierAccountId: 'courier',
    courierName: 'Baptiste', amountAriary: 25000, status: 'PENDING', sellerPayoutNumber: '032 11 111 11',
    sellerPayoutOperator: 'Orange Money', sellerPayoutAccountHolder: 'Jean Rakoto' }],
})
const row = (source = batch()): CashBriefingTransferRow => ({ batchId: source.id, serviceDate: source.serviceDate, batchStatus: source.status, ledgerRevision: source.ledgerRevision, batchMessage: source.message, transfer: source.transfers[0] })
const page = (source = batch()) => ({ items: [row(source)], totalElements: 1, page: 0, size: 20, totalPages: 1 })
const cashRow = (): CashBriefingTransferRow => ({ batchId: 'batch', serviceDate: '2026-11-15', batchStatus: 'ACTIVE', ledgerRevision: 4,
  batchMessage: '', kind: 'COURIER_TRANSFER', courierTransfer: { id: 'cash-transfer', fromCourierAccountId: 'other', fromCourierName: 'Jean',
    toCourierAccountId: 'courier', toCourierName: 'Baptiste', amountAriary: 4000, status: 'PENDING', readyToConfirm: true } })

afterEach(() => { cleanup(); vi.clearAllMocks() })

describe('financial confirmation of a briefing payout', () => {
  it('confirms a cash handover separately and only then unlocks its funded seller payout', async () => {
    const initial = batch()
    initial.transfers[0].readyToConfirm = false
    initial.transfers[0].blockedReason = 'Confirmez les fonds à recevoir de Jean.'
    vi.mocked(listCashBriefingTransfers).mockResolvedValue({ ...page(), items: [cashRow(), row(initial)], totalElements: 2 })
    vi.mocked(confirmCashBriefingCourierTransfer).mockImplementation(async () => {
      const funded = batch()
      funded.ledgerRevision = 5
      funded.transfers[0].readyToConfirm = true
      const confirmed = cashRow()
      if (confirmed.kind !== 'COURIER_TRANSFER') throw new Error('Courier row required')
      confirmed.courierTransfer.status = 'CONFIRMED'
      confirmed.ledgerRevision = 5
      vi.mocked(listCashBriefingTransfers).mockResolvedValue({ ...page(funded), items: [confirmed, row(funded)], totalElements: 2 })
      return funded
    })
    vi.mocked(confirmCashBriefingTransfer).mockResolvedValue({ ...batch(), status: 'COMPLETED' })
    const onChanged = vi.fn()
    render(<CashBriefingFinancePanel accessToken="token" onChanged={onChanged} />)
    expect(await screen.findByText('Confirmez les fonds à recevoir de Jean.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le versement' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le transfert entre livreurs' }))
    const dialog = screen.getByRole('dialog', { name: 'Confirmer le transfert entre livreurs' })
    expect(within(dialog).getByText(/de Jean à Baptiste/)).toBeInTheDocument()
    fireEvent.change(within(dialog).getByLabelText('Moyen de transfert'), { target: { value: 'MVola' } })
    fireEvent.change(within(dialog).getByLabelText('Référence du transfert (facultative)'), { target: { value: 'CASH-4000' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirmer le transfert réel' }))
    await waitFor(() => expect(confirmCashBriefingCourierTransfer).toHaveBeenCalledWith('cash-transfer', {
      paymentMethod: 'MVola', reference: 'CASH-4000', note: undefined, expectedLedgerRevision: 4,
    }, null, 'token'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Confirmer le versement' })).toBeEnabled())
    expect(onChanged).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le versement' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmer le transfert réel' }))
    await waitFor(() => expect(confirmCashBriefingTransfer).toHaveBeenCalledWith('transfer', expect.objectContaining({ expectedLedgerRevision: 5 }), null, 'token'))
  })

  it('blocks a courier handover while its source is waiting for actual funds', async () => {
    const blocked = cashRow()
    if (blocked.kind !== 'COURIER_TRANSFER') throw new Error('Courier row required')
    blocked.courierTransfer.readyToConfirm = false
    blocked.courierTransfer.blockedReason = 'Jean attend un transfert de Marie.'
    vi.mocked(listCashBriefingTransfers).mockResolvedValue({ ...page(), items: [blocked] })
    render(<CashBriefingFinancePanel accessToken="token" />)
    expect(await screen.findByText('Jean attend un transfert de Marie.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirmer le transfert entre livreurs' })).toBeDisabled()
    expect(confirmCashBriefingCourierTransfer).not.toHaveBeenCalled()
  })

  it('keeps reversed handovers and seller payouts visible without offering another confirmation', async () => {
    const payout = batch()
    payout.transfers[0].status = 'VOIDED'
    const handover = cashRow()
    if (handover.kind !== 'COURIER_TRANSFER') throw new Error('Courier row required')
    handover.courierTransfer.status = 'VOIDED'
    vi.mocked(listCashBriefingTransfers).mockResolvedValue({ ...page(), items: [handover, row(payout)], totalElements: 2 })
    render(<CashBriefingFinancePanel accessToken="token" />)
    expect(await screen.findAllByText('Annulé')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Confirmer le transfert entre livreurs' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirmer le versement' })).not.toBeInTheDocument()
    expect(confirmCashBriefingCourierTransfer).not.toHaveBeenCalled()
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })

  it('shows the stored holder and number before confirming through the chosen operator', async () => {
    vi.mocked(listCashBriefingTransfers).mockResolvedValue(page())
    vi.mocked(confirmCashBriefingTransfer).mockResolvedValue({ ...batch(), status: 'COMPLETED' })
    render(<CashBriefingFinancePanel accessToken="token" />)
    expect(await screen.findByText('Titulaire : Jean Rakoto')).toBeInTheDocument()
    expect(screen.getByText('Orange Money : 032 11 111 11')).toBeInTheDocument()
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le versement' }))
    const dialog = screen.getByRole('dialog', { name: 'Confirmer le versement réel' })
    expect(within(dialog).getByText(/Titulaire : Jean Rakoto/)).toBeInTheDocument()
    fireEvent.change(within(dialog).getByLabelText('Référence Orange Money (facultative)'), { target: { value: 'OM-123' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirmer le transfert réel' }))
    await waitFor(() => expect(confirmCashBriefingTransfer).toHaveBeenCalledWith('transfer', {
      paymentMethod: 'Orange Money', reference: 'OM-123', note: undefined, expectedLedgerRevision: 4,
    }, null, 'token'))
  })

  it('does not replace an unknown historical holder with the seller name', async () => {
    const historical = batch()
    historical.transfers[0].sellerPayoutAccountHolder = null
    historical.transfers[0].status = 'CONFIRMED'
    vi.mocked(listCashBriefingTransfers).mockResolvedValue(page(historical))
    render(<CashBriefingFinancePanel accessToken="token" />)
    expect(await screen.findByText('Titulaire non renseigné')).toBeInTheDocument()
    expect(screen.queryByText('Titulaire : Boutique Soa')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirmer le versement' })).not.toBeInTheDocument()
  })
  it('paginates individual payouts and resets the page on dates, search and size changes', async () => {
    vi.mocked(listCashBriefingTransfers).mockImplementation(async (_token, filters) => ({ items: [row()], totalElements: 62, page: filters.page, size: filters.size, totalPages: Math.ceil(62 / filters.size) }))
    render(<CashBriefingFinancePanel accessToken="token" />)
    expect(await screen.findByText('1–20 sur 62')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Page suivante' }))
    expect(await screen.findByText('21–40 sur 62')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Du'), { target: { value: '2026-11-01' } })
    fireEvent.change(screen.getByLabelText('Au'), { target: { value: '2026-11-15' } })
    await waitFor(() => expect(listCashBriefingTransfers).toHaveBeenLastCalledWith('token', expect.objectContaining({ page: 0, from: '2026-11-01', to: '2026-11-15' }), expect.any(AbortSignal)))
    fireEvent.change(screen.getByRole('searchbox', { name: 'Rechercher un versement' }), { target: { value: 'Baptiste' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'Par page' }), { target: { value: '50' } })
    expect(await screen.findByText('1–50 sur 62')).toBeInTheDocument()
    expect(listCashBriefingTransfers).toHaveBeenLastCalledWith('token', expect.objectContaining({ page: 0, size: 50, q: 'Baptiste' }), expect.any(AbortSignal))
  })
  it('blocks stale proposals until their batch is explicitly revalidated', async () => {
    vi.mocked(listCashBriefingTransfers).mockResolvedValue(page({ ...batch(), status: 'STALE', message: 'Vérifiez les soldes.' }))
    vi.mocked(revalidateCashBriefing).mockImplementation(async () => {
      vi.mocked(listCashBriefingTransfers).mockResolvedValue(page())
      return batch()
    })
    render(<CashBriefingFinancePanel accessToken="token" />)
    expect(await screen.findByText('Vérifiez les soldes.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirmer le versement' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Revalider' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Confirmer le versement' })).toBeEnabled())
    expect(revalidateCashBriefing).toHaveBeenCalledWith('batch', 'token')
  })
  it('shows failures without displaying an empty success state and retries the same filters', async () => {
    vi.mocked(listCashBriefingTransfers).mockRejectedValueOnce(new Error('Service indisponible')).mockResolvedValue(page())
    render(<CashBriefingFinancePanel accessToken="token" />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Service indisponible')
    expect(screen.queryByText('Aucun versement correspondant aux filtres.')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Réessayer' }))
    expect(await screen.findByText('Titulaire : Jean Rakoto')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
