import { useCallback, useEffect, useMemo, useState } from 'react'
import { BadgeCheck, BadgePercent, Calculator, CheckCheck, LoaderCircle, RotateCcw, TriangleAlert, UsersRound } from 'lucide-react'
import {
  finalizeCourierCompensations,
  getCourierCompensations,
  type CompensationLine,
  type CourierCompensation,
} from '../../api/admin'
import { errorMessage, formatAriary, formatDateTime } from './adminHelpers'
import './AdminCourierCompensationPanel.css'

interface CompensationDraft {
  amount: string
  reason: string
}

function makeDraft(compensation: CourierCompensation): Record<string, CompensationDraft> {
  return Object.fromEntries(compensation.lines.map((line) => [line.packageId, {
    amount: String(line.finalAmountAriary ?? line.suggestedAmountAriary),
    reason: line.overrideReason ?? '',
  }]))
}

export function AdminCourierCompensationPanel({
  accessToken,
  serviceDate,
  refreshKey,
  onFinalized,
}: {
  accessToken: string
  serviceDate: string
  refreshKey?: number
  onFinalized?: () => void
}) {
  const [compensation, setCompensation] = useState<CourierCompensation>()
  const [drafts, setDrafts] = useState<Record<string, CompensationDraft>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string>()

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true)
    setMessage(undefined)
    try {
      const loaded = await getCourierCompensations(serviceDate, accessToken, signal)
      setCompensation(loaded)
      setDrafts(makeDraft(loaded))
    } catch (error) {
      if ((error as { name?: string }).name !== 'AbortError') setMessage(errorMessage(error))
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [accessToken, serviceDate])

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load, refreshKey])

  const grouped = useMemo(() => {
    const groups = new Map<string, CompensationLine[]>()
    for (const line of compensation?.lines ?? []) {
      const current = groups.get(line.courierId) ?? []
      current.push(line)
      groups.set(line.courierId, current)
    }
    return [...groups.values()]
  }, [compensation])

  const draftTotal = useMemo(() => (compensation?.lines ?? []).reduce((total, line) => {
    if (!line.eligible) return total
    const amount = Number(drafts[line.packageId]?.amount)
    return Number.isSafeInteger(amount) && amount >= 0 ? total + amount : total
  }, 0), [compensation, drafts])

  function acceptSuggestions() {
    if (!compensation || compensation.finalized) return
    setDrafts(Object.fromEntries(compensation.lines.map((line) => [line.packageId, {
      amount: String(line.suggestedAmountAriary),
      reason: '',
    }])))
    setMessage(undefined)
  }

  async function finalize() {
    if (!compensation || compensation.finalized) return
    const overrides: Array<{ packageId: string; finalAmountAriary: number; reason?: string }> = []
    for (const line of compensation.lines) {
      if (!line.eligible) continue
      const draft = drafts[line.packageId]
      const amount = Number(draft?.amount)
      if (!Number.isSafeInteger(amount) || amount < 0) {
        setMessage(`La rémunération de ${line.recipientName ?? 'ce colis'} doit être un montant entier positif ou nul.`)
        return
      }
      if (amount !== line.suggestedAmountAriary) {
        if (!draft?.reason.trim()) {
          setMessage(`Expliquez l’ajustement appliqué à ${line.recipientName ?? 'ce colis'}.`)
          return
        }
        overrides.push({ packageId: line.packageId, finalAmountAriary: amount, reason: draft.reason.trim() })
      }
    }
    setSaving(true)
    setMessage(undefined)
    try {
      const saved = await finalizeCourierCompensations(
        serviceDate,
        compensation.closureRevision,
        overrides,
        accessToken,
      )
      setCompensation(saved)
      setDrafts(makeDraft(saved))
      onFinalized?.()
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="admin-compensation" aria-labelledby="courier-compensation-title">
      <header>
        <div><p className="admin-eyebrow">Après la clôture</p><h3 id="courier-compensation-title">Rémunération des livreurs</h3><p>Validez ce qui est réellement gagné par livraison. Ces montants alimenteront ensuite le solde global de chaque livreur.</p></div>
        {compensation?.finalized
          ? <span className="admin-compensation__finalized"><BadgeCheck aria-hidden="true" />Validé{compensation.finalizedAt ? ` le ${formatDateTime(compensation.finalizedAt)}` : ''}</span>
          : <button className="admin-button admin-button--ghost" type="button" disabled={loading || saving} onClick={acceptSuggestions}><RotateCcw aria-hidden="true" />Reprendre les suggestions</button>}
      </header>

      {loading && !compensation ? (
        <div className="admin-empty"><LoaderCircle className="admin-spin" aria-hidden="true" /><strong>Calcul des parts livreurs…</strong></div>
      ) : compensation ? (
        <>
          <div className="admin-compensation__totals">
            <article><Calculator aria-hidden="true" /><div><small>Montant suggéré</small><strong>{formatAriary(compensation.totalSuggestedAriary)}</strong></div></article>
            <article><BadgePercent aria-hidden="true" /><div><small>Montant final</small><strong>{formatAriary(compensation.finalized ? compensation.totalFinalAriary : draftTotal)}</strong></div></article>
            <article><UsersRound aria-hidden="true" /><div><small>Livreurs concernés</small><strong>{grouped.length}</strong></div></article>
          </div>

          {compensation.pendingReturnCount > 0 && <div className="admin-callout admin-callout--warning"><TriangleAlert aria-hidden="true" /><div><strong>Validation en attente</strong><span>Confirmez encore {compensation.pendingReturnCount} retour{compensation.pendingReturnCount > 1 ? 's' : ''} à l’expéditeur avant de figer les rémunérations.</span></div></div>}
          {message && <div className="admin-callout admin-callout--error" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Validation impossible</strong><span>{message}</span></div></div>}

          <div className="admin-compensation__groups">
            {grouped.map((lines) => {
              const courier = lines[0]
              const total = lines.reduce((sum, line) => (
                line.eligible ? sum + (Number(drafts[line.packageId]?.amount) || 0) : sum
              ), 0)
              return (
                <article className="admin-compensation__group" key={courier.courierId}>
                  <header><div><span><UsersRound aria-hidden="true" /></span><div><small>{lines.length} livraison{lines.length > 1 ? 's' : ''}</small><strong>{courier.courierName}</strong></div></div><b>{formatAriary(total)}</b></header>
                  <div className="admin-compensation__lines">
                    {lines.map((line) => {
                      const draft = drafts[line.packageId] ?? { amount: '', reason: '' }
                      const adjusted = Number(draft.amount) !== line.suggestedAmountAriary
                      return (
                        <div className={!line.eligible ? 'is-pending' : undefined} key={line.packageId}>
                          <div><strong>{line.recipientName ?? 'Colis'}</strong><small>{line.destinationLabel ?? 'Destination non renseignée'}</small></div>
                          <div><small>Frais finaux</small><strong>{formatAriary(line.deliveryFeeAriary)}</strong></div>
                          <div><small>Fraction figée</small><strong>{line.commissionNumerator}/{line.commissionDenominator}</strong></div>
                          <div><small>Suggestion</small><strong>{formatAriary(line.suggestedAmountAriary)}</strong></div>
                          {compensation.finalized ? (
                            <div><small>Montant retenu</small><strong>{formatAriary(line.finalAmountAriary ?? 0)}</strong>{line.overrideReason && <em>{line.overrideReason}</em>}</div>
                          ) : line.eligible ? (
                            <div className="admin-compensation__edit"><label><span>Montant retenu</span><div><input type="number" min="0" step="1" value={draft.amount} onChange={(event) => setDrafts((current) => ({ ...current, [line.packageId]: { ...draft, amount: event.target.value } }))} /><b>Ar</b></div></label>{adjusted && <label><span>Motif obligatoire</span><input maxLength={1000} value={draft.reason} onChange={(event) => setDrafts((current) => ({ ...current, [line.packageId]: { ...draft, reason: event.target.value } }))} /></label>}</div>
                          ) : <div className="admin-compensation__pending"><TriangleAlert aria-hidden="true" /><span>{line.ineligibilityReason}</span></div>}
                        </div>
                      )
                    })}
                  </div>
                </article>
              )
            })}
            {grouped.length === 0 && <div className="admin-empty"><UsersRound aria-hidden="true" /><strong>Aucune rémunération pour cette journée</strong><span>Aucun colis livré ou retourné ne donne lieu à une part livreur.</span></div>}
          </div>

          {!compensation.finalized && <footer><div><small>Total à inscrire dans les soldes livreurs</small><strong>{formatAriary(draftTotal)}</strong></div><button className="admin-button" type="button" disabled={saving || compensation.pendingReturnCount > 0} onClick={() => void finalize()}><CheckCheck aria-hidden="true" />{saving ? 'Validation…' : 'Valider les rémunérations'}</button></footer>}
        </>
      ) : null}
    </section>
  )
}
