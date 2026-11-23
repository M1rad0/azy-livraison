import { useCallback, useState } from 'react'
import { ArrowRightLeft, Banknote, RefreshCw } from 'lucide-react'
import { confirmCashBriefingCourierTransfer, confirmCashBriefingTransfer, listCashBriefingTransfers, revalidateCashBriefing, type CashBriefingTransferRow } from '../../api/cashBriefing'
import { Button } from '../Button'
import { ConfirmDialog } from '../ConfirmDialog'
import { PaymentProofField } from '../PaymentProofField'
import { FinanceListFilters, FinanceListPagination, useFinanceList } from './FinanceListControls'
import { errorMessage, formatAriary, formatDate } from './adminHelpers'
import './CashBriefingPanel.css'

function instruction(row: CashBriefingTransferRow) {
  if (row.kind === 'COURIER_TRANSFER') {
    const transfer = row.courierTransfer
    return { id: transfer.id, from: transfer.fromCourierName, to: transfer.toCourierName,
      amount: transfer.amountAriary, status: transfer.status, ready: transfer.readyToConfirm !== false,
      blockedReason: transfer.blockedReason, operator: undefined, number: undefined, holder: undefined }
  }
  const transfer = row.transfer
  return { id: transfer.id, from: transfer.courierName, to: transfer.sellerName,
    amount: transfer.amountAriary, status: transfer.status, ready: transfer.readyToConfirm !== false,
    blockedReason: transfer.blockedReason, operator: transfer.sellerPayoutOperator?.trim() || 'MVola',
    number: transfer.sellerPayoutNumber, holder: transfer.sellerPayoutAccountHolder }
}

