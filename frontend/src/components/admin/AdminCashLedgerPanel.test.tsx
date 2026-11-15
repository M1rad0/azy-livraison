import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { adjustCashAccount, createCashTransfer, getCashLedger, voidCashMovement, type CashLedger } from '../../api/cashLedger'
import { AdminCashLedgerPanel } from './AdminCashLedgerPanel'

vi.mock('../../api/cashLedger', async (original) => ({ ...await original<typeof import('../../api/cashLedger')>(), getCashLedger: vi.fn(), createCashTransfer: vi.fn(), adjustCashAccount: vi.fn(), voidCashMovement: vi.fn() }))
vi.mock('./CashBriefingFinancePanel', () => ({ CashBriefingFinancePanel: () => <section aria-label="Versements des briefings" /> }))
const ledger: CashLedger = {
  activatedAt: '2026-11-15T04:00:00Z', revision: 7,
  accounts: [
    { key: 'AZY', type: 'AZY', entityId: null, name: 'AZY', balanceAriary: 5000, reservedAriary: 0, availableAriary: 5000 },
    { key: 'COURIER:c1', type: 'COURIER', entityId: 'c1', name: 'Jean', balanceAriary: 30000, reservedAriary: 5000, availableAriary: 25000, payoutNumber: '0340011122' },
    { key: 'COURIER:c2', type: 'COURIER', entityId: 'c2', name: 'Baptiste', balanceAriary: -1000, reservedAriary: 0, availableAriary: -1000 },
    { key: 'SELLER:s1', type: 'SELLER', entityId: 's1', name: 'Boutique Soa', balanceAriary: 25000, reservedAriary: 0, availableAriary: 25000 },
  ],
  obligations: [{ id: 'ob1', reportId: 'report1', sellerAccountId: 's1', serviceDate: '2026-11-14', amountDueAriary: 25000, paidAriary: 0, remainingAriary: 25000 }],
  movements: [
    { id: 'transfer1', kind: 'TRANSFER', fromKey: 'COURIER:c1', toKey: 'SELLER:s1', amountAriary: 25000, paymentMethod: 'MVOLA', status: 'CONFIRMED', createdAt: '2026-11-15T06:00:00Z', proofAvailable: false },
    { id: 'collect1', kind: 'COLLECTION', toKey: 'COURIER:c1', amountAriary: 30000, status: 'CONFIRMED', createdAt: '2026-11-15T05:00:00Z', proofAvailable: false },
  ],
}
const getLedger = vi.mocked(getCashLedger)
async function loaded() { await screen.findByRole('heading', { name: /Argent détenu/ }) }
function selectAccount(label: string, name: string) {
  const input = screen.getByRole('combobox', { name: label })
  fireEvent.focus(input); fireEvent.change(input, { target: { value: name } })
  fireEvent.click(within(input.parentElement!).getByRole('option', { name: new RegExp(name) }))
}
describe('AdminCashLedgerPanel', () => {
  beforeEach(() => { getLedger.mockResolvedValue(structuredClone(ledger)); vi.mocked(createCashTransfer).mockResolvedValue(undefined); vi.mocked(adjustCashAccount).mockResolvedValue(undefined); vi.mocked(voidCashMovement).mockResolvedValue(undefined) })
  afterEach(() => { cleanup(); vi.clearAllMocks(); document.body.style.overflow = '' })
  it('distingue argent détenu, réservations, créances vendeurs et avances des livreurs', async () => {
    render(<AdminCashLedgerPanel accessToken="token" />); await loaded()
    expect(screen.queryByText('Les caisses ont démarré à zéro', { exact: false })).not.toBeInTheDocument()
    expect(screen.getByText('Avance personnelle : AZY doit cette somme au livreur, hors salaire.')).toBeInTheDocument()
    const jean = screen.getByRole('heading', { name: 'Jean' }).closest('article')!
    expect(within(jean).getByText('Réservé')).toBeInTheDocument()
    expect(within(jean).getByText('Disponible')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Boutique Soa' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: 'Vendeurs' }))
    expect(screen.getByText('AZY doit cette somme au vendeur.')).toBeInTheDocument()
  })
  it('filtre les personnes sans afficher un grand menu déroulant', async () => {
    render(<AdminCashLedgerPanel accessToken="token" />); await loaded()
    fireEvent.change(screen.getByRole('searchbox', { name: 'Rechercher une caisse ou une personne' }), { target: { value: '0340011122' } })
    expect(screen.getByRole('heading', { name: 'Jean' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Baptiste' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Boutique Soa' })).not.toBeInTheDocument()
  })
  it('confirme un transfert partiel entre livreurs avec la révision et une clé stable', async () => {
    render(<AdminCashLedgerPanel accessToken="token" />); await loaded()
    fireEvent.click(screen.getByRole('button', { name: 'Nouveau transfert' }))
    selectAccount('Départ — caisse ou personne', 'Jean'); selectAccount('Arrivée — caisse ou personne', 'Baptiste')
    fireEvent.change(screen.getByLabelText('Montant en ariary'), { target: { value: '1000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le transfert' }))
    await waitFor(() => expect(createCashTransfer).toHaveBeenCalledWith(expect.objectContaining({ fromKey: 'COURIER:c1', toKey: 'COURIER:c2', amountAriary: 1000, paymentMethod: 'MVOLA', expectedRevision: 7, idempotencyKey: expect.any(String) }), null, 'token'))
  })
  it('avertit un découvert mais autorise son enregistrement', async () => {
    render(<AdminCashLedgerPanel accessToken="token" />); await loaded()
    fireEvent.click(screen.getByRole('button', { name: 'Nouveau transfert' }))
    selectAccount('Arrivée — caisse ou personne', 'Boutique Soa')
    fireEvent.change(screen.getByLabelText('Montant en ariary'), { target: { value: '26000' } })
    expect(screen.getByText(/Ce transfert dépasse le disponible/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le transfert' }))
    await waitFor(() => expect(createCashTransfer).toHaveBeenCalled())
  })
  it('bloque un montant non entier et les comptes identiques sans écrire', async () => {
    render(<AdminCashLedgerPanel accessToken="token" />); await loaded()
    fireEvent.click(screen.getByRole('button', { name: 'Nouveau transfert' }))
    selectAccount('Arrivée — caisse ou personne', 'AZY')
    fireEvent.change(screen.getByLabelText('Montant en ariary'), { target: { value: '1000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le transfert' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Choisissez deux comptes distincts.')
    selectAccount('Arrivée — caisse ou personne', 'Jean')
    fireEvent.change(screen.getByLabelText('Montant en ariary'), { target: { value: '10.5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le transfert' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Indiquez un montant entier strictement positif en ariary.')
    expect(createCashTransfer).not.toHaveBeenCalled()
  })
  it('réessaie une réponse réseau incertaine avec la même clé pour éviter un double versement', async () => {
    vi.mocked(createCashTransfer).mockRejectedValueOnce(new Error('Connexion interrompue'))
    render(<AdminCashLedgerPanel accessToken="token" />); await loaded()
    fireEvent.click(screen.getByRole('button', { name: 'Nouveau transfert' }))
    selectAccount('Arrivée — caisse ou personne', 'Boutique Soa')
    fireEvent.change(screen.getByLabelText('Montant en ariary'), { target: { value: '1000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le transfert' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Connexion interrompue')
    const first = vi.mocked(createCashTransfer).mock.calls[0][0]
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le transfert' }))
    await waitFor(() => expect(createCashTransfer).toHaveBeenCalledTimes(2))
    expect(vi.mocked(createCashTransfer).mock.calls[1][0].idempotencyKey).toBe(first.idempotencyKey)
  })
  it('remet un compte à zéro uniquement avec un motif traçable', async () => {
    render(<AdminCashLedgerPanel accessToken="token" />); await loaded()
    fireEvent.click(screen.getByRole('button', { name: 'Remettre Jean à zéro' }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer la régularisation' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Le motif est obligatoire.')
    fireEvent.change(screen.getByLabelText('Motif obligatoire'), { target: { value: 'Comptage initial vérifié' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer la régularisation' }))
    await waitFor(() => expect(adjustCashAccount).toHaveBeenCalledWith(expect.objectContaining({ accountKey: 'COURIER:c1', deltaAriary: -30000, reason: 'Comptage initial vérifié', expectedRevision: 7 }), 'token'))
  })
  it('annule les transferts manuels mais pas les encaissements liés au compte rendu', async () => {
    render(<AdminCashLedgerPanel accessToken="token" />); await loaded()
    fireEvent.click(screen.getByRole('tab', { name: 'Mouvements' }))
    expect(screen.queryByRole('button', { name: 'Annuler le mouvement collect1' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Annuler le mouvement transfer1' }))
    fireEvent.change(screen.getByLabelText('Motif obligatoire'), { target: { value: 'Saisie erronée' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer l’annulation' }))
    await waitFor(() => expect(voidCashMovement).toHaveBeenCalledWith('transfer1', 'Saisie erronée', 7, 'token'))
  })
  it('affiche une erreur de chargement et permet de réessayer', async () => {
    getLedger.mockRejectedValueOnce(new Error('Service indisponible'))
    render(<AdminCashLedgerPanel accessToken="token" />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Service indisponible')
    fireEvent.click(screen.getByRole('button', { name: 'Actualiser les caisses' })); await loaded()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
  it('sépare les vues actuelles sans exposer les anciens systèmes financiers', async () => {
    render(<AdminCashLedgerPanel accessToken="token" />); await loaded()
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['Caisses', 'Vendeurs', 'Versements', 'Mouvements'])
    expect(screen.queryByRole('heading', { name: /Historique des mouvements/ })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Versements des briefings')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: 'Versements' }))
    expect(screen.getByLabelText('Versements des briefings')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /Argent détenu/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/Historique clients|Historique livreurs|démarré à zéro/)).not.toBeInTheDocument()
  })
})
