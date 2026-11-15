import { useEffect, useState } from 'react'
import { Banknote, RefreshCw, X } from 'lucide-react'
import { listCashBriefings, prepareCashBriefing, releaseCashBriefing, revalidateCashBriefing, type CashBriefingBatch } from '../../api/cashBriefing'
import { Button } from '../Button'
import { errorMessage, formatAriary } from './adminHelpers'
import './CashBriefingPanel.css'

export function CashBriefingPanel({ accessToken, planId, onBatchChange }: { accessToken: string; planId: string; onBatchChange: (batch: CashBriefingBatch | undefined) => void }) {
  const [batch, setBatch] = useState<CashBriefingBatch>()
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string>()
  const [reload, setReload] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setMessage(undefined); setBatch(undefined); onBatchChange(undefined)
    void listCashBriefings(accessToken, planId, controller.signal).then((batches) => {
      if (controller.signal.aborted) return
      const current = batches.find((b) => ['ACTIVE', 'STALE', 'COMPLETED'].includes(b.status))
      setBatch(current); onBatchChange(current)
    }).catch((error) => { if (!controller.signal.aborted) setMessage(errorMessage(error)) })
    return () => controller.abort()
  }, [accessToken, planId, onBatchChange, reload])

  async function action(kind: 'prepare' | 'release' | 'revalidate') {
    setBusy(true); setMessage(undefined)
    try {
      const result = kind === 'prepare' ? await prepareCashBriefing(planId, accessToken)
        : kind === 'release' ? await releaseCashBriefing(batch!.id, accessToken) : await revalidateCashBriefing(batch!.id, accessToken)
      const current = kind === 'release' ? undefined : result
      setBatch(current); onBatchChange(current)
    } catch (error) { setMessage(errorMessage(error)); onBatchChange(undefined) }
    finally { setBusy(false) }
  }
  return <section className="cash-briefing-panel" aria-label="Versements du briefing">
    <header><div><h3><Banknote aria-hidden="true" />Versements aux vendeurs</h3><p>Proposition commune à tous les livreurs, sur leur caisse déjà confirmée. Aucun versement n’est validé ici.</p></div><Button tone="admin" variant="secondary" disabled={busy} onClick={() => setReload((n) => n + 1)} aria-label="Actualiser les versements"><RefreshCw aria-hidden="true" /></Button></header>
    {message && <p role="alert" className="admin-callout admin-callout--warning">{message}</p>}
    {batch && <><p role={batch.status === 'STALE' ? 'alert' : undefined}>{batch.message}</p>{batch.transfers.length > 0 && <ul>{batch.transfers.map((transfer) => <li key={transfer.id}><span><strong>{transfer.courierName}</strong> → {transfer.sellerName}{transfer.sellerPayoutNumber && <small>MVola : {transfer.sellerPayoutNumber}</small>}</span><span><strong>{formatAriary(transfer.amountAriary)}</strong><small>{transfer.status === 'CONFIRMED' ? 'Confirmé dans Finance' : 'À confirmer dans Finance'}</small></span></li>)}</ul>}</>}
    <footer>{(!batch || batch.status === 'COMPLETED') && <Button tone="admin" disabled={busy} onClick={() => void action('prepare')}><Banknote aria-hidden="true" />Préparer les versements</Button>}{batch && ['ACTIVE', 'STALE'].includes(batch.status) && <><Button tone="admin" variant="secondary" disabled={busy} onClick={() => void action('release')}><X aria-hidden="true" />Libérer la proposition</Button>{batch.status === 'STALE' && <Button tone="admin" disabled={busy} onClick={() => void action('revalidate')}><RefreshCw aria-hidden="true" />Revalider les caisses</Button>}</>}</footer>
  </section>
}
