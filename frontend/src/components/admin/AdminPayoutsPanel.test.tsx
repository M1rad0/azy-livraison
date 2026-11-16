import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useEffect } from 'react'
import { getDispatchPlan, type DispatchPlan, type DispatchPlanSummary } from '../../api/admin'
import { AdminPayoutsPanel } from './AdminPayoutsPanel'

vi.mock('../../api/admin', async (original) => ({ ...await original<typeof import('../../api/admin')>(), getDispatchPlan: vi.fn() }))
const childGate = vi.hoisted(() => ({ loaded: true }))
vi.mock('./CashBriefingPanel', () => ({ CashBriefingPanel: ({ planId, onDirtyChange, onBlockedChange }: { planId: string; onDirtyChange?: (dirty: boolean) => void; onBlockedChange?: (blocked: boolean) => void }) => {
  useEffect(() => { onDirtyChange?.(false); onBlockedChange?.(!childGate.loaded) }, [onDirtyChange, onBlockedChange])
  return <div data-testid="payout-planning">Planning de {planId}<button type="button" onClick={() => onDirtyChange?.(true)}>Modifier les versements simulés</button><button type="button" onClick={() => { onDirtyChange?.(false); onBlockedChange?.(false) }}>Enregistrer les versements simulés</button><button type="button" onClick={() => onBlockedChange?.(true)}>Bloquer les versements simulés</button></div>
} }))
const date = '2026-11-15'
const summary = (overrides: Partial<DispatchPlanSummary> = {}): DispatchPlanSummary => ({
  id: 'plan-active', serviceDate: date, status: 'READY', strategy: 'MANUAL', revision: 1, version: 0,
  hub: { latitude: -18.91, longitude: 47.52 }, selectedPackageCount: 2, selectedCourierCount: 2,
  createdAt: `${date}T05:00:00Z`, ...overrides,
})
const detail = (plan = summary()): DispatchPlan => ({ plan, packages: [] })
const props = () => ({ accessToken: 'admin-token', date, history: [summary()], onDateChange: vi.fn(), onOpenDispatch: vi.fn(), onOpenBriefing: vi.fn(), onRefresh: vi.fn() })

