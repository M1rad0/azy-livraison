import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getDispatchPlan, type DispatchPlan, type DispatchPlanSummary } from '../../api/admin'
import { getManualDraftForPlan, type ManualDispatchDraft } from '../../api/manualDispatch'
import { AdminBriefingPanel, DispatchBriefingView } from './AdminBriefingPanel'
import { draftFixture } from './manualDispatch.fixtures'

vi.mock('../../api/admin', async (importOriginal) => ({ ...await importOriginal<typeof import('../../api/admin')>(), getDispatchPlan: vi.fn() }))
vi.mock('../../api/manualDispatch', async (importOriginal) => ({ ...await importOriginal<typeof import('../../api/manualDispatch')>(), getManualDraftForPlan: vi.fn() }))

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

describe('Briefing dispatch views and text', () => {
  beforeEach(() => {
    vi.mocked(getDispatchPlan).mockResolvedValue({ plan: summary(), packages: [] })
    vi.mocked(getManualDraftForPlan).mockResolvedValue(finalized())
  })
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.clearAllMocks(); vi.unstubAllGlobals() })

  it('orders recoveries, exchanges and deliveries and groups the current assignments', () => {
    const { container } = render(<DispatchBriefingView draft={finalized()} allowMessages />)
    expect([...container.querySelectorAll('.briefing-section > header h3')].map((item) => item.textContent)).toEqual(['Récupérations', 'Échanges', 'Colis à livrer'])
    expect(within(screen.getByRole('region', { name: 'Échanges' })).getByText('Baptiste → Jean')).toBeInTheDocument()
    const recovery = screen.getByRole('region', { name: 'Récupérations' })
    expect(within(recovery).getByText('2 colis', { exact: false })).toBeInTheDocument()
    expect(screen.queryByText(/lieu de rencontre/i)).not.toBeInTheDocument()
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
    const opener = screen.getAllByRole('button', { name: 'Générer le briefing de Baptiste' })[0]
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
    fireEvent.click(screen.getAllByRole('button', { name: 'Générer le briefing de Baptiste' })[0])
    fireEvent.change(screen.getByRole('textbox', { name: 'Texte du briefing' }), { target: { value: 'Texte personnel de Baptiste' } })
    fireEvent.click(screen.getByRole('button', { name: 'Fermer le briefing texte' }))
    fireEvent.click(screen.getAllByRole('button', { name: 'Générer le briefing de Jean' })[0])
    expect((screen.getByRole('textbox', { name: 'Texte du briefing' }) as HTMLTextAreaElement).value).toContain('Prendre chez Baptiste')
    expect(screen.getByRole('textbox', { name: 'Texte du briefing' })).not.toHaveValue('Texte personnel de Baptiste')
  })

  it('offers manual selection when clipboard permission is denied and disables empty copying', async () => {
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    render(<DispatchBriefingView draft={finalized()} allowMessages />)
    fireEvent.click(screen.getAllByRole('button', { name: 'Générer le briefing de Baptiste' })[0])
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
    fireEvent.click(screen.getAllByRole('button', { name: 'Générer le briefing de Baptiste' })[0])
    const close = screen.getByRole('button', { name: 'Fermer le briefing texte' })
    close.focus()
    expect(close).toHaveFocus()
    expect(screen.getByRole('dialog').querySelectorAll('button:not(:disabled), textarea')).toHaveLength(4)
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
