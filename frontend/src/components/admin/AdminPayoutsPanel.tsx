import { useEffect, useState } from 'react'
import { ArrowRight, Banknote, CalendarDays, LoaderCircle, RefreshCw, TriangleAlert } from 'lucide-react'
import { getDispatchPlan, type DispatchPlanSummary } from '../../api/admin'
import { Button } from '../Button'
import { CashBriefingPanel } from './CashBriefingPanel'
import { errorMessage, formatDate } from './adminHelpers'

interface AdminPayoutsPanelProps {
  accessToken: string
  date: string
  history: DispatchPlanSummary[]
  busy?: boolean
  onDateChange: (date: string) => void
  onOpenDispatch: () => void
  onOpenBriefing: () => void
  onRefresh?: () => void
}

/** Optional money instructions between the validated dispatch and courier messages. */
export function AdminPayoutsPanel({ accessToken, date, history, busy = false, onDateChange, onOpenDispatch, onOpenBriefing, onRefresh }: AdminPayoutsPanelProps) {
  const [readyPlan, setReadyPlan] = useState<DispatchPlanSummary>()
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState<string>()
  const [reload, setReload] = useState(0)
  const [dirty, setDirty] = useState(false)
  const [blocked, setBlocked] = useState(true)
  const active = [...history]
    .filter((plan) => plan.serviceDate === date && !plan.archivedAt && ['READY', 'READY_WITH_WARNINGS'].includes(plan.status))
    .sort((left, right) => (right.revision ?? 0) - (left.revision ?? 0) || right.createdAt.localeCompare(left.createdAt))[0]
  const planId = active?.id
  const version = active?.version

  useEffect(() => {
    const controller = new AbortController()
    setReadyPlan(undefined); setMessage(undefined); setDirty(false); setBlocked(true)
    if (!planId) { setLoading(false); return () => controller.abort() }
    setLoading(true)
    void getDispatchPlan(planId, accessToken, controller.signal).then((detail) => {
      if (controller.signal.aborted) return
      const plan = detail.plan
      if (plan.id !== planId || plan.serviceDate !== date || plan.archivedAt || !['READY', 'READY_WITH_WARNINGS'].includes(plan.status)) {
        throw new Error('Ce dispatch n’est plus actif. Actualisez la journée avant de préparer ses versements.')
      }
      if (plan.strategy !== 'MANUAL') throw new Error('Ce plan ancien ne possède pas de répartition manuelle. Consultez ses détails dans Dispatch.')
      setReadyPlan(plan)
    }).catch((error: unknown) => { if (!controller.signal.aborted) setMessage(errorMessage(error)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [accessToken, date, planId, version, reload])

  const ready = !busy && !loading && readyPlan?.id === planId ? readyPlan : undefined
  return <section className="admin-panel" aria-labelledby="payouts-title">
    <header className="admin-panel__heading">
      <div><p className="admin-eyebrow">Avant les consignes des livreurs</p><h2 id="payouts-title">Versements</h2><p>Préparez les versements aux vendeurs si nécessaire, puis continuez vers le briefing. Cette étape ne confirme aucun paiement.</p></div>
      <div className="admin-toolbar"><label className="admin-date-field"><CalendarDays aria-hidden="true" /><span>Date d’activité</span><input type="date" required disabled={busy || loading || dirty} value={date} onChange={(event) => { if (event.target.value && !dirty) onDateChange(event.target.value) }} /></label><Button tone="admin" variant="secondary" disabled={busy || loading || dirty} onClick={() => { if (dirty) return; setReload((value) => value + 1); onRefresh?.() }}><RefreshCw aria-hidden="true" />Actualiser</Button></div>
    </header>
    {busy || loading ? <div className="admin-empty" role="status"><LoaderCircle className="admin-spin" aria-hidden="true" /><strong>Chargement des versements…</strong></div>
      : message ? <div className="admin-callout admin-callout--warning" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Versements indisponibles</strong><span>{message}</span><Button tone="admin" variant="secondary" size="small" onClick={onOpenDispatch}>Ouvrir Dispatch</Button></div></div>
        : ready ? <>
          <CashBriefingPanel key={`${ready.id}:${ready.version ?? 0}:${reload}`} accessToken={accessToken} planId={ready.id} onDirtyChange={setDirty} onBlockedChange={setBlocked} />
          {dirty && <p id="payouts-draft-notice" role="status" className="admin-muted">Enregistrez les modifications des versements avant de continuer, de changer la date ou d’actualiser.</p>}
          <footer className="admin-toolbar" style={{ justifyContent: 'flex-end' }}><Button tone="admin" disabled={dirty || blocked} aria-describedby={dirty ? 'payouts-draft-notice' : undefined} onClick={onOpenBriefing}>Continuer vers le briefing<ArrowRight aria-hidden="true" /></Button></footer>
        </> : <div className="admin-empty"><Banknote aria-hidden="true" /><strong>Aucun dispatch validé pour le {formatDate(date)}</strong><span>Validez les affectations de cette journée avant de préparer ses versements.</span><Button tone="admin" onClick={onOpenDispatch}>Ouvrir Dispatch<ArrowRight aria-hidden="true" /></Button></div>}
  </section>
}
