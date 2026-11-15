import { useEffect, useState } from 'react'
import { Banknote, RefreshCw } from 'lucide-react'
import { confirmCashBriefingTransfer, listCashBriefings, revalidateCashBriefing, type CashBriefingBatch, type CashBriefingTransfer } from '../../api/cashBriefing'
import { Button } from '../Button'
import { ConfirmDialog } from '../ConfirmDialog'
import { PaymentProofField } from '../PaymentProofField'
import { errorMessage, formatAriary, formatDate } from './adminHelpers'
import './CashBriefingPanel.css'

export function CashBriefingFinancePanel({ accessToken, onChanged, ledgerRevision }: { accessToken: string; onChanged?: () => void; ledgerRevision?: number }) {
  const [batches, setBatches] = useState<CashBriefingBatch[]>([])
  const [reload, setReload] = useState(0)
  const [message, setMessage] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [selected, setSelected] = useState<{ batch: CashBriefingBatch; transfer: CashBriefingTransfer }>()
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const [proof, setProof] = useState<File | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    void listCashBriefings(accessToken, undefined, controller.signal).then((data) => { if (!controller.signal.aborted) setBatches(data) })
      .catch((error) => { if (!controller.signal.aborted) setMessage(errorMessage(error)) })
    return () => controller.abort()
  }, [accessToken, reload, ledgerRevision])
  async function revalidate(batch: CashBriefingBatch) {
    setBusy(true); setMessage(undefined)
    try { await revalidateCashBriefing(batch.id, accessToken); setReload((n) => n + 1); onChanged?.() }
    catch (error) { setMessage(errorMessage(error)) }
    finally { setBusy(false) }
  }
  async function confirm() {
    if (!selected) return
    setBusy(true); setMessage(undefined)
    try {
      await confirmCashBriefingTransfer(selected.transfer.id, { paymentMethod: selected.transfer.sellerPayoutOperator?.trim() || 'MVola', reference: reference.trim() || undefined, note: note.trim() || undefined, expectedLedgerRevision: selected.batch.ledgerRevision }, proof, accessToken)
      setSelected(undefined); setReload((n) => n + 1); onChanged?.()
    } catch (error) { setMessage(errorMessage(error)); setSelected(undefined); setReload((n) => n + 1) }
    finally { setBusy(false) }
  }
  return <section className="cash-briefing-panel" aria-label="Versements proposés dans les briefings"><header><div><h3><Banknote aria-hidden="true" />Versements des briefings</h3><p>Confirmez uniquement les transferts réellement effectués. Les confirmations alimentent les caisses et soldent les rapports les plus anciens.</p></div><Button tone="admin" variant="secondary" disabled={busy} onClick={() => setReload((n) => n + 1)} aria-label="Actualiser les propositions"><RefreshCw aria-hidden="true" /></Button></header>
    {message && <p role="alert" className="admin-callout admin-callout--warning">{message}</p>}
    {!batches.length && <p>Aucun versement proposé en cours.</p>}
    {batches.map((batch) => <div key={batch.id}><h4>{formatDate(batch.serviceDate)}</h4>{batch.status === 'STALE' && <div className="admin-callout admin-callout--warning"><span>{batch.message}</span><Button tone="admin" disabled={busy} onClick={() => void revalidate(batch)}>Revalider</Button></div>}<ul>{batch.transfers.map((transfer) => <li key={transfer.id}><span><strong>{transfer.courierName}</strong> → {transfer.sellerName}<small>{transfer.sellerPayoutOperator?.trim() || 'MVola'} : {transfer.sellerPayoutNumber ?? 'Numéro non renseigné'}</small><small>{transfer.sellerPayoutAccountHolder?.trim() ? `Titulaire : ${transfer.sellerPayoutAccountHolder.trim()}` : 'Titulaire non renseigné'}</small></span><strong>{formatAriary(transfer.amountAriary)}</strong>{transfer.status === 'CONFIRMED' ? <span>Confirmé</span> : <Button tone="admin" disabled={busy || batch.status !== 'ACTIVE'} onClick={() => { setSelected({ batch, transfer }); setReference(''); setNote(''); setProof(null) }}>Confirmer le versement</Button>}</li>)}</ul></div>)}
    {selected && <ConfirmDialog title="Confirmer le versement réel" confirmLabel="Confirmer le transfert réel" busy={busy} onConfirm={() => void confirm()} onClose={() => setSelected(undefined)}><div className="cash-briefing-confirm"><h4>{formatAriary(selected.transfer.amountAriary)} de {selected.transfer.courierName} à {selected.transfer.sellerName}</h4><p>{selected.transfer.sellerPayoutOperator?.trim() || 'MVola'} : {selected.transfer.sellerPayoutNumber ?? 'Numéro non renseigné'}<br />{selected.transfer.sellerPayoutAccountHolder?.trim() ? `Titulaire : ${selected.transfer.sellerPayoutAccountHolder.trim()}` : 'Titulaire non renseigné'}</p><p>Cette action enregistre un transfert réel, sans lien avec la rémunération du livreur.</p><label>Référence {selected.transfer.sellerPayoutOperator?.trim() || 'MVola'} (facultative)<input value={reference} onChange={(e) => setReference(e.target.value)} maxLength={200} disabled={busy} /></label><label>Note (facultative)<textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} disabled={busy} /></label><PaymentProofField value={proof} onChange={setProof} disabled={busy} /></div></ConfirmDialog>}
  </section>
}