describe('Étape Versements', () => {
  beforeEach(() => { childGate.loaded = true; vi.mocked(getDispatchPlan).mockResolvedValue(detail()) })
  afterEach(() => { cleanup(); vi.clearAllMocks() })

  it('demande un dispatch actif sans charger ni générer des versements quand il n’existe pas', () => {
    const input = props()
    render(<AdminPayoutsPanel {...input} history={[]} />)
    expect(screen.getByText(/Aucun dispatch validé pour le/)).toBeInTheDocument()
    expect(screen.queryByTestId('payout-planning')).not.toBeInTheDocument()
    expect(getDispatchPlan).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Ouvrir Dispatch' }))
    expect(input.onOpenDispatch).toHaveBeenCalledOnce()
  })

  it.each(['CANCELLED', 'SUPERSEDED', 'FAILED', 'GENERATING'] as const)('ignore un plan %s', (status) => {
    render(<AdminPayoutsPanel {...props()} history={[summary({ status })]} />)
    expect(screen.queryByTestId('payout-planning')).not.toBeInTheDocument()
    expect(getDispatchPlan).not.toHaveBeenCalled()
  })

  it('ignore les plans archivés ou appartenant à une autre journée', () => {
    render(<AdminPayoutsPanel {...props()} history={[summary({ archivedAt: `${date}T06:00:00Z` }), summary({ id: 'another-day', serviceDate: '2026-11-14' })]} />)
    expect(screen.queryByTestId('payout-planning')).not.toBeInTheDocument()
    expect(getDispatchPlan).not.toHaveBeenCalled()
  })

  it('réutilise le planning du dispatch actif et permet de continuer sans imposer un versement', async () => {
    const input = props()
    render(<AdminPayoutsPanel {...input} />)
    expect(await screen.findByTestId('payout-planning')).toHaveTextContent('plan-active')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Continuer vers le briefing' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: 'Continuer vers le briefing' }))
    expect(input.onOpenBriefing).toHaveBeenCalledOnce()
    expect(getDispatchPlan).toHaveBeenCalledWith('plan-active', 'admin-token', expect.any(AbortSignal))
  })

  it('choisit la révision active la plus récente, pas un ancien plan remplacé', async () => {
    const newest = summary({ id: 'newest', revision: 3 })
    vi.mocked(getDispatchPlan).mockResolvedValue(detail(newest))
    render(<AdminPayoutsPanel {...props()} history={[summary({ id: 'old', revision: 2 }), summary({ id: 'cancelled', status: 'CANCELLED', revision: 4 }), newest]} />)
    expect(await screen.findByTestId('payout-planning')).toHaveTextContent('newest')
    expect(getDispatchPlan).toHaveBeenCalledOnce()
  })

  it('ne charge pas le planning si le serveur confirme que le dispatch a été annulé', async () => {
    vi.mocked(getDispatchPlan).mockResolvedValue(detail(summary({ status: 'CANCELLED' })))
    render(<AdminPayoutsPanel {...props()} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Ce dispatch n’est plus actif.')
    expect(screen.queryByTestId('payout-planning')).not.toBeInTheDocument()
  })

  it('renvoie les anciens plans automatiques vers Dispatch', async () => {
    vi.mocked(getDispatchPlan).mockResolvedValue(detail(summary({ strategy: 'VIA_HUB' })))
    render(<AdminPayoutsPanel {...props()} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Ce plan ancien ne possède pas de répartition manuelle.')
    expect(screen.queryByTestId('payout-planning')).not.toBeInTheDocument()
  })

  it('partage le choix de date et actualise les données sans lancer de génération', async () => {
    const input = props()
    render(<AdminPayoutsPanel {...input} />)
    await screen.findByTestId('payout-planning')
    fireEvent.change(screen.getByLabelText('Date d’activité'), { target: { value: '2026-11-16' } })
    expect(input.onDateChange).toHaveBeenCalledWith('2026-11-16')
    fireEvent.click(screen.getByRole('button', { name: 'Actualiser' }))
    await waitFor(() => expect(getDispatchPlan).toHaveBeenCalledTimes(2))
    expect(input.onRefresh).toHaveBeenCalledOnce()
  })

  it('ignore une réponse ancienne lorsque la journée change', async () => {
    let resolve: (value: DispatchPlan) => void = () => {}
    vi.mocked(getDispatchPlan).mockImplementationOnce(() => new Promise<DispatchPlan>((done) => { resolve = done }))
    const input = props()
    const view = render(<AdminPayoutsPanel {...input} />)
    view.rerender(<AdminPayoutsPanel {...input} date="2026-11-16" history={[]} />)
    await act(async () => { resolve(detail()) })
    expect(screen.queryByTestId('payout-planning')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Date d’activité')).toHaveValue('2026-11-16')
  })

  it('protège les versements non enregistrés avant continuation, actualisation et changement de date', async () => {
    const input = props()
    render(<AdminPayoutsPanel {...input} />)
    await screen.findByTestId('payout-planning')
    fireEvent.click(screen.getByRole('button', { name: 'Modifier les versements simulés' }))
    expect(screen.getByRole('button', { name: 'Continuer vers le briefing' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Actualiser' })).toBeDisabled()
    expect(screen.getByLabelText('Date d’activité')).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('Enregistrez les modifications des versements')
    fireEvent.click(screen.getByRole('button', { name: 'Continuer vers le briefing' }))
    expect(input.onOpenBriefing).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements simulés' }))
    expect(screen.getByRole('button', { name: 'Continuer vers le briefing' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Actualiser' })).toBeEnabled()
    expect(screen.getByLabelText('Date d’activité')).toBeEnabled()
  })

  it('bloque la continuation pendant le chargement et les états financiers non fiables', async () => {
    childGate.loaded = false
    render(<AdminPayoutsPanel {...props()} />)
    await screen.findByTestId('payout-planning')
    expect(screen.getByRole('button', { name: 'Continuer vers le briefing' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer les versements simulés' }))
    expect(screen.getByRole('button', { name: 'Continuer vers le briefing' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Bloquer les versements simulés' }))
    expect(screen.getByRole('button', { name: 'Continuer vers le briefing' })).toBeDisabled()
  })
})
