import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { confirmCashBriefingTransfer, listCashBriefings, type CashBriefingBatch } from '../../api/cashBriefing'
import { CashBriefingFinancePanel } from './CashBriefingFinancePanel'

vi.mock('../../api/cashBriefing', () => ({ listCashBriefings: vi.fn(), revalidateCashBriefing: vi.fn(), confirmCashBriefingTransfer: vi.fn() }))

const batch = (): CashBriefingBatch => ({
  id: 'batch', planId: 'plan', serviceDate: '2026-11-15', status: 'ACTIVE', ledgerRevision: 4,
  expiresAt: '2026-11-15T21:00:00Z', algorithm: 'manual', message: '', balances: [],
  transfers: [{ id: 'transfer', sellerAccountId: 'seller', sellerName: 'Boutique Soa', courierAccountId: 'courier',
    courierName: 'Baptiste', amountAriary: 25000, status: 'PENDING', sellerPayoutNumber: '032 11 111 11',
    sellerPayoutOperator: 'Orange Money', sellerPayoutAccountHolder: 'Jean Rakoto' }],
})

afterEach(() => { cleanup(); vi.clearAllMocks() })

describe('financial confirmation of a briefing payout', () => {
  it('shows the stored holder and number before confirming through the chosen operator', async () => {
    vi.mocked(listCashBriefings).mockResolvedValue([batch()])
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
    vi.mocked(listCashBriefings).mockResolvedValue([historical])
    render(<CashBriefingFinancePanel accessToken="token" />)
    expect(await screen.findByText('Titulaire non renseigné')).toBeInTheDocument()
    expect(screen.queryByText('Titulaire : Boutique Soa')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirmer le versement' })).not.toBeInTheDocument()
  })
})
