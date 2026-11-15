import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { confirmCashBriefingTransfer, getCashBriefingPlanning, listCashBriefings, prepareCashBriefing, releaseCashBriefing, revalidateCashBriefing, saveManualCashBriefing, type CashBriefingBatch, type CashBriefingPlanning } from '../../api/cashBriefing'
import { CashBriefingPanel } from './CashBriefingPanel'
import { CashBriefingFinancePanel } from './CashBriefingFinancePanel'

vi.mock('../../api/cashBriefing', () => ({ getCashBriefingPlanning: vi.fn(), saveManualCashBriefing: vi.fn(), listCashBriefings: vi.fn(), prepareCashBriefing: vi.fn(), releaseCashBriefing: vi.fn(), revalidateCashBriefing: vi.fn(), confirmCashBriefingTransfer: vi.fn() }))
const destination = { id: 'destination', operator: 'MVola', accountHolder: 'Soa Rakoto', number: '034 00 000 00', priority: 1, active: true }
const alternative = { id: 'alternative', operator: 'Orange Money', accountHolder: 'Soa Rabe', number: '032 11 111 11', priority: 2, active: true }
function batch(status: CashBriefingBatch['status'] = 'ACTIVE'): CashBriefingBatch {
  return { id: 'batch', planId: 'plan', serviceDate: '2026-11-15', status, ledgerRevision: 7, expiresAt: '2026-11-15T21:00:00Z', algorithm: 'exact', message: status === 'STALE' ? 'Les caisses ont changé.' : 'À confirmer dans Finance.', balances: [], transfers: [{ id: 'transfer', sellerAccountId: 'seller', sellerName: 'Boutique Soa', sellerPayoutNumber: destination.number, sellerPayoutDestinationId: destination.id, sellerPayoutAccountHolder: destination.accountHolder, sellerPayoutOperator: destination.operator, courierAccountId: 'courier', courierName: 'Baptiste', amountAriary: 25000, status: 'PENDING' }] }
}
function planning(current?: CashBriefingBatch): CashBriefingPlanning {
  return { planId: 'plan', serviceDate: '2026-11-15', ledgerRevision: 7, batch: current, sellers: [{ sellerAccountId: 'seller', sellerName: 'Boutique Soa', sellerPayoutNumber: destination.number, payoutDestinations: [destination], balanceAriary: 25000, plannedAriary: current ? 25000 : 0, availableAriary: 25000 }, { sellerAccountId: 'zero', sellerName: 'Vendeur sans solde', balanceAriary: 0, plannedAriary: 0, availableAriary: 0 }], couriers: [{ courierAccountId: 'courier', courierName: 'Baptiste', balanceAriary: 30000, reservedElsewhereAriary: 0, capacityAriary: 30000, plannedAriary: current ? 25000 : 0, remainingAriary: current ? 5000 : 30000 }] }
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

  it('creates partial manual plans without validating a real payment', async () => {
    const onBatchChange = vi.fn()
    render(<CashBriefingPanel accessToken="token" planId="plan" onBatchChange={onBatchChange} />)
    await addManual()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith({ planId: 'plan', expectedLedgerRevision: 7, expectedBatchId: null, idempotencyKey: expect.any(String), transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 12000, sellerPayoutDestinationId: destination.id }] }, 'token'))
    expect(confirmCashBriefingTransfer).not.toHaveBeenCalled()
  })

  it('defaults to read-only profile details and saves a custom number and holder without modifying the seller account', async () => {
    const initial = planning()
    const saved = batch()
    saved.transfers[0].sellerPayoutNumber = '034 99 999 99'
    saved.transfers[0].sellerPayoutNumberOverride = true
    saved.transfers[0].sellerPayoutDestinationId = null
    saved.transfers[0].sellerPayoutAccountHolder = 'Titulaire spécifique'
    vi.mocked(getCashBriefingPlanning).mockResolvedValueOnce(initial).mockResolvedValue(planning(saved))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    await addManual()
    const number = screen.getByRole('textbox', { name: 'Numéro du versement 1' })
    expect(number).toHaveValue('034 00 000 00')
    expect(number).toHaveAttribute('readonly')
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue(destination.accountHolder)
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveAttribute('readonly')
    fireEvent.click(screen.getByRole('button', { name: 'Numéro suivant du versement 1' }))
    expect(screen.getByText('Personnalisé')).toBeInTheDocument()
    expect(number).not.toHaveAttribute('readonly')
    fireEvent.change(number, { target: { value: '034 99 999 99' } })
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: 'Titulaire du versement 1' }), { target: { value: 'Titulaire spécifique' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 12000, sellerPayoutNumber: '034 99 999 99', sellerPayoutAccountHolder: 'Titulaire spécifique' }],
    }), 'token'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled())
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue('034 99 999 99')
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
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue('034 00 000 00')
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveAttribute('readonly')
    fireEvent.click(screen.getByRole('button', { name: 'Numéro suivant du versement 1' }))
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue('034 99 999 99')
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue('Nom personnalisé')
    fireEvent.click(screen.getByRole('button', { name: 'Numéro précédent du versement 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 12000, sellerPayoutDestinationId: destination.id }],
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
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 12000, sellerPayoutNumber: '034 99 999 99', sellerPayoutAccountHolder: 'Titulaire personnalisé' }],
    }), 'token'))
  })

  it('retains a saved override while editing amounts even if the account default changed', async () => {
    const current = batch()
    current.transfers[0].sellerPayoutNumber = '034 99 999 99'
    current.transfers[0].sellerPayoutNumberOverride = true
    current.transfers[0].sellerPayoutDestinationId = null
    current.transfers[0].sellerPayoutAccountHolder = 'Titulaire enregistré'
    vi.mocked(getCashBriefingPlanning).mockResolvedValue(planning(current))
    render(<CashBriefingPanel accessToken="token" planId="plan" />)
    expect(await screen.findByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue('034 99 999 99')
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '10000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 10000, sellerPayoutNumber: '034 99 999 99', sellerPayoutAccountHolder: 'Titulaire enregistré' }],
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
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue(alternative.number)
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue(alternative.accountHolder)
    expect(screen.getByText('Orange Money')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveAttribute('readonly')
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 12000, sellerPayoutDestinationId: alternative.id }],
    }), 'token'))
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
    expect(await screen.findByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue(alternative.number)
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue(alternative.accountHolder)
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '10000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements' }))
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({
      transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 10000, sellerPayoutDestinationId: alternative.id }],
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
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue('034 88 888 88')
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue('Ancien titulaire')
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveAttribute('readonly')
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '10000' } })
    expect(screen.getByRole('button', { name: 'Enregistrer les versements' })).toBeDisabled()
    expect(saveManualCashBriefing).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Numéro précédent du versement 1' }))
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue(destination.number)
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
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '10000' } })
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
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue(alternative.number)
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue(alternative.accountHolder)
    fireEvent.click(screen.getByRole('button', { name: 'Numéro suivant du versement 1' }))
    expect(screen.getByRole('textbox', { name: 'Numéro du versement 1' })).toHaveValue('')
    expect(screen.getByRole('textbox', { name: 'Titulaire du versement 1' })).toHaveValue('')
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
    await waitFor(() => expect(saveManualCashBriefing).toHaveBeenCalledWith(expect.objectContaining({ expectedLedgerRevision: 8, expectedBatchId: 'batch', transfers: [{ sellerAccountId: 'seller', courierAccountId: 'courier', amountAriary: 5000, sellerPayoutDestinationId: destination.id }] }), 'token'))
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
