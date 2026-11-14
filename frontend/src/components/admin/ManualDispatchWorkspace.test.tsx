import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ManualDispatchWorkspace, ManualDispatchReview } from './ManualDispatchWorkspace'
import { assignPoint, changeManualStep } from './manualDispatchLayout'
import { draftFixture } from './manualDispatch.fixtures'
import { finalizeManualDraft, saveManualDraft } from '../../api/manualDispatch'

vi.mock('./ManualDispatchMap', () => ({ ManualDispatchMap: () => <div>Carte de test</div> }))
vi.mock('../LocationPicker', () => ({ LocationPicker: () => <div>Lieu de départ</div> }))
vi.mock('../../api/manualDispatch', () => ({
  saveManualDraft: vi.fn(), finalizeManualDraft: vi.fn(), cancelManualDraft: vi.fn(), getActiveManualDraft: vi.fn(),
}))

describe('espace de dispatch manuel', () => {
  beforeEach(() => {
    vi.mocked(saveManualDraft).mockImplementation(async (id, version, layout) => ({ ...draftFixture(), id, ...layout, version: version + 1 }))
    vi.mocked(finalizeManualDraft).mockResolvedValue({ plan: { id: 'plan' } } as Awaited<ReturnType<typeof finalizeManualDraft>>)
  })
  afterEach(() => { cleanup(); vi.clearAllMocks() })

  it('bloque la suite avant affectation de toutes les récupérations', () => {
    render(<ManualDispatchWorkspace accessToken="token" draft={draftFixture()} onDraftChange={vi.fn()} onFinalized={vi.fn()} onCancelled={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Répartir les colis' })).toBeDisabled()
    expect(screen.getByText('2 à affecter')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Carte' }))
    expect(screen.getByText('Carte de test')).toBeInTheDocument()
  })

  it('affecte par glisser-déposer et initialise les colis chez leur collecteur', async () => {
    const { container } = render(<ManualDispatchWorkspace accessToken="token" draft={draftFixture()} onDraftChange={vi.fn()} onFinalized={vi.fn()} onCancelled={vi.fn()} />)
    const lanes = container.querySelectorAll('.manual-lane')
    fireEvent.drop(lanes[0], { dataTransfer: { getData: () => 'pickup:r1' } })
    fireEvent.drop(lanes[1], { dataTransfer: { getData: () => 'pickup:r2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Répartir les colis' }))
    expect(screen.getByText('Colis récupérés par livreur')).toBeInTheDocument()
    expect(within(container.querySelectorAll('.manual-lane')[0] as HTMLElement).getByText('2 colis')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Vérifier le dispatch' }))
    fireEvent.click(screen.getByRole('button', { name: 'Valider le dispatch' }))
    await waitFor(() => expect(finalizeManualDraft).toHaveBeenCalledWith('draft-1', 1, 'token'))
    expect(saveManualDraft).toHaveBeenCalledWith('draft-1', 0, expect.objectContaining({ step: 'REVIEW' }), 'token')
  })

  it('affiche un transfert sans rendez-vous ou horaire inventé au siège', () => {
    let draft = assignPoint(draftFixture(), 'COLLECTION', 'r1', 'c1')
    draft = assignPoint(draft, 'COLLECTION', 'r2', 'c2')
    draft = changeManualStep(draft, 'DELIVERY')
    draft = assignPoint(draft, 'DELIVERY', 'p1', 'c2')
    render(<ManualDispatchReview draft={draft} />)
    expect(screen.getByText('Baptiste → Jean')).toBeInTheDocument()
    expect(screen.queryByText(/Rendez-vous au siège/)).not.toBeInTheDocument()
    expect(screen.queryByText(/distance/i)).not.toBeInTheDocument()
  })
})
