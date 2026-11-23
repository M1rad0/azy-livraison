import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { confirmCashBriefingCourierTransfer, confirmCashBriefingTransfer, getCashBriefingPlanning, listCashBriefingTransfers, prepareCashBriefing, releaseCashBriefing, revalidateCashBriefing, saveManualCashBriefing, type CashBriefingBatch, type CashBriefingPlanning } from '../../api/cashBriefing'
import { CashBriefingPanel } from './CashBriefingPanel'
import { CashBriefingFinancePanel } from './CashBriefingFinancePanel'

vi.mock('../../api/cashBriefing', () => ({ getCashBriefingPlanning: vi.fn(), saveManualCashBriefing: vi.fn(), listCashBriefingTransfers: vi.fn(), prepareCashBriefing: vi.fn(), releaseCashBriefing: vi.fn(), revalidateCashBriefing: vi.fn(), confirmCashBriefingTransfer: vi.fn(), confirmCashBriefingCourierTransfer: vi.fn() }))
const destination = { id: 'destination', operator: 'MVola', accountHolder: 'Soa Rakoto', number: '034 00 000 00', priority: 1, active: true }
const alternative = { id: 'alternative', operator: 'Orange Money', accountHolder: 'Soa Rabe', number: '032 11 111 11', priority: 2, active: true }
function batch(status: CashBriefingBatch['status'] = 'ACTIVE'): CashBriefingBatch {
  return { id: 'batch', planId: 'plan', serviceDate: '2026-11-15', status, ledgerRevision: 7, expiresAt: '2026-11-15T21:00:00Z', algorithm: 'exact', message: status === 'STALE' ? 'Les caisses ont changé.' : 'À confirmer dans Finance.', balances: [], transfers: [{ id: 'transfer', sellerAccountId: 'seller', sellerName: 'Boutique Soa', sellerPayoutNumber: destination.number, sellerPayoutDestinationId: destination.id, sellerPayoutAccountHolder: destination.accountHolder, sellerPayoutOperator: destination.operator, courierAccountId: 'courier', courierName: 'Baptiste', amountAriary: 25000, status: 'PENDING' }] }
}
function financePage(status: CashBriefingBatch['status'] = 'ACTIVE') {
  const value = batch(status)
  return { items: [{ batchId: value.id, serviceDate: value.serviceDate, batchStatus: value.status, ledgerRevision: value.ledgerRevision, batchMessage: value.message, transfer: value.transfers[0] }], totalElements: 1, page: 0, size: 20, totalPages: 1 }
}
function planning(current?: CashBriefingBatch): CashBriefingPlanning {
  return { planId: 'plan', serviceDate: '2026-11-15', ledgerRevision: 7, batch: current, sellers: [{ sellerAccountId: 'seller', sellerName: 'Boutique Soa', sellerPayoutNumber: destination.number, payoutDestinations: [destination], balanceAriary: 25000, plannedAriary: current ? 25000 : 0, availableAriary: 25000 }, { sellerAccountId: 'zero', sellerName: 'Vendeur sans solde', balanceAriary: 0, plannedAriary: 0, availableAriary: 0 }], couriers: [{ courierAccountId: 'courier', courierName: 'Baptiste', balanceAriary: 30000, reservedElsewhereAriary: 0, capacityAriary: 30000, plannedAriary: current ? 25000 : 0, remainingAriary: current ? 5000 : 30000 }] }
}
async function addManual() {
  fireEvent.click(await screen.findByRole('button', { name: 'Ajouter un versement' }))
  const seller = screen.getByRole('combobox', { name: 'Vendeur du versement 1' })
  fireEvent.focus(seller)
  fireEvent.change(seller, { target: { value: 'Soa' } })
  fireEvent.mouseDown(screen.getByRole('option', { name: /Boutique Soa/ }))
  fireEvent.focus(screen.getByRole('combobox', { name: 'Livreur du versement 1' }))
  fireEvent.mouseDown(screen.getByRole('option', { name: /Baptiste/ }))
  expect(screen.getByRole('spinbutton', { name: 'Montant du versement 1' })).toHaveAttribute('readonly')
}
function markDirty() {
  fireEvent.focus(screen.getByRole('combobox', { name: 'Livreur du versement 1' }))
  fireEvent.mouseDown(screen.getByRole('option', { name: /Baptiste/ }))
}
async function addCourierTransfer(from = 'Baptiste', to = 'Jean', amount = '15000', index = 1) {
  fireEvent.click(await screen.findByRole('button', { name: 'Ajouter un transfert' }))
  fireEvent.focus(screen.getByRole('combobox', { name: `Donneur du transfert ${index}` }))
  fireEvent.mouseDown(screen.getByRole('option', { name: new RegExp(from) }))
  fireEvent.focus(screen.getByRole('combobox', { name: `Receveur du transfert ${index}` }))
  fireEvent.mouseDown(screen.getByRole('option', { name: new RegExp(to) }))
  fireEvent.change(screen.getByRole('spinbutton', { name: `Montant du transfert ${index}` }), { target: { value: amount } })
}
function pooledPlanning(current?: CashBriefingBatch) {
  const state = planning(current)
  state.couriers[0].capacityAriary = 15000
  state.couriers[0].balanceAriary = 15000
  state.couriers.push({ ...state.couriers[0], courierAccountId: 'jean', courierName: 'Jean', capacityAriary: 10000, balanceAriary: 10000, plannedAriary: 0, remainingAriary: 10000 })
  return state
}

