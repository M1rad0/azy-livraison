import { useCallback, useEffect, useMemo, useState } from 'react'
import { BadgeCheck, BadgePercent, Calculator, CheckCheck, Fuel, History, LoaderCircle, Pencil, Plus, RefreshCw, Route, RotateCcw, TriangleAlert, UsersRound, X } from 'lucide-react'
import {
  finalizeCourierCompensations,
  getCourierCompensations,
  recalculateExpressFuel,
  updateExpressFuel,
  type CompensationLine,
  type Courier,
  type CourierCompensation,
  type ExpressFuelCalculation,
  type ExpressFuelLine,
} from '../../api/admin'
import { errorMessage, formatAriary, formatDateTime } from './adminHelpers'
import { CourierCombobox } from './CourierCombobox'
import './AdminCourierCompensationPanel.css'

interface CompensationDraft {
  amount: string
  reason: string
}

interface FuelEditorState {
  calculation: ExpressFuelCalculation
  line?: ExpressFuelLine
}

interface FuelDraft {
  courierId: string
  distanceKm: string
  amountAriary: string
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
  couriers,
  clientAccountId,
  clientDisplayName,
  refreshKey,
  onFinalized,
}: {
  accessToken: string
  serviceDate: string
  couriers: Courier[]
  clientAccountId?: string
  clientDisplayName?: string
  refreshKey?: number
  onFinalized?: () => void
}) {
  const [compensation, setCompensation] = useState<CourierCompensation>()
  const [drafts, setDrafts] = useState<Record<string, CompensationDraft>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [fuelBusyId, setFuelBusyId] = useState<string>()
  const [fuelEditor, setFuelEditor] = useState<FuelEditorState>()
  const [fuelDraft, setFuelDraft] = useState<FuelDraft>({ courierId: '', distanceKm: '', amountAriary: '', reason: '' })
  const [message, setMessage] = useState<string>()

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true)
    setMessage(undefined)
    try {
      const loaded = await getCourierCompensations(serviceDate, accessToken, signal, clientAccountId)
      setCompensation(loaded)
      setDrafts(makeDraft(loaded))
    } catch (error) {
      if ((error as { name?: string }).name !== 'AbortError') setMessage(errorMessage(error))
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [accessToken, clientAccountId, serviceDate])

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

  const commissionDraftTotal = useMemo(() => (compensation?.lines ?? []).reduce((total, line) => {
    if (!line.eligible) return total
    const amount = Number(drafts[line.packageId]?.amount)
    return Number.isSafeInteger(amount) && amount >= 0 ? total + amount : total
  }, 0), [compensation, drafts])
  const fuelDraftTotal = compensation?.totalFinalFuelAriary ?? 0
  const combinedDraftTotal = commissionDraftTotal + fuelDraftTotal
  const fuelCalculations = compensation?.fuelCalculations ?? []
  const fuelReady = (compensation?.unresolvedFuelCalculationCount ?? 0) === 0

  function acceptSuggestions() {
    if (!compensation || compensation.finalized) return
    setDrafts(Object.fromEntries(compensation.lines.map((line) => [line.packageId, {
      amount: String(line.suggestedAmountAriary),
      reason: '',
    }])))
    setMessage(undefined)
  }

  function openFuelEditor(calculation: ExpressFuelCalculation, line?: ExpressFuelLine) {
    setFuelEditor({ calculation, line })
    setFuelDraft({
      courierId: line?.courierId ?? '',
      distanceKm: line ? String(line.finalDistanceMeters / 1_000) : '',
      amountAriary: line ? String(line.finalFuelAriary) : '',
      reason: line?.overrideReason ?? '',
    })
    setMessage(undefined)
  }

  async function recalculateFuel(calculation: ExpressFuelCalculation) {
    setFuelBusyId(calculation.requestId)
    setMessage(undefined)
    try {
      await recalculateExpressFuel(calculation.requestId, calculation.version, accessToken)
      await load()
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setFuelBusyId(undefined)
    }
  }

  async function saveFuelAdjustment() {
    if (!fuelEditor) return
    const distanceKm = fuelDraft.distanceKm.trim() ? Number(fuelDraft.distanceKm) : undefined
    const amountAriary = fuelDraft.amountAriary.trim() ? Number(fuelDraft.amountAriary) : undefined
    if (!fuelDraft.courierId) { setMessage('Choisissez le livreur qui doit recevoir ce remboursement.'); return }
    if (distanceKm == null && amountAriary == null) { setMessage('Indiquez les kilomètres réels, le montant retenu, ou les deux.'); return }
    if (distanceKm != null && (!Number.isFinite(distanceKm) || distanceKm < 0)) { setMessage('La distance réelle doit être positive ou nulle.'); return }
    if (amountAriary != null && (!Number.isSafeInteger(amountAriary) || amountAriary < 0)) { setMessage('Le montant de carburant doit être un entier positif ou nul.'); return }
    if (!fuelDraft.reason.trim()) { setMessage('Expliquez cet ajustement de carburant.'); return }
    setFuelBusyId(fuelEditor.calculation.requestId)
    setMessage(undefined)
    try {
      await updateExpressFuel(
        fuelEditor.calculation.requestId,
        fuelEditor.calculation.version,
        [{
          courierId: fuelDraft.courierId,
          finalDistanceMeters: distanceKm == null ? null : Math.round(distanceKm * 1_000),
          finalFuelAmountAriary: amountAriary ?? null,
          reason: fuelDraft.reason.trim(),
        }],
        accessToken,
      )
      setFuelEditor(undefined)
      await load()
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setFuelBusyId(undefined)
    }
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
        clientAccountId,
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

  if (compensation && !compensation.finalized && compensation.newAccrualsEnabled !== true) {
    return <section className="admin-compensation"><div className="admin-callout"><History aria-hidden="true" /><div><strong>Ancien suivi des rémunérations</strong><span>Les nouvelles commissions et les remboursements de carburant sont désactivés. Les encaissements et transferts se suivent désormais dans Finances, onglet Caisses.</span></div></div></section>
  }

  return (
    <section className="admin-compensation" aria-labelledby={`courier-compensation-title-${clientAccountId ?? 'day'}`}>
      <header>
        <div><p className="admin-eyebrow">{clientAccountId ? 'Clôture du client' : 'Après la clôture'}</p><h3 id={`courier-compensation-title-${clientAccountId ?? 'day'}`}>Rémunération des livreurs</h3><p>{clientDisplayName ? `Périmètre : ${clientDisplayName}. ` : ''}Validez séparément les parts de livraison et le carburant des demandes express. Leur somme alimentera le solde global de chaque livreur.</p></div>
        {compensation?.finalized
          ? <span className="admin-compensation__finalized"><BadgeCheck aria-hidden="true" />Validé{compensation.finalizedAt ? ` le ${formatDateTime(compensation.finalizedAt)}` : ''}</span>
          : <button className="admin-button admin-button--ghost" type="button" disabled={loading || saving} onClick={acceptSuggestions}><RotateCcw aria-hidden="true" />Reprendre les suggestions</button>}
      </header>

      {loading && !compensation ? (
        <div className="admin-empty"><LoaderCircle className="admin-spin" aria-hidden="true" /><strong>Calcul des parts livreurs…</strong></div>
      ) : compensation ? (
        <>
          <div className="admin-compensation__totals">
            <article><BadgePercent aria-hidden="true" /><div><small>Parts de livraison</small><strong>{formatAriary(compensation.finalized ? compensation.totalFinalCommissionAriary : commissionDraftTotal)}</strong></div></article>
            <article><Fuel aria-hidden="true" /><div><small>Carburant express</small><strong>{formatAriary(fuelDraftTotal)}</strong></div></article>
            <article><Calculator aria-hidden="true" /><div><small>Total à verser</small><strong>{formatAriary(compensation.finalized ? compensation.totalFinalAriary : combinedDraftTotal)}</strong></div></article>
            <article><UsersRound aria-hidden="true" /><div><small>Livreurs concernés</small><strong>{new Set([...(compensation.lines ?? []).map((line) => line.courierId), ...(compensation.fuelLines ?? []).map((line) => line.courierId).filter((id): id is string => Boolean(id))]).size}</strong></div></article>
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
            {grouped.length === 0 && <div className="admin-empty"><UsersRound aria-hidden="true" /><strong>Aucune part de livraison sur ce périmètre</strong><span>Aucun colis livré ou retourné ne donne lieu à une part livreur.</span></div>}
          </div>

          {fuelCalculations.length > 0 && (
            <section className="admin-compensation__fuel" aria-labelledby={`express-fuel-title-${clientAccountId ?? 'day'}`}>
              <header><div><p className="admin-eyebrow">Demandes express</p><h4 id={`express-fuel-title-${clientAccountId ?? 'day'}`}>Carburant</h4><p>Chaque demande est calculée séparément, avec une tournée routière par livreur réel.</p></div><strong>{formatAriary(fuelDraftTotal)}</strong></header>
              <div className="admin-fuel-requests">
                {fuelCalculations.map((calculation) => {
                  const ready = calculation.status === 'CALCULATED' || calculation.status === 'MANUAL'
                  const busy = fuelBusyId === calculation.requestId
                  return (
                    <article className={`admin-fuel-request is-${calculation.status.toLowerCase()}`} key={calculation.requestId}>
                      <header><div><span><Fuel aria-hidden="true" /></span><div><small>Demande express · {calculation.pickupLabel ?? 'Retrait non renseigné'}</small><strong>{calculation.clientDisplayName ?? 'Client'}</strong></div></div><div><span className={`admin-fuel-status${ready ? ' is-ready' : ''}`}>{calculation.status === 'CALCULATED' ? 'Calculée' : calculation.status === 'MANUAL' ? 'Ajustée' : calculation.status === 'MANUAL_INCOMPLETE' ? 'Saisie incomplète' : calculation.status === 'ROUTING_FAILED' ? 'Calcul impossible' : calculation.status === 'CONFIGURATION_MISSING' ? 'Configuration incomplète' : 'En attente'}</span><strong>{formatAriary(calculation.totalFinalFuelAriary)}</strong></div></header>
                      {calculation.failureReason && <div className="admin-callout admin-callout--warning"><TriangleAlert aria-hidden="true" /><div><strong>Le trajet routier n’a pas pu être calculé</strong><span>{calculation.failureReason}</span></div></div>}
                      <div className="admin-fuel-lines">
                        {calculation.lines.map((line) => (
                          <div key={line.courierId}>
                            <div><strong>{line.courierName}</strong><small>{line.packageIds.length} colis · {line.manual ? 'valeur ajustée' : 'calcul automatique'}</small></div>
                            <div>
                              <small>Distance retenue</small>
                              <strong>{(line.finalDistanceMeters / 1_000).toLocaleString('fr-MG', { maximumFractionDigits: 1 })} km</strong>
                              {(line.repositionDistanceMeters ?? 0) > 0 && (
                                <small>dont {(line.repositionDistanceMeters! / 1_000).toLocaleString('fr-MG', { maximumFractionDigits: 1 })} km depuis l’express précédent</small>
                              )}
                            </div>
                            <div><small>Référence</small><strong>{line.fuelReimbursementEnabled && line.fuelEfficiencyKmPerLiter != null ? `${line.fuelEfficiencyKmPerLiter} km/L` : 'Non remboursé'}</strong><small>{line.fuelPricePerLiterAriary.toLocaleString('fr-MG')} Ar/L</small></div>
                            <div><small>Suggestion</small><strong>{formatAriary(line.suggestedFuelAriary)}</strong></div>
                            <div><small>Essence retenue</small><strong>{formatAriary(line.finalFuelAriary)}</strong>{line.overrideReason && <small>{line.overrideReason}</small>}</div>
                            {!compensation.finalized && <button className="admin-icon-button" type="button" aria-label={`Ajuster le carburant de ${line.courierName}`} disabled={busy} onClick={() => openFuelEditor(calculation, line)}><Pencil aria-hidden="true" /></button>}
                          </div>
                        ))}
                        {calculation.lines.length === 0 && <p>Aucun trajet carburant automatique pour cette demande. Ajoutez une valeur manuelle uniquement si un déplacement a réellement eu lieu.</p>}
                      </div>
                      <footer><span><Route aria-hidden="true" />{calculation.returnToPickup ? 'Retour au retrait inclus' : 'Sans retour au retrait'}</span>{!compensation.finalized && <div><button className="admin-button admin-button--ghost admin-button--small" type="button" disabled={busy} onClick={() => void recalculateFuel(calculation)}><RefreshCw className={busy ? 'admin-spin' : undefined} aria-hidden="true" />Recalculer</button><button className="admin-button admin-button--ghost admin-button--small" type="button" disabled={busy} onClick={() => openFuelEditor(calculation)}><Plus aria-hidden="true" />Ajouter un remboursement</button></div>}</footer>
                    </article>
                  )
                })}
              </div>
            </section>
          )}

          {!compensation.finalized && <footer><div><small>Total à inscrire dans les soldes livreurs</small><strong>{formatAriary(combinedDraftTotal)}</strong></div><button className="admin-button" type="button" disabled={saving || compensation.pendingReturnCount > 0 || !fuelReady} onClick={() => void finalize()}><CheckCheck aria-hidden="true" />{saving ? 'Validation…' : 'Valider les rémunérations'}</button></footer>}
        </>
      ) : message ? (
        <div className="admin-callout admin-callout--error" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Chargement impossible</strong><span>{message}</span></div></div>
      ) : null}

      {fuelEditor && (
        <div className="admin-modal-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !fuelBusyId) setFuelEditor(undefined) }}>
          <section className="admin-modal admin-fuel-dialog" role="dialog" aria-modal="true" aria-labelledby={`fuel-adjustment-title-${clientAccountId ?? 'day'}`}>
            <header><div><p className="admin-eyebrow">Demande express</p><h3 id={`fuel-adjustment-title-${clientAccountId ?? 'day'}`}>Ajuster le carburant</h3><p>{fuelEditor.calculation.clientDisplayName} · {fuelEditor.calculation.pickupLabel}</p></div><button className="admin-icon-button" type="button" aria-label="Fermer" disabled={Boolean(fuelBusyId)} onClick={() => setFuelEditor(undefined)}><X aria-hidden="true" /></button></header>
            <div className="admin-fuel-dialog__body">
              <label>
                <span>Livreur bénéficiaire</span>
                {fuelEditor.line ? (
                  <div className="admin-fuel-dialog__courier-fixed"><UsersRound aria-hidden="true" /><strong>{fuelEditor.line.courierName}</strong></div>
                ) : (
                  <CourierCombobox
                    couriers={couriers}
                    value={fuelDraft.courierId}
                    ariaLabel="Rechercher le livreur bénéficiaire"
                    placeholder="Rechercher un livreur"
                    onChange={(courierId) => setFuelDraft({ ...fuelDraft, courierId })}
                  />
                )}
              </label>
              <div className="admin-form-grid">
                <label><span>Distance réelle <small>facultatif si montant saisi</small></span><div className="admin-input-unit"><input type="number" min="0" step="0.1" value={fuelDraft.distanceKm} onChange={(event) => setFuelDraft({ ...fuelDraft, distanceKm: event.target.value })} /><b>km</b></div></label>
                <label><span>Montant final <small>facultatif si distance saisie</small></span><div className="admin-input-unit"><input type="number" min="0" step="1" value={fuelDraft.amountAriary} onChange={(event) => setFuelDraft({ ...fuelDraft, amountAriary: event.target.value })} /><b>Ar</b></div></label>
              </div>
              <label><span>Motif obligatoire</span><textarea rows={3} maxLength={1000} value={fuelDraft.reason} placeholder="Ex. détour réel, colis annulé après le départ…" onChange={(event) => setFuelDraft({ ...fuelDraft, reason: event.target.value })} /></label>
            </div>
            <footer><button className="admin-button admin-button--ghost" type="button" disabled={Boolean(fuelBusyId)} onClick={() => setFuelEditor(undefined)}>Annuler</button><button className="admin-button" type="button" disabled={Boolean(fuelBusyId)} onClick={() => void saveFuelAdjustment()}>{fuelBusyId ? <LoaderCircle className="admin-spin" aria-hidden="true" /> : <Fuel aria-hidden="true" />}{fuelBusyId ? 'Enregistrement…' : 'Enregistrer le carburant'}</button></footer>
          </section>
        </div>
      )}
    </section>
  )
}