export function CashBriefingFinancePanel({ accessToken, onChanged, ledgerRevision }: { accessToken: string; onChanged?: () => void; ledgerRevision?: number }) {
  const [message, setMessage] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [selected, setSelected] = useState<CashBriefingTransferRow>()
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const [method, setMethod] = useState('Espèces')
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
    const details = instruction(selected)
    if (!details.ready || details.status !== 'PENDING' || selected.batchStatus !== 'ACTIVE') return
    setBusy(true); setMessage(undefined)
    try {
      const confirmTransfer = selected.kind === 'COURIER_TRANSFER' ? confirmCashBriefingCourierTransfer : confirmCashBriefingTransfer
      await confirmTransfer(details.id, { paymentMethod: selected.kind === 'COURIER_TRANSFER' ? method : details.operator || 'MVola', reference: reference.trim() || undefined, note: note.trim() || undefined, expectedLedgerRevision: selected.ledgerRevision }, proof, accessToken)
      setSelected(undefined); list.refresh(); onChanged?.()
    } catch (error) { setMessage(errorMessage(error)); setSelected(undefined); list.refresh() }
    finally { setBusy(false) }
  }
  const selectedDetails = selected ? instruction(selected) : undefined
  return <section className="cash-briefing-panel" aria-label="Versements proposés dans les briefings" aria-busy={list.loading}><header><div><h3><Banknote aria-hidden="true" />Versements des briefings</h3><p>Confirmez les transferts entre livreurs, puis les versements aux vendeurs une fois les fonds reçus. Enregistrez uniquement les opérations réellement effectuées.</p></div><Button tone="admin" variant="secondary" disabled={busy || list.loading} onClick={() => { setMessage(undefined); list.refresh() }} aria-label="Actualiser les propositions"><RefreshCw aria-hidden="true" /></Button></header>
    <FinanceListFilters filters={list.filters} onChange={list.change} searchLabel="Rechercher un versement" placeholder="Livreur, vendeur, numéro ou titulaire…" />
    {message && <p role="alert" className="admin-callout admin-callout--warning">{message}</p>}
    {list.error && <p role="alert" className="admin-form-error">{list.error}{!list.invalidRange && <Button tone="admin" variant="ghost" size="small" onClick={list.refresh}>Réessayer</Button>}</p>}
    {!list.loading && !list.error && !list.items.length && <p>Aucun versement correspondant aux filtres.</p>}
    {[...batches.entries()].map(([batchId, rows]) => <div className="cash-finance-transfer" key={batchId}>
      <header><h4>{formatDate(rows[0].serviceDate)}</h4></header>
      {rows[0].batchStatus === 'STALE' && <div className="admin-callout admin-callout--warning"><span>{rows[0].batchMessage}</span><Button tone="admin" disabled={busy} onClick={() => void revalidate(batchId)}>Revalider</Button></div>}
      <ul>{rows.map((row) => {
        const details = instruction(row)
        const courierTransfer = row.kind === 'COURIER_TRANSFER'
        return <li key={`${row.kind ?? 'SELLER_PAYOUT'}:${details.id}`}>
          <span><small>{courierTransfer ? 'Transfert entre livreurs' : 'Versement vendeur'}</small><strong>{details.from}</strong> → {details.to}
            {!courierTransfer && <><small>{details.operator} : {details.number ?? 'Numéro non renseigné'}</small><small>{details.holder?.trim() ? `Titulaire : ${details.holder.trim()}` : 'Titulaire non renseigné'}</small></>}
            {details.status === 'PENDING' && !details.ready && row.batchStatus === 'ACTIVE' && <small>{details.blockedReason || 'En attente des fonds à recevoir par le livreur.'}</small>}
          </span>
          <strong>{formatAriary(details.amount)}</strong>
          {details.status === 'CONFIRMED' ? <span className="cash-finance-transfer__state">Confirmé</span> : details.status === 'VOIDED' ? <span className="cash-finance-transfer__state">Annulé</span> : details.status === 'REPLACED' ? <span className="cash-finance-transfer__state">Remplacé</span>
            : <Button tone="admin" disabled={busy || row.batchStatus !== 'ACTIVE' || !details.ready} onClick={() => { setSelected(row); setMethod('Espèces'); setReference(''); setNote(''); setProof(null) }}>{courierTransfer && <ArrowRightLeft aria-hidden="true" />}{courierTransfer ? 'Confirmer le transfert entre livreurs' : 'Confirmer le versement'}</Button>}
        </li>
      })}</ul>
    </div>)}
    <FinanceListPagination label="Pagination des versements des briefings" filters={list.filters} onChange={list.change} data={list.error ? undefined : list.data} disabled={list.loading || busy || Boolean(list.error)} loading={list.loading && !list.invalidRange} />
    {selected && selectedDetails && <ConfirmDialog title={selected.kind === 'COURIER_TRANSFER' ? 'Confirmer le transfert entre livreurs' : 'Confirmer le versement réel'} confirmLabel="Confirmer le transfert réel" busy={busy} onConfirm={() => void confirm()} onClose={() => setSelected(undefined)}>
      <div className="cash-briefing-confirm">
        <h4>{formatAriary(selectedDetails.amount)} de {selectedDetails.from} à {selectedDetails.to}</h4>
        {selected.kind === 'COURIER_TRANSFER'
          ? <><p>Le montant sera retiré de la caisse de {selectedDetails.from} et ajouté à celle de {selectedDetails.to}.</p><label>Moyen de transfert<select value={method} onChange={(event) => setMethod(event.target.value)} disabled={busy}>{['Espèces', 'MVola', 'Orange Money', 'Airtel Money'].map((value) => <option key={value}>{value}</option>)}</select></label></>
          : <p>{selectedDetails.operator} : {selectedDetails.number ?? 'Numéro non renseigné'}<br />{selectedDetails.holder?.trim() ? `Titulaire : ${selectedDetails.holder.trim()}` : 'Titulaire non renseigné'}</p>}
        <label>Référence {selected.kind === 'COURIER_TRANSFER' ? 'du transfert' : selectedDetails.operator} (facultative)<input value={reference} onChange={(e) => setReference(e.target.value)} maxLength={200} disabled={busy} /></label>
        <label>Note (facultative)<textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} disabled={busy} /></label>
        <PaymentProofField value={proof} onChange={setProof} disabled={busy} />
      </div>
    </ConfirmDialog>}
  </section>
}