describe('payout planning and shared cash proposals', () => {
  beforeEach(() => {
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(planning())
    vi.mocked(listCashBriefingTransfers).mockResolvedValue({ items: [], totalElements: 0, page: 0, size: 20, totalPages: 0 })
    vi.mocked(saveManualCashBriefing).mockResolvedValue(batch())
    vi.mocked(prepareCashBriefing).mockResolvedValue(batch())
    vi.mocked(revalidateCashBriefing).mockResolvedValue(batch())
    vi.mocked(releaseCashBriefing).mockResolvedValue(batch('RELEASED'))
    vi.mocked(confirmCashBriefingTransfer).mockResolvedValue({ ...batch('COMPLETED'), transfers: [{ ...batch().transfers[0], status: 'CONFIRMED' }] })
    vi.mocked(confirmCashBriefingCourierTransfer).mockResolvedValue(batch())
  })
  afterEach(() => { cleanup(); vi.clearAllMocks() })

  it('loads seller balances and only dispatch couriers without creating any proposal', async () => {
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await screen.findByText('Boutique Soa')
    expect(getCashBriefingPlanning).toHaveBeenCalledWith('plan', 'token', expect.any(AbortSignal))
    expect(screen.queryByText('Vendeur sans solde')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Baptiste' })).toBeInTheDocument()
    expect(prepareCashBriefing).not.toHaveBeenCalled()
    expect(saveManualCashBriefing).not.toHaveBeenCalled()
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })

  it('hides sellers without available debt from both the balance table and manual selection', async () => {
    const state = planning()
    state.sellers.push(
      { sellerAccountId: 'negative', sellerName: 'Vendeur débiteur', balanceAriary: -5000, plannedAriary: 0, availableAriary: -5000 },
      { sellerAccountId: 'reserved', sellerName: 'Vendeur réservé ailleurs', balanceAriary: 8000, plannedAriary: 0, availableAriary: 0 },
    )
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await screen.findByText('Boutique Soa')
    expect(screen.queryByText('Vendeur sans solde')).not.toBeInTheDocument()
    expect(screen.queryByText('Vendeur débiteur')).not.toBeInTheDocument()
    expect(screen.queryByText('Vendeur réservé ailleurs')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un versement' }))
    fireEvent.focus(screen.getByRole('combobox', { name: 'Vendeur du versement 1' }))
    expect(screen.getAllByRole('option')).toHaveLength(1)
    expect(screen.getByRole('option', { name: /Boutique Soa/ })).toBeInTheDocument()
  })

  it('keeps fully planned sellers visible and preserves stale rows for correction', async () => {
    const state = planning(batch('STALE'))
    state.sellers[0].balanceAriary = 0
    state.sellers[0].availableAriary = 0
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await screen.findByRole('spinbutton')
    expect(screen.getByRole('table')).toHaveTextContent('Boutique Soa')
    expect(screen.getByRole('combobox', { name: 'Vendeur du versement 1' })).toHaveValue('Boutique Soa')
    fireEvent.click(screen.getByRole('button', { name: 'Retirer le versement 1' }))
    expect(screen.getByText('Aucun versement à prévoir.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
  })

  it('distinguishes no payable balance from a search with no match', async () => {
    const state = planning()
    state.sellers = [state.sellers[1]]
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await screen.findByText('Aucun versement à prévoir.')
    expect(screen.getByRole('button', { name: 'Ajouter un versement' })).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: 'Rechercher un vendeur' }), { target: { value: 'inconnu' } })
    expect(screen.getByText('Aucun vendeur ne correspond à cette recherche.')).toBeInTheDocument()
  })

  it('explicitly generates an automatic proposal with optimistic guards', async () => {
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await screen.findByText('Boutique Soa')
    fireEvent.click(screen.getByRole('button', { name: 'Répartir automatiquement' }))
    await waitFor(() => expect(prepareCashBriefing).toHaveBeenCalledWith('plan', 'token', expect.any(String), { replaceExisting: true, expectedLedgerRevision: 7, expectedBatchId: null }))
    expect(releaseCashBriefing).not.toHaveBeenCalled()
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })

  it('creates whole seller payouts without validating a real payment', async () => {
    const onBatchChange = vi.fn()
    render(<CashBriefingPanel accessToken="token" planId="plan" onBatchChange={onBatchChange} />)
    await addManual()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith({ planId: 'plan', expectedLedgerRevision: 7, expectedBatchId: null, idempotencyKey: expect.any(String), courierTransfers: [], transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 25000, sellerPayoutDestinationId: destination.id }] }, 'token'))
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })

  it('pools two couriers’ funds and assigns the seller one full payout', async () => {
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(pooledPlanning())
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    await addCourierTransfer('Jean', 'Baptiste', '10000')
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
    const baptiste = screen.getByRole('heading', { name: 'Baptiste' }).closest('article')!
    expect(baptiste).toHaveTextContent('À recevoir10 000 Ar')
    expect(baptiste).toHaveTextContent('Aux vendeurs25 000 Ar')
    expect(baptiste).toHaveTextContent('Restant0 Ar')
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      courierTransfers: [{ fromCourierAccountId: 'jean', toCourierAccountId: 'courier', amountAriary: 10000 }],
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 25000, sellerPayoutDestinationId: destination.id }],
    }), 'token'))
    expect(confirmCashBriefingCourierTransfer).not.toHaveBeenCalled()
  })

  it('allows an initially empty courier to receive funds and perform the entire seller payout', async () => {
    const state = pooledPlanning()
    state.couriers[0].capacityAriary = 25000
    state.couriers[0].balanceAriary = 25000
    state.couriers[1].capacityAriary = 0
    state.couriers[1].balanceAriary = 0
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    fireEvent.focus(screen.getByRole('combobox', { name: 'Livreur du versement 1' }))
    fireEvent.change(screen.getByRole('combobox', { name: 'Livreur du versement 1' }), { target: { value: 'Jean' } })
    fireEvent.mouseDown(screen.getByRole('option', { name: /Jean/ }))
    await addCourierTransfer('Baptiste', 'Jean', '25000')
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
  })

  it('prevents duplicate seller selection and makes the complete amount read-only', async () => {
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    expect(screen.getByRole('spinbutton', { name: 'Montant du versement 1' })).toHaveValue(25000)
    expect(screen.getByRole('spinbutton', { name: 'Montant du versement 1' })).toHaveAttribute('readonly')
    expect(screen.getByRole('button', { name: 'Ajouter un versement' })).toBeDisabled()
  })

  it('uses incoming funds to clear a courier’s negative balance before any seller payout', async () => {
    const state = pooledPlanning()
    state.couriers[0].balanceAriary = 20000
    state.couriers[0].capacityAriary = 20000
    state.couriers[1].balanceAriary = -5000
    state.couriers[1].capacityAriary = 0
    state.sellers[0].balanceAriary = 10000
    state.sellers[0].availableAriary = 10000
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    const payoutCourier = screen.getByRole('combobox', { name: 'Livreur du versement 1' })
    fireEvent.change(payoutCourier, { target: { value: 'Jean' } })
    fireEvent.mouseDown(screen.getByRole('option', { name: /Jean/ }))
    await addCourierTransfer('Baptiste', 'Jean', '10000')
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    const jean = screen.getByRole('heading', { name: 'Jean' }).closest('article')!
    expect(jean).toHaveTextContent('Restant-5 000 Ar')
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Montant du transfert 1' }), { target: { value: '15000' } })
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
    expect(jean).toHaveTextContent('Restant0 Ar')
  })

  it('blocks cash handovers that cannot be funded, without inventing available cash', async () => {
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(pooledPlanning())
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addCourierTransfer('Baptiste', 'Jean', '16000')
    expect(screen.getByRole('alert')).toHaveTextContent('ne permet pas de réaliser les transferts')
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Montant du transfert 1' }), { target: { value: '15000' } })
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
  })

  it('rejects circular funding even when both couriers have cash', async () => {
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(pooledPlanning())
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addCourierTransfer('Baptiste', 'Jean', '5000')
    await addCourierTransfer('Jean', 'Baptiste', '1000', 2)
    expect(screen.getByRole('alert')).toHaveTextContent('ne doivent pas former de boucle')
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
  })

  it('keeps another plan’s reserved funds unavailable for handovers', async () => {
    const state = pooledPlanning()
    state.couriers[0].reservedElsewhereAriary = 6000
    state.couriers[0].capacityAriary = 9000
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addCourierTransfer('Baptiste', 'Jean', '10000')
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Montant du transfert 1' }), { target: { value: '9000' } })
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
  })

  it('keeps legacy partial payouts readable and requires the whole balance when resaving', async () => {
    const current = batch()
    current.transfers[0].amountAriary = 12000
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(planning(current))
    const onBlockedChange = vi.fn()
    render(<CashBriefingPanel accessToken="token" planId="plan" onBlockedChange={onBlockedChange} />)
    expect(await screen.findByRole('spinbutton', { name: 'Montant du versement 1' })).toHaveValue(12000)
    expect(onBlockedChange).toHaveBeenLastCalledWith(false)
    markDirty()
    expect(screen.getByRole('alert')).toHaveTextContent('solde entier du vendeur')
    fireEvent.click(screen.getByRole('button', { name: 'Utiliser le solde entier' }))
    expect(screen.getByRole('spinbutton', { name: 'Montant du versement 1' })).toHaveValue(25000)
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
  })

  it('restores pending cash handovers while keeping confirmed handovers read-only', async () => {
    const current = batch()
    const transfer = { id: 'internal', fromCourierAccountId: 'jean', fromCourierName: 'Jean', toCourierAccountId: 'courier', toCourierName: 'Baptiste', amountAriary: 10000, status: 'PENDING' as const, readyToConfirm: true }
    current.courierTransfers = [transfer, { ...transfer, id: 'confirmed-internal', amountAriary: 5000, status: 'CONFIRMED' }]
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(pooledPlanning(current))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    expect(await screen.findByRole('spinbutton', { name: 'Montant du transfert 1' })).toHaveValue(10000)
    expect(screen.queryByRole('spinbutton', { name: 'Montant du transfert 2' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Transferts déjà confirmés dans Finance' }).parentElement).toHaveTextContent('Jean → Baptiste5 000 Ar')
    fireEvent.click(screen.getByRole('button', { name: 'Retirer le transfert 1' }))
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent('dépassent la caisse disponible')
  })

  it('keeps reversed handovers and seller payouts read-only in the saved history', async () => {
    const current = batch()
    current.transfers[0].status = 'VOIDED'
    current.courierTransfers = [{ id: 'reversed', fromCourierAccountId: 'jean', fromCourierName: 'Jean', toCourierAccountId: 'courier', toCourierName: 'Baptiste', amountAriary: 10000, status: 'VOIDED', readyToConfirm: false }]
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(planning(current))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    expect(await screen.findAllByText('Annulé dans Finance')).toHaveLength(2)
    expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retirer le versement 1' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retirer le transfert 1' })).not.toBeInTheDocument()
  })

  it('defaults to read-only profile details and saves a custom number and holder without modifying the seller account', async () => {
    const initial = planning()
    const saved = batch()
    saved.transfers[0].sellerPayoutNumber = '034 99 999 99'
    saved.transfers[0].sellerPayoutNumberOverride = true
    saved.transfers[0].sellerPayoutDestinationId = null
    saved.transfers[0].sellerPayoutAccountHolder = 'Titulaire spécifique'
    saved.transfers[0].sellerPayoutOperator = 'Airtel Money'
    vi.mocked(getCashBriefingPlanning).mockResolvedValueOnce(initial).mockResolvedValue(planning(saved))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    const number = screen.getByRole('textbox', { name: 'Numéro du versement 1' })
    expect(number).toHaveValue('0340000000')
    expect(number).toHaveAttribute('readonly')
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue(destination.accountHolder)
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveAttribute('readonly')
    fireEvent.click(screen.getByRole('button', { name: 'Numéro suivant du versement 1' }))
    expect(screen.getByText('Personnalisé')).toBeInTheDocument()
    const operator = screen.getByRole('combobox', { name: 'Opérateur du versement 1' })
    expect(operator).toHaveValue('MVola')
    expect(within(operator).getAllByRole('option').map((option) => option.textContent)).toEqual(['Choisir un opérateur', 'MVola', 'Orange Money', 'Airtel Money'])
    fireEvent.change(operator, { target: { value: 'Airtel Money' } })
    expect(number).not.toHaveAttribute('readonly')
    fireEvent.change(number, { target: { value: '034 99 999 99' } })
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: 'Titulaire du versement 1' }), { target: { value: 'Titulaire spécifique' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 25000, sellerPayoutOperator: 'Airtel Money', sellerPayoutNumber: '0349999999', sellerPayoutAccountHolder: 'Titulaire spécifique' }],
    }), 'token'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled())
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue('0349999999')
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue('Titulaire spécifique')
    expect(initial.sellers[0].sellerPayoutNumber).toBe('034 00 000 00')
  })

  it('can cycle back to a read-only account number and retains custom drafts for another visit', async () => {
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    expect(screen.getByRole('button', { name: 'Numéro précédent du versement 1' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Numéro suivant du versement 1' }))
    expect(screen.getByRole('button', { name: 'Numéro suivant du versement 1' })).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: 'Numéro du versement 1' }), { target: { value: '034 99 999 99' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Titulaire du versement 1' }), { target: { value: 'Nom personnalisé' } })
    fireEvent.click(screen.getByRole('button', { name: 'Numéro précédent du versement 1' }))
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue('0340000000')
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveAttribute('readonly')
    fireEvent.click(screen.getByRole('button', { name: 'Numéro suivant du versement 1' }))
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue('0349999999')
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue('Nom personnalisé')
    fireEvent.click(screen.getByRole('button', { name: 'Numéro précédent du versement 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 25000, sellerPayoutDestinationId: destination.id }],
    }), 'token'))
  })

  it('allows a specific number when the seller account has no number but blocks a blank value', async () => {
    const state = planning()
    state.sellers[0].sellerPayoutNumber = null
    state.sellers[0].payoutDestinations = []
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    expect(screen.getByText('Personnalisé')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Numéro précédent du versement 1' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Numéro suivant du versement 1' })).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: 'Numéro du versement 1' }), { target: { value: '034 99 999 99' } })
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: 'Titulaire du versement 1' }), { target: { value: 'Titulaire personnalisé' } })
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 25000, sellerPayoutOperator: 'MVola', sellerPayoutNumber: '0349999999', sellerPayoutAccountHolder: 'Titulaire personnalisé' }],
    }), 'token'))
  })

  it('retains a saved override while editing the payout courier', async () => {
    const current = batch()
    current.transfers[0].sellerPayoutNumber = '034 99 999 99'
    current.transfers[0].sellerPayoutNumberOverride = true
    current.transfers[0].sellerPayoutDestinationId = null
    current.transfers[0].sellerPayoutAccountHolder = 'Titulaire enregistré'
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(planning(current))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    expect(await screen.findByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue('0349999999')
    markDirty()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 25000, sellerPayoutOperator: 'MVola', sellerPayoutNumber: '0349999999', sellerPayoutAccountHolder: 'Titulaire enregistré' }],
    }), 'token'))
  })

  it('requires a supported operator before resaving an older custom pending transfer', async () => {
    const current = batch('STALE')
    current.transfers[0].sellerPayoutNumberOverride = true
    current.transfers[0].sellerPayoutDestinationId = null
    current.transfers[0].sellerPayoutNumber = '034 99 999 99'
    current.transfers[0].sellerPayoutAccountHolder = 'Ancien titulaire'
    current.transfers[0].sellerPayoutOperator = 'Banque'
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(planning(current))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    const operator = await screen.findByRole('combobox', { name: 'Opérateur du versement 1' })
    expect(operator).toHaveValue('')
    markDirty()
    expect(screen.getByText(/Choisissez MVola, Orange Money ou Airtel Money/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    fireEvent.change(operator, { target: { value: 'Orange Money' } })
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 25000, sellerPayoutOperator: 'Orange Money', sellerPayoutNumber: '0349999999', sellerPayoutAccountHolder: 'Ancien titulaire' }],
    }), 'token'))
  })

  it('cycles active profile destinations in priority order before the final custom slot', async () => {
    const state = planning()
    state.sellers[0].payoutDestinations = [alternative, { ...destination, id: 'inactive', priority: 0, active: false }, destination]
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue(destination.accountHolder)
    fireEvent.click(screen.getByRole('button', { name: 'Numéro suivant du versement 1' }))
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue(alternative.number.replaceAll(' ', ''))
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue(alternative.accountHolder)
    expect(screen.getByText('Orange Money')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveAttribute('readonly')
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 25000, sellerPayoutDestinationId: alternative.id }],
    }), 'token'))
  })

  it('ignores an older bank destination and defaults to the first supported mobile operator', async () => {
    const state = planning()
    const legacyBank = { id: 'bank', operator: 'Banque', accountHolder: 'Soa Banque', number: 'COMPTE-001', priority: 1, active: true }
    state.sellers[0].payoutDestinations = [legacyBank, alternative]
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue(alternative.number.replaceAll(' ', ''))
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue(alternative.accountHolder)
    expect(screen.getByText('Orange Money')).toBeInTheDocument()
    expect(screen.queryByText('Banque')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Numéro précédent du versement 1' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 25000, sellerPayoutDestinationId: alternative.id }],
    }), 'token'))
    expect(legacyBank.operator).toBe('Banque')
  })

  it('restores the selected account destination by id even after its priority changes', async () => {
    const current = batch()
    current.transfers[0].sellerPayoutDestinationId = alternative.id
    current.transfers[0].sellerPayoutNumber = alternative.number
    current.transfers[0].sellerPayoutAccountHolder = alternative.accountHolder
    const state = planning(current)
    state.sellers[0].payoutDestinations = [{ ...alternative, priority: 1 }, { ...destination, priority: 2 }]
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    expect(await screen.findByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue(alternative.number.replaceAll(' ', ''))
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue(alternative.accountHolder)
    markDirty()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 25000, sellerPayoutDestinationId: alternative.id }],
    }), 'token'))
  })

  it('does not silently replace a deleted account destination with another profile or custom number', async () => {
    const current = batch('STALE')
    current.transfers[0].sellerPayoutDestinationId = 'deleted'
    current.transfers[0].sellerPayoutNumber = '034 88 888 88'
    current.transfers[0].sellerPayoutAccountHolder = 'Ancien titulaire'
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(planning(current))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    expect(await screen.findByText('Numéro indisponible')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue('0348888888')
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue('Ancien titulaire')
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveAttribute('readonly')
    markDirty()
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    expect(saveManualCashBriefing).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Numéro précédent du versement 1' }))
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue(destination.number.replaceAll(' ', ''))
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
  })

  it('requires an actual holder for an older custom proposal instead of using the seller name', async () => {
    const current = batch('STALE')
    current.transfers[0].sellerPayoutNumberOverride = true
    current.transfers[0].sellerPayoutDestinationId = null
    current.transfers[0].sellerPayoutAccountHolder = null
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(planning(current))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    expect(await screen.findByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue('')
    expect(screen.getByText('Personnalisé')).toBeInTheDocument()
    markDirty()
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: 'Titulaire du versement 1' }), { target: { value: 'Titulaire vérifié' } })
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
  })

  it('blocks continuing to the briefing when a loaded legacy custom proposal has no holder', async () => {
    const current = batch()
    current.transfers[0].sellerPayoutNumberOverride = true
    current.transfers[0].sellerPayoutDestinationId = null
    current.transfers[0].sellerPayoutAccountHolder = null
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(planning(current))
    const onBlockedChange = vi.fn()
    render(<CashBriefingPanel accessToken="token" planId="plan" onBlockedChange={onBlockedChange} />)
    await screen.findByRole('textbox', { name: 'Titulaire du versement 1' })
    expect(onBlockedChange).toHaveBeenLastCalledWith(true)
    fireEvent.change(screen.getByRole('textbox', { name: 'Titulaire du versement 1' }), { target: { value: 'Titulaire confirmé' } })
    await waitFor(() => expect(onBlockedChange).toHaveBeenLastCalledWith(false))
  })

  it('resets account selection and custom drafts when the seller changes', async () => {
    const state = planning()
    state.sellers.push({ ...state.sellers[0], sellerAccountId: 'other', sellerName: 'Autre boutique', payoutDestinations: [alternative] })
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    fireEvent.click(screen.getByRole('button', { name: 'Numéro suivant du versement 1' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Numéro du versement 1' }), { target: { value: '034 99 999 99' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Titulaire du versement 1' }), { target: { value: 'Ancien brouillon' } })
    const seller = screen.getByRole('combobox', { name: 'Vendeur du versement 1' })
    fireEvent.change(seller, { target: { value: 'Autre' } })
    fireEvent.mouseDown(screen.getByRole('option', { name: /Autre boutique/ }))
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue(alternative.number.replaceAll(' ', ''))
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue(alternative.accountHolder)
    fireEvent.click(screen.getByRole('button', { name: 'Numéro suivant du versement 1' }))
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue('')
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue('')
  })

  it('blocks a whole seller payout when the assigned courier has insufficient cash', async () => {
    const state = planning()
    state.couriers[0].capacityAriary = 20000
    state.couriers[0].balanceAriary = 20000
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    expect(screen.getByRole('alert')).toHaveTextContent('dépassent la caisse disponible')
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
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
    expect(screen.getByRole('spinbutton')).toHaveValue(25000)
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
    await screen.findByRole('spinbutton')
    markDirty()
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Revalider les caisses' }))
    await waitFor(() => expect(revalidateCashBriefing).toHaveBeenCalledWith('batch', 'token'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeEnabled())
    expect(screen.getByRole('spinbutton')).toHaveValue(25000)
  })

  it('can remove an over-budget stale proposal without revalidating its invalid amounts', async () => {
    const state = planning(batch('STALE'))
    state.ledgerRevision = 8
    state.couriers[0].capacityAriary = 5000
    state.couriers[0].balanceAriary = 5000
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(state)
    const onBlockedChange = vi.fn()
    render(<CashBriefingPanel accessToken="token" planId="plan" onBlockedChange={onBlockedChange} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Retirer le versement 1' }))
    expect(onBlockedChange).toHaveBeenLastCalledWith(true)
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({ expectedLedgerRevision: 8, expectedBatchId: 'batch', transfers: [], courierTransfers: [] }), 'token'))
    expect(revalidateCashBriefing).not.toHaveBeenCalled()
  })

  it('keeps edits and the same key if saving succeeds but refreshing the response fails', async () => {
    vi.mocked(getCashBriefingPlanning).mockResolvedValueOnce(planning()).mockRejectedValueOnce(new Error('Actualisation interrompue')).mockResolvedValue(planning(batch()))
    const onBlockedChange = vi.fn(), onDirtyChange = vi.fn()
    render(<CashBriefingPanel accessToken="token" planId="plan" onBlockedChange={onBlockedChange} onDirtyChange={onDirtyChange} />)
    await addManual()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await screen.findByText('Actualisation interrompue')
    expect(screen.getByRole('spinbutton')).toHaveValue(25000)
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
    vi.mocked(listCashBriefingTransfers).mockResolvedValue(financePage())
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
    vi.mocked(listCashBriefingTransfers).mockResolvedValue(financePage('STALE'))
    render(<CashBriefingFinancePanel accessToken="token" />)
    expect(await screen.findByRole('button', { name: 'Confirmer le versement' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Revalider' }))
    await waitFor(() => expect(revalidateCashBriefing).toHaveBeenCalledWith('batch', 'token'))
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })
})
