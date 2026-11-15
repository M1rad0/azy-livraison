import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getDispatchPlan, listAdminClients, type DispatchPlan, type DispatchPlanSummary } from '../../api/admin'
import { getManualDraftForPlan, type ManualDispatchDraft } from '../../api/manualDispatch'
import { listCashBriefings, type CashBriefingBatch } from '../../api/cashBriefing'
import { AdminBriefingPanel, DispatchBriefingView } from './AdminBriefingPanel'
import { draftFixture } from './manualDispatch.fixtures'

vi.mock('../../api/admin', async (importOriginal) => ({ ...await importOriginal<typeof import('../../api/admin')>(), getDispatchPlan: vi.fn(), listAdminClients: vi.fn() }))
vi.mock('../../api/manualDispatch', async (importOriginal) => ({ ...await importOriginal<typeof import('../../api/manualDispatch')>(), getManualDraftForPlan: vi.fn() }))
vi.mock('../../api/cashBriefing', async (importOriginal) => ({ ...await importOriginal<typeof import('../../api/cashBriefing')>(), listCashBriefings: vi.fn() }))

function finalized(): ManualDispatchDraft {
  return { ...draftFixture(), status: 'FINALIZED', finalPlanId: 'plan-1', step: 'REVIEW',
    collections: [{ pickupId: 'r1', courierId: 'c1', order: 0 }, { pickupId: 'r2', courierId: 'c2', order: 0 }],
    deliveries: [{ packageId: 'p2', courierId: 'c1', order: 0 }, { packageId: 'p3', courierId: 'c2', order: 0 }, { packageId: 'p1', courierId: 'c2', order: 1 }],
  }
}
function summary(overrides: Partial<DispatchPlanSummary> = {}): DispatchPlanSummary {
  return { id: 'plan-1', serviceDate: '2026-11-14', status: 'READY', strategy: 'MANUAL', hub: draftFixture().hub,
    selectedPackageCount: 3, selectedCourierCount: 2, version: 1, createdAt: '2026-11-14T03:00:00Z', ...overrides }
}
function panel(props: Partial<Parameters<typeof AdminBriefingPanel>[0]> = {}) {
  const all = { accessToken: 'token', date: '2026-11-14', history: [summary()], onDateChange: vi.fn(), onOpenDispatch: vi.fn(), onRefresh: vi.fn(), ...props }
  return { ...render(<AdminBriefingPanel {...all} />), props: all }
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done }); return { promise, resolve } }
function messageButton(courierName = 'Baptiste') {
  fireEvent.click(screen.getByRole('button', { name: 'Par livreur' }))
  const picker = screen.getByRole('combobox', { name: 'Livreur du briefing' })
  fireEvent.focus(picker)
  fireEvent.change(picker, { target: { value: courierName } })
  fireEvent.mouseDown(screen.getByRole('option', { name: new RegExp(courierName) }))
  return screen.getByRole('button', { name: 'Générer le briefing' })
}

