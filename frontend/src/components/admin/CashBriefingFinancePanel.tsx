import { useCallback, useState } from 'react'
import { Banknote, RefreshCw } from 'lucide-react'
import { confirmCashBriefingTransfer, listCashBriefingTransfers, revalidateCashBriefing, type CashBriefingTransferRow } from '../../api/cashBriefing'
import { Button } from '../Button'
import { ConfirmDialog } from '../ConfirmDialog'
import { PaymentProofField } from '../PaymentProofField'
import { FinanceListFilters, FinanceListPagination, useFinanceList } from './FinanceListControls'
import { errorMessage, formatAriary, formatDate } from './adminHelpers'
import './CashBriefingPanel.css'

export function CashBriefingFinancePanel({ accessToken, onChanged, ledgerRevision }: { accessToken: string; onChanged?: () => void; ledgerRevision?: number }) {
  const [message, setMessage] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [selected, setSelected] = useState<CashBriefingTransferRow>()
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const [proof, setProof] = useState<File | null>(null)
  const loadTransfers = useCallback((filters: Parameters<typeof listCashBriefingTransfers>[1], signal: AbortSignal) => listCashBriefingTransfers(accessToken, filters, signal), [accessToken, ledgerRevision])
  const list = useFinanceList(loadTransfers)
  const batches = new Map<string, CashBriefingTransferRow[]>()
  for (const row of list.items) batches.set(row.batchId, [...(batches.get(row.batchId) ?? []), row])
  async function revalidate(batchId: string) {
    setBusy(true); setMessage(undefined)
    try { await revalidateCashBriefing(batchId, accessToken); list.refresh(); onChanged?.() }
    catch (error) { setMessage(errorMessage(error)) }
    finally { setBusy(false) }
  }
  async function confirm() {
    if (!selected) return
    setBusy(true); setMessage(undefined)
    try {
      await confirmCashBriefingTransfer(selected.transfer.id, { paymentMethod: selected.transfer.sellerPayoutOperator?.trim() || 'MVola', reference: reference.trim() || undefined, note: note.trim() || undefined, expectedLedgerRevision: selected.ledgerRevision }, proof, accessToken)
      setSelected(undefined); list.refresh(); onChanged?.()
    } catch (error) { setMessage(errorMessage(error)); setSelected(undefined); list.refresh() }
    finally { setBusy(false) }
  }
  return <section className="cash-briefing-panel" aria-label="Versements proposés dans les briefings" aria-busy={list.loading}><header><div><h3><Banknote aria-hidden="true" />Versements des briefings</h3><p>Confirmez uniquement les transferts réellement effectués. Les confirmations alimentent les caisses et soldent les rapports les plus anciens.</p></div><Button tone="admin" variant="secondary" disabled={busy || list.loading} onClick={() => { setMessage(undefined); list.refresh() }} aria-label="Actualiser les propositions"><RefreshCw aria-hidden="true" /></Button></header>
    <FinanceListFilters filters={list.filters} onChange={list.change} searchLabel="Rechercher un versement" placeholder="Livreur, vendeur, numéro ou titulaire…" />
    {message && <p role="alert" className="admin-callout admin-callout--warning">{message}</p>}
    {list.error && <p role="alert" className="admin-form-error">{list.error}{!list.invalidRange && <Button tone="admin" variant="ghost" size="small" onClick={list.refresh}>Réessayer</Button>}</p>}
    {!list.loading && !list.error && !list.items.length && <p>Aucun versement correspondant aux filtres.</p>}
    {[...batches.entries()].map(([batchId, rows]) => <div className="cash-finance-transfer" key={batchId}>
      <header><h4>{formatDate(rows[0].serviceDate)}</h4></header>
      {rows[0].batchStatus === 'STALE' && <div className="admin-callout admin-callout--warning"><span>{rows[0].batchMessage}</span><Button tone="admin" disabled={busy} onClick={() => void revalidate(batchId)}>Revalider</Button></div>}
      <ul>{rows.map((row) => { const { transfer } = row; return <li key={transfer.id}><span><strong>{transfer.courierName}</strong> → {transfer.sellerName}<small>{transfer.sellerPayoutOperator?.trim() || 'MVola'} : {transfer.sellerPayoutNumber ?? 'Numéro non renseigné'}</small><small>{transfer.sellerPayoutAccountHolder?.trim() ? `Titulaire : ${transfer.sellerPayoutAccountHolder.trim()}` : 'Titulaire non renseigné'}</small></span><strong>{formatAriary(transfer.amountAriary)}</strong>{transfer.status === 'CONFIRMED' ? <span className="cash-finance-transfer__state">Confirmé</span> : transfer.status === 'REPLACED' ? <span className="cash-finance-transfer__state">Remplacé</span> : <Button tone="admin" disabled={busy || row.batchStatus !== 'ACTIVE'} onClick={() => { setSelected(row); setReference(''); setNote(''); setProof(null) }}>Confirmer le versement</Button>}</li> })}</ul>
    </div>)}
    <FinanceListPagination label="Pagination des versements des briefings" filters={list.filters} onChange={list.change} data={list.error ? undefined : list.data} disabled={list.loading || busy || Boolean(list.error)} loading={list.loading && !list.invalidRange} />
    {selected && <ConfirmDialog title="Confirmer le versement réel" confirmLabel="Confirmer le transfert réel" busy={busy} onConfirm={() => void confirm()} onClose={() => setSelected(undefined)}><div className="cash-briefing-confirm"><h4>{formatAriary(selected.transfer.amountAriary)} de {selected.transfer.courierName} à {selected.transfer.sellerName}</h4><p>{selected.transfer.sellerPayoutOperator?.trim() || 'MVola'} : {selected.transfer.sellerPayoutNumber ?? 'Numéro non renseigné'}<br />{selected.transfer.sellerPayoutAccountHolder?.trim() ? `Titulaire : ${selected.transfer.sellerPayoutAccountHolder.trim()}` : 'Titulaire non renseigné'}</p><p>Cette action enregistre un transfert réel, sans lien avec la rémunération du livreur.</p><label>Référence {selected.transfer.sellerPayoutOperator?.trim() || 'MVola'} (facultative)<input value={reference} onChange={(e) => setReference(e.target.value)} maxLength={200} disabled={busy} /></label><label>Note (facultative)<textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} disabled={busy} /></label><PaymentProofField value={proof} onChange={setProof} disabled={busy} /></div></ConfirmDialog>}
  </section>
}