describe('Briefing dispatch views and text', () => {
  const payoutBatch = (): CashBriefingBatch => ({ id: 'batch', planId: 'plan-1', serviceDate: '2026-11-14', status: 'ACTIVE', ledgerRevision: 4, expiresAt: '2026-11-14T21:00:00Z', algorithm: 'exact', message: 'À confirmer dans Finance', balances: [], transfers: [{ id: 'transfer', sellerAccountId: 'seller', sellerName: 'Vendeur Soa', sellerPayoutNumber: '034 99 999 99', courierAccountId: 'c1', courierName: 'Baptiste', amountAriary: 25000, status: 'PENDING' }] })
  beforeEach(() => {
    vi.mocked(getDispatchPlan).mockResolvedValue({ plan: summary(), packages: [] })
    vi.mocked(getManualDraftForPlan).mockResolvedValue(finalized())
    vi.mocked(listAdminClients).mockResolvedValue([])
    vi.mocked(listCashBriefings).mockResolvedValue([])
  })
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.clearAllMocks(); vi.unstubAllGlobals() })

  it('invalidates both independently edited languages when the financial proposal changes', () => {
    const batch = payoutBatch()
    const { rerender } = render(<DispatchBriefingView draft={finalized()} allowMessages cashBatch={batch} />)
    fireEvent.click(messageButton())
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toContain('25000 Ar')
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'FR ancienne proposition' } })
    fireEvent.click(screen.getByRole('tab', { name: 'Malagasy' }))
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'MG ancienne proposition' } })
    rerender(<DispatchBriefingView draft={finalized()} allowMessages cashBatch={{ ...batch, ledgerRevision: 5, transfers: [{ ...batch.transfers[0], amountAriary: 24000 }] }} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Générer le briefing' }))
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toContain('24000 Ar')
    expect(screen.getByRole('textbox')).not.toHaveValue('MG ancienne proposition')
    fireEvent.click(screen.getByRole('tab', { name: 'Français' }))
    expect(screen.getByRole('textbox')).not.toHaveValue('FR ancienne proposition')
    rerender(<DispatchBriefingView draft={finalized()} allowMessages cashBatch={{ ...batch, ledgerRevision: 5, transfers: [{ ...batch.transfers[0], amountAriary: 24000, sellerPayoutNumber: '034 88 888 88' }] }} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Générer le briefing' }))
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toContain('MVola : 034 88 888 88')
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).not.toContain('034 99 999 99')
  })

  it('blocks message generation while a financial proposal needs revalidation', () => {
    render(<DispatchBriefingView draft={finalized()} allowMessages cashBatch={{ ...payoutBatch(), status: 'STALE' }} />)
    fireEvent.click(screen.getByRole('button', { name: 'Par livreur' }))
    expect(screen.queryByRole('button', { name: 'Générer le briefing' })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Colis à livrer' })).toBeInTheDocument()
  })

  it('does not copy a message when a fresh financial check fails', async () => {
    const validate = vi.fn().mockResolvedValueOnce(true).mockResolvedValueOnce(false)
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    render(<DispatchBriefingView draft={finalized()} allowMessages cashBatch={payoutBatch()} validateCash={validate} />)
    fireEvent.click(messageButton())
    await screen.findByRole('textbox')
    fireEvent.click(screen.getByRole('button', { name: 'Copier le texte' }))
    expect(await screen.findByText(/Les caisses ou la proposition ont changé/)).toBeInTheDocument()
    expect(writeText).not.toHaveBeenCalled()
  })

  it('orders recoveries, exchanges and deliveries and groups the current assignments', () => {
    const { container } = render(<DispatchBriefingView draft={finalized()} allowMessages />)
    expect([...container.querySelectorAll('.briefing-section > header h3')].map((item) => item.textContent)).toEqual(['Récupérations', 'Échanges', 'Colis à livrer', 'Versements à faire'])
    expect(within(screen.getByRole('region', { name: 'Échanges' })).getByText('Baptiste → Jean')).toBeInTheDocument()
    const recovery = screen.getByRole('region', { name: 'Récupérations' })
    expect(within(recovery).getByText('2 colis', { exact: false })).toBeInTheDocument()
    expect(screen.queryByText(/lieu de rencontre/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /briefing/i })).not.toBeInTheDocument()
  })

  it('switches to a searchable individual view and retains delivery order', () => {
    render(<DispatchBriefingView draft={finalized()} allowMessages />)
    fireEvent.click(screen.getByRole('button', { name: 'Par livreur' }))
    const picker = screen.getByRole('combobox', { name: 'Livreur du briefing' })
    fireEvent.change(picker, { target: { value: 'Jean' } })
    fireEvent.mouseDown(screen.getByRole('option', { name: /Jean/ }))
    expect(screen.getByRole('heading', { name: 'Jean' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Échanges' })).getByText('Prendre chez')).toBeInTheDocument()
    const deliveries = within(screen.getByRole('region', { name: 'Colis à livrer' })).getAllByRole('listitem')
    expect(deliveries[0]).toHaveTextContent('Destinataire 3')
    expect(deliveries[1]).toHaveTextContent('Destinataire 1')
    expect(screen.getByRole('button', { name: 'Générer le briefing' })).toBeEnabled()
  })

  it('opens editable text, copies exactly the edits and keeps them when reopened', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    render(<DispatchBriefingView draft={finalized()} allowMessages />)
    const opener = messageButton()
    opener.focus(); fireEvent.click(opener)
    const dialog = screen.getByRole('dialog', { name: 'Briefing de Baptiste' })
    const textarea = within(dialog).getByRole('textbox', { name: 'Texte du briefing' })
    expect(textarea).toHaveFocus()
    expect((textarea as HTMLTextAreaElement).value).toContain('Donner à Jean')
    expect(document.body.style.overflow).toBe('hidden')
    fireEvent.change(textarea, { target: { value: 'Bonjour Baptiste, récupération à Analakely.' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Copier le texte' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('Bonjour Baptiste, récupération à Analakely.'))
    expect(await screen.findByText('Texte copié.')).toBeInTheDocument()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
    expect(document.body.style.overflow).not.toBe('hidden')
    fireEvent.click(opener)
    expect(screen.getByRole('textbox', { name: 'Texte du briefing' })).toHaveValue('Bonjour Baptiste, récupération à Analakely.')
  })

  it('keeps changes separate for each courier', () => {
    render(<DispatchBriefingView draft={finalized()} allowMessages />)
    fireEvent.click(messageButton())
    fireEvent.change(screen.getByRole('textbox', { name: 'Texte du briefing' }), { target: { value: 'Texte personnel de Baptiste' } })
    fireEvent.click(screen.getByRole('button', { name: 'Fermer le briefing texte' }))
    fireEvent.click(messageButton('Jean'))
    expect((screen.getByRole('textbox', { name: 'Texte du briefing' }) as HTMLTextAreaElement).value).toContain('Prendre chez Baptiste')
    expect(screen.getByRole('textbox', { name: 'Texte du briefing' })).not.toHaveValue('Texte personnel de Baptiste')
  })

  it('keeps FR and MG edits independent per courier, including after reopening, and copies the active language', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    render(<DispatchBriefingView draft={finalized()} allowMessages />)
    fireEvent.click(messageButton())
    expect(screen.getByRole('tab', { name: 'Français' })).toHaveAttribute('aria-selected', 'true')
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'FR Baptiste personnalisé' } })
    fireEvent.click(screen.getByRole('tab', { name: 'Malagasy' }))
    expect(screen.getByRole('tabpanel', { name: 'Malagasy' })).toBeInTheDocument()
    expect(screen.getByRole('textbox')).toHaveAttribute('lang', 'mg')
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toContain('Entana alaina :')
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toContain("Omena an'i Jean")
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'MG Baptiste personnalisé' } })
    fireEvent.click(screen.getByRole('button', { name: 'Copier le texte' }))
    await waitFor(() => expect(writeText).toHaveBeenLastCalledWith('MG Baptiste personnalisé'))
    fireEvent.click(screen.getByRole('tab', { name: 'Français' }))
    expect(screen.queryByText('Texte copié.')).not.toBeInTheDocument()
    expect(screen.getByRole('textbox')).toHaveValue('FR Baptiste personnalisé')
    fireEvent.click(screen.getByRole('button', { name: 'Copier le texte' }))
    await waitFor(() => expect(writeText).toHaveBeenLastCalledWith('FR Baptiste personnalisé'))
    fireEvent.click(screen.getByRole('button', { name: 'Fermer le briefing texte' }))
    fireEvent.click(screen.getByRole('button', { name: 'Livreur suivant' }))
    fireEvent.click(screen.getByRole('button', { name: 'Générer le briefing' }))
    expect(screen.getByRole('textbox')).not.toHaveValue('FR Baptiste personnalisé')
    fireEvent.click(screen.getByRole('tab', { name: 'Malagasy' }))
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toContain("Alaina any amin'i Baptiste")
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'MG Jean personnalisé' } })
    fireEvent.click(screen.getByRole('button', { name: 'Fermer le briefing texte' }))
    fireEvent.click(screen.getByRole('button', { name: 'Livreur précédent' }))
    fireEvent.click(screen.getByRole('button', { name: 'Générer le briefing' }))
    expect(screen.getByRole('textbox')).toHaveValue('MG Baptiste personnalisé')
    fireEvent.click(screen.getByRole('tab', { name: 'Français' }))
    expect(screen.getByRole('textbox')).toHaveValue('FR Baptiste personnalisé')
  })

  it('navigates alphabetically without wrapping and keeps search in sync with both arrows', () => {
    const draft = finalized()
    draft.couriers = [draft.couriers[1], { ...draft.couriers[0], courierId: 'c3', name: 'Zoé' }, draft.couriers[0]]
    render(<DispatchBriefingView draft={draft} allowMessages />)
    fireEvent.click(screen.getByRole('button', { name: 'Par livreur' }))
    const previous = screen.getByRole('button', { name: 'Livreur précédent' })
    const next = screen.getByRole('button', { name: 'Livreur suivant' })
    const picker = screen.getByRole('combobox', { name: 'Livreur du briefing' })
    expect(picker).toHaveValue('Baptiste')
    expect(previous).toBeDisabled()
    expect(next).toBeEnabled()
    expect(screen.getByText('1 / 3')).toBeInTheDocument()
    fireEvent.click(next)
    expect(picker).toHaveValue('Jean')
    expect(previous).toBeEnabled()
    expect(screen.getByRole('heading', { name: 'Jean' })).toBeInTheDocument()
    fireEvent.click(next)
    expect(picker).toHaveValue('Zoé')
    expect(next).toBeDisabled()
    expect(screen.getByText('3 / 3')).toBeInTheDocument()
    fireEvent.click(next)
    expect(picker).toHaveValue('Zoé')
    fireEvent.change(picker, { target: { value: 'Bap' } })
    fireEvent.mouseDown(screen.getByRole('option', { name: /Baptiste/ }))
    expect(previous).toBeDisabled()
    expect(next).toBeEnabled()
    fireEvent.click(next)
    fireEvent.click(previous)
    expect(picker).toHaveValue('Baptiste')
    expect(previous).toBeDisabled()
  })

  it('disables both courier arrows when only one courier is present', () => {
    const draft = finalized()
    draft.couriers = [draft.couriers[0]]
    draft.collections = draft.collections.map((item) => ({ ...item, courierId: 'c1' }))
    draft.deliveries = draft.deliveries.map((item) => ({ ...item, courierId: 'c1' }))
    render(<DispatchBriefingView draft={draft} allowMessages />)
    fireEvent.click(screen.getByRole('button', { name: 'Par livreur' }))
    expect(screen.getByRole('button', { name: 'Livreur précédent' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Livreur suivant' })).toBeDisabled()
    expect(screen.getByText('1 / 1')).toBeInTheDocument()
  })

  it('switches language tabs with the keyboard without losing either edited text', () => {
    render(<DispatchBriefingView draft={finalized()} allowMessages />)
    fireEvent.click(messageButton())
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'FR modifié' } })
    const fr = screen.getByRole('tab', { name: 'Français' })
    const mg = screen.getByRole('tab', { name: 'Malagasy' })
    fr.focus()
    fireEvent.keyDown(fr, { key: 'ArrowRight' })
    expect(mg).toHaveFocus()
    expect(mg).toHaveAttribute('tabindex', '0')
    expect(fr).toHaveAttribute('tabindex', '-1')
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'MG modifié' } })
    fireEvent.keyDown(mg, { key: 'Home' })
    expect(fr).toHaveFocus()
    expect(screen.getByRole('textbox')).toHaveValue('FR modifié')
    fireEvent.keyDown(fr, { key: 'End' })
    expect(mg).toHaveFocus()
    expect(screen.getByRole('textbox')).toHaveValue('MG modifié')
    fireEvent.keyDown(mg, { key: 'ArrowLeft' })
    expect(fr).toHaveFocus()
  })

  it('does not show the other language clipboard status after a switch during copying', async () => {
    const copied = deferred<void>()
    const writeText = vi.fn().mockReturnValue(copied.promise)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    render(<DispatchBriefingView draft={finalized()} allowMessages />)
    fireEvent.click(messageButton())
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'FR envoyé' } })
    fireEvent.click(screen.getByRole('button', { name: 'Copier le texte' }))
    fireEvent.click(screen.getByRole('tab', { name: 'Malagasy' }))
    copied.resolve()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Copier le texte' })).toBeEnabled())
    expect(writeText).toHaveBeenCalledWith('FR envoyé')
    expect(screen.queryByText(/a été copiée|Texte copié/)).not.toBeInTheDocument()
    expect(screen.getByRole('textbox')).toHaveAttribute('lang', 'mg')
  })

  it('uses seller contacts only in the generated text, without changing the visual briefing', () => {
    const draft = finalized()
    const { container, rerender } = render(<DispatchBriefingView draft={draft} allowMessages />)
    const html = container.innerHTML
    rerender(<DispatchBriefingView draft={draft} allowMessages sellerContacts={{ client: '034 99 999 99' }} />)
    expect(container.innerHTML).toBe(html)
    fireEvent.click(messageButton())
    const text = (screen.getByRole('textbox', { name: 'Texte du briefing' }) as HTMLTextAreaElement).value
    expect(text.split('Échanges :')[0]).toContain('034 99 999 99')
    expect(text.split('Échanges :')[1]).not.toMatch(/Destinataire|Boutique/)
    expect(text.split('Échanges :')[1]).toContain('Destination 1')
    expect(text.split('Échanges :')[1]).toContain('contact : 034')
  })

  it('offers manual selection when clipboard permission is denied and disables empty copying', async () => {
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    render(<DispatchBriefingView draft={finalized()} allowMessages />)
    fireEvent.click(messageButton())
    const textarea = screen.getByRole('textbox', { name: 'Texte du briefing' }) as HTMLTextAreaElement
    fireEvent.click(screen.getByRole('button', { name: 'Copier le texte' }))
    expect(await screen.findByText(/Copie automatique indisponible/)).toBeInTheDocument()
    expect(textarea.selectionStart).toBe(0)
    expect(textarea.selectionEnd).toBe(textarea.value.length)
    fireEvent.change(textarea, { target: { value: ' ' } })
    expect(screen.getByRole('button', { name: 'Copier le texte' })).toBeDisabled()
  })

  it('traps keyboard focus in the editor and closes on the backdrop', () => {
    render(<DispatchBriefingView draft={finalized()} allowMessages />)
    fireEvent.click(messageButton())
    const close = screen.getByRole('button', { name: 'Fermer le briefing texte' })
    close.focus()
    expect(close).toHaveFocus()
    expect([...screen.getByRole('dialog').querySelectorAll<HTMLElement>('button:not(:disabled), textarea')].filter((control) => control.tabIndex >= 0)).toHaveLength(5)
    fireEvent.keyDown(window, { key: 'Tab', shiftKey: true })
    expect(screen.getByRole('button', { name: 'Copier le texte' })).toHaveFocus()
    fireEvent.keyDown(window, { key: 'Tab' })
    expect(close).toHaveFocus()
    fireEvent.mouseDown(screen.getByRole('dialog').parentElement!)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('does not generate text from unvalidated or inconsistent assignments', () => {
    const draft = { ...finalized(), status: 'DRAFT' as const }
    const { rerender } = render(<DispatchBriefingView draft={draft} allowMessages />)
    fireEvent.click(screen.getByRole('button', { name: 'Par livreur' }))
    expect(screen.queryByRole('button', { name: /Générer le briefing/ })).not.toBeInTheDocument()
    rerender(<DispatchBriefingView draft={{ ...finalized(), deliveries: [] }} allowMessages />)
    expect(screen.getByRole('alert')).toHaveTextContent('Répartition à vérifier')
    expect(screen.queryByRole('button', { name: /Générer le briefing/ })).not.toBeInTheDocument()
  })

  it('loads the validated snapshot, not current courier profiles or a mutable draft', async () => {
    panel()
    expect(await screen.findByText('Baptiste → Jean')).toBeInTheDocument()
    expect(getDispatchPlan).toHaveBeenCalledWith('plan-1', 'token', expect.any(AbortSignal))
    expect(getManualDraftForPlan).toHaveBeenCalledWith('plan-1', 'token', expect.any(AbortSignal))
  })

  it('reads planned payouts without offering generation or mutation inside the briefing', async () => {
    vi.mocked(listCashBriefings).mockResolvedValue([payoutBatch()])
    const onOpenPayouts = vi.fn()
    panel({ onOpenPayouts })
    const payments = await screen.findByRole('region', { name: 'Versements à faire' })
    expect(within(payments).getByText('Vendeur Soa')).toBeInTheDocument()
    expect(within(payments).getByText('MVola : 034 99 999 99')).toBeInTheDocument()
    expect(within(payments).getByText('Baptiste')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Générer automatiquement|Préparer les versements|Libérer la proposition|Enregistrer les versements/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Modifier les versements' }))
    expect(onOpenPayouts).toHaveBeenCalledOnce()
    fireEvent.click(messageButton())
    const text = (await screen.findByRole('textbox')) as HTMLTextAreaElement
    expect(text.value).toContain('Versements à faire :\n- Vendeur Soa — MVola : 034 99 999 99 — 25000 Ar')
    fireEvent.click(screen.getByRole('tab', { name: 'Malagasy' }))
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toContain('Vola halefa :')
  })

  it('limits individual payout lists to this courier and excludes already confirmed transfers', () => {
    const batch = payoutBatch()
    batch.transfers.push({ ...batch.transfers[0], id: 'other', courierAccountId: 'c2', courierName: 'Jean', sellerName: 'Autre vendeur' })
    batch.transfers.push({ ...batch.transfers[0], id: 'paid', sellerName: 'Déjà réglé', status: 'CONFIRMED' })
    render(<DispatchBriefingView draft={finalized()} allowMessages cashBatch={batch} />)
    fireEvent.click(messageButton())
    const payments = screen.getByRole('region', { name: 'Versements à faire' })
    expect(within(payments).getByText('Vendeur Soa')).toBeInTheDocument()
    expect(within(payments).queryByText('Autre vendeur')).not.toBeInTheDocument()
    expect(within(payments).queryByText('Déjà réglé')).not.toBeInTheDocument()
  })

  it('keeps routes visible but blocks text generation if planned payouts cannot be checked', async () => {
    vi.mocked(listCashBriefings).mockRejectedValue(new Error('Versements indisponibles'))
    panel()
    expect(await screen.findByText('Baptiste → Jean')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Versements indisponibles')
    fireEvent.click(screen.getByRole('button', { name: 'Par livreur' }))
    expect(screen.queryByRole('button', { name: 'Générer le briefing' })).not.toBeInTheDocument()
  })

  it('loads the current seller contact for the text via the existing admin client API', async () => {
    vi.mocked(listAdminClients).mockResolvedValue([{ accountId: 'client', username: 'boutique', displayName: 'Boutique', phone: '032 55 555 55', enabled: true, expressDeliveryEnabled: false, defaultPickup: null, version: 0 }])
    panel()
    await screen.findByText('Baptiste → Jean')
    fireEvent.click(messageButton())
    expect(listAdminClients).toHaveBeenCalledWith('token', expect.any(AbortSignal))
    expect(((await screen.findByRole('textbox', { name: 'Texte du briefing' })) as HTMLTextAreaElement).value.split('Échanges :')[0]).toContain('032 55 555 55')
  })

  it('keeps the visual briefing available if seller contacts cannot be loaded', async () => {
    vi.mocked(listAdminClients).mockRejectedValue(new Error('Contacts indisponibles'))
    panel()
    expect(await screen.findByText('Baptiste → Jean')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    fireEvent.click(messageButton())
    expect(((await screen.findByRole('textbox', { name: 'Texte du briefing' })) as HTMLTextAreaElement).value).toContain('contact vendeur indisponible')
  })

  it('shows an actionable empty state without fetching cancelled or superseded plans', () => {
    const onOpenDispatch = vi.fn()
    panel({ history: [summary({ status: 'CANCELLED' }), summary({ id: 'old', status: 'SUPERSEDED' })], onOpenDispatch })
    expect(screen.getByText(/Aucun dispatch validé/)).toBeInTheDocument()
    expect(getDispatchPlan).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Ouvrir Dispatch' }))
    expect(onOpenDispatch).toHaveBeenCalledOnce()
  })

  it('supports a day change, refresh and snapshot errors without showing a misleading briefing', async () => {
    vi.mocked(getManualDraftForPlan).mockRejectedValue(new Error('Plan indisponible'))
    const { props } = panel()
    expect(await screen.findByRole('alert')).toHaveTextContent('Plan indisponible')
    fireEvent.change(screen.getByLabelText('Date d’activité'), { target: { value: '2026-11-15' } })
    expect(props.onDateChange).toHaveBeenCalledWith('2026-11-15')
    fireEvent.click(screen.getByRole('button', { name: 'Actualiser' }))
    expect(props.onRefresh).toHaveBeenCalledOnce()
  })

  it('ignores obsolete responses after changing day', async () => {
    const late = deferred<DispatchPlan>()
    vi.mocked(getDispatchPlan).mockReturnValue(late.promise)
    const { props, rerender } = panel()
    rerender(<AdminBriefingPanel {...props} date="2026-11-15" history={[]} />)
    late.resolve({ plan: summary(), packages: [] })
    await waitFor(() => expect(screen.getByText(/Aucun dispatch validé/)).toBeInTheDocument())
    expect(getManualDraftForPlan).not.toHaveBeenCalled()
    expect(screen.queryByText('Baptiste → Jean')).not.toBeInTheDocument()
  })

  it('rejects a cancelled plan or a snapshot belonging to another plan', async () => {
    vi.mocked(getDispatchPlan).mockResolvedValueOnce({ plan: summary({ status: 'CANCELLED' }), packages: [] })
    const { props, rerender } = panel()
    expect(await screen.findByRole('alert')).toHaveTextContent('Ce dispatch n’est plus actif')
    expect(getManualDraftForPlan).not.toHaveBeenCalled()
    vi.mocked(getManualDraftForPlan).mockResolvedValueOnce({ ...finalized(), finalPlanId: 'another-plan' })
    rerender(<AdminBriefingPanel {...props} history={[summary({ version: 2 })]} />)
    expect(await screen.findByText(/Le briefing de ce plan n’est pas disponible/)).toBeInTheDocument()
    expect(screen.queryByText('Baptiste → Jean')).not.toBeInTheDocument()
  })
})
