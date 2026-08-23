import { useEffect, useMemo, useState } from 'react'
import {
  ArrowDown,
  ArrowUp,
  Bike,
  Boxes,
  LoaderCircle,
  PencilRuler,
  RefreshCw,
  Save,
  TriangleAlert,
  XCircle,
} from 'lucide-react'
import {
  cancelDispatchPlan,
  regenerateDispatchPlan,
  updateManualDispatchLayout,
  type Courier,
  type DispatchPlan,
  type ManualDispatchAssignmentInput,
} from '../../api/admin'
import { ConfirmDialog } from '../ConfirmDialog'
import { CourierCombobox } from './CourierCombobox'
import { errorMessage, locationLabel } from './adminHelpers'
import './AdminPlanControls.css'

interface AssignmentDraft extends ManualDispatchAssignmentInput {
  recipientName: string
  destinationLabel: string
  weightKg: number
}

function randomKey() {
  return globalThis.crypto?.randomUUID?.() ?? `dispatch-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function normalizeOrders(assignments: AssignmentDraft[]): AssignmentDraft[] {
  const next = assignments.map((item) => ({ ...item }))
  const courierIds = [...new Set(next.map((item) => item.distributorCourierId))]
  for (const courierId of courierIds) {
    next.filter((item) => item.distributorCourierId === courierId)
      .sort((left, right) => left.deliveryOrder - right.deliveryOrder)
      .forEach((item, index) => { item.deliveryOrder = index + 1 })
  }
  return next
}

function initialAssignments(plan: DispatchPlan): AssignmentDraft[] {
  return normalizeOrders(plan.packages.filter((item) => item.assigned && item.distributorCourierId).map((item) => ({
    packageId: item.packageId,
    distributorCourierId: item.distributorCourierId as string,
    deliveryOrder: item.deliveryOrder ?? Number.MAX_SAFE_INTEGER,
    recipientName: item.recipientName,
    destinationLabel: locationLabel(item.destination),
    weightKg: item.effectiveWeightKg,
  })))
}

function movePackage(assignments: AssignmentDraft[], packageId: string, direction: -1 | 1) {
  const current = assignments.find((item) => item.packageId === packageId)
  if (!current) return assignments
  const route = assignments
    .filter((item) => item.distributorCourierId === current.distributorCourierId)
    .sort((left, right) => left.deliveryOrder - right.deliveryOrder)
  const index = route.findIndex((item) => item.packageId === packageId)
  const target = route[index + direction]
  if (!target) return assignments
  return normalizeOrders(assignments.map((item) => {
    if (item.packageId === current.packageId) return { ...item, deliveryOrder: target.deliveryOrder }
    if (item.packageId === target.packageId) return { ...item, deliveryOrder: current.deliveryOrder }
    return item
  }))
}

export function AdminPlanControls({
  accessToken,
  plan,
  couriers,
  onChanged,
}: {
  accessToken: string
  plan: DispatchPlan
  couriers: Courier[]
  onChanged: (plan: DispatchPlan) => void
}) {
  const [editing, setEditing] = useState(false)
  const [assignments, setAssignments] = useState<AssignmentDraft[]>(() => initialAssignments(plan))
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState<'manual' | 'cancel' | 'regenerate'>()
  const [error, setError] = useState<string>()
  const [confirmingCancel, setConfirmingCancel] = useState(false)
  const [confirmingRegenerate, setConfirmingRegenerate] = useState(false)
  const [includeNewPending, setIncludeNewPending] = useState(false)
  const initialCourierIds = useMemo(() => plan.couriers?.map((item) => item.courierId)
    ?? [...new Set(plan.packages.flatMap((item) => [item.collectorCourierId, item.distributorCourierId]).filter(Boolean) as string[])], [plan.couriers, plan.packages])
  const [regenerationCourierIds, setRegenerationCourierIds] = useState<Set<string>>(new Set(initialCourierIds))
  const planCouriers = useMemo(() => initialCourierIds.map((id) => {
    const snapshot = plan.couriers?.find((courier) => courier.courierId === id)
    const current = couriers.find((courier) => courier.id === id)
    if (!snapshot && !current) return undefined
    return {
      id,
      name: snapshot?.name ?? current?.name ?? `Livreur ${id.slice(0, 6)}`,
      contact: snapshot?.contact ?? current?.phoneNumber ?? current?.contact,
      active: current?.active ?? true,
      maxWeightKg: snapshot?.maxWeightKg ?? current?.effectiveMaxWeightKg ?? 0,
    }
  }).filter((courier): courier is NonNullable<typeof courier> => Boolean(courier)), [couriers, initialCourierIds, plan.couriers])
  const groupedAssignments = useMemo(() => planCouriers.map((courier) => ({
    courier,
    packages: assignments
      .filter((item) => item.distributorCourierId === courier.id)
      .sort((left, right) => left.deliveryOrder - right.deliveryOrder),
    totalWeight: assignments
      .filter((item) => item.distributorCourierId === courier.id)
      .reduce((sum, item) => sum + item.weightKg, 0),
  })), [assignments, planCouriers])
  const overloaded = groupedAssignments.filter((group) => group.totalWeight > group.courier.maxWeightKg)
  const editable = plan.plan.editable === true
    && (plan.plan.status === 'READY' || plan.plan.status === 'READY_WITH_WARNINGS')
  const planVersion = plan.plan.version ?? 0

  useEffect(() => {
    setAssignments(initialAssignments(plan))
    setRegenerationCourierIds(new Set(initialCourierIds))
    setReason('')
    setError(undefined)
    setEditing(false)
    setConfirmingCancel(false)
    setConfirmingRegenerate(false)
    setIncludeNewPending(false)
  }, [initialCourierIds, plan, plan.plan.id, planVersion])

  function resetEditor(nextPlan = plan) {
    setAssignments(initialAssignments(nextPlan))
    setReason('')
    setError(undefined)
  }

  function assign(packageId: string, courierId: string) {
    if (!courierId) return
    setAssignments((current) => {
      const nextOrder = current.filter((item) => item.distributorCourierId === courierId).length + 1
      return normalizeOrders(current.map((item) => item.packageId === packageId
        ? { ...item, distributorCourierId: courierId, deliveryOrder: nextOrder }
        : item))
    })
  }

  async function saveManual() {
    if (!reason.trim()) {
      setError('Indiquez la raison de cette modification manuelle.')
      return
    }
    if (overloaded.length > 0) {
      setError(`Capacité dépassée pour ${overloaded.map((item) => item.courier.name).join(', ')}. Modifiez la capacité journalière ou la répartition.`)
      return
    }
    setBusy('manual')
    setError(undefined)
    try {
      const saved = await updateManualDispatchLayout(
        plan.plan.id,
        planVersion,
        reason.trim(),
        assignments.map(({ recipientName: _recipient, destinationLabel: _destination, weightKg: _weight, ...item }) => item),
        accessToken,
      )
      onChanged(saved)
      resetEditor(saved)
      setEditing(false)
    } catch (saveError) {
      setError(errorMessage(saveError))
    } finally {
      setBusy(undefined)
    }
  }

  async function cancel() {
    if (!reason.trim()) {
      setError('Indiquez la raison de l’annulation du plan.')
      return
    }
    setBusy('cancel')
    setError(undefined)
    try {
      const cancelled = await cancelDispatchPlan(plan.plan.id, planVersion, reason.trim(), accessToken)
      onChanged(cancelled)
      setConfirmingCancel(false)
      setReason('')
    } catch (cancelError) {
      setError(errorMessage(cancelError))
    } finally {
      setBusy(undefined)
    }
  }

  async function regenerate() {
    if (!reason.trim()) {
      setError('Indiquez pourquoi le plan doit être régénéré.')
      return
    }
    if (regenerationCourierIds.size === 0) {
      setError('Sélectionnez au moins un livreur.')
      return
    }
    setBusy('regenerate')
    setError(undefined)
    try {
      const regenerated = await regenerateDispatchPlan(plan.plan.id, {
        expectedVersion: planVersion,
        reason: reason.trim(),
        courierIds: [...regenerationCourierIds],
        hubOverride: plan.plan.hub,
        includeNewPending,
      }, randomKey(), accessToken)
      onChanged(regenerated)
      setConfirmingRegenerate(false)
      setReason('')
      setEditing(false)
      resetEditor(regenerated)
    } catch (regenerateError) {
      setError(errorMessage(regenerateError))
    } finally {
      setBusy(undefined)
    }
  }

  if (!editable) {
    return (
      <section className="admin-plan-controls is-locked">
        <span><TriangleAlert aria-hidden="true" /></span>
        <div><strong>Ce plan n’est plus modifiable</strong><p>{plan.plan.status === 'CANCELLED' ? 'Il a été annulé et reste disponible uniquement dans l’historique.' : plan.plan.status === 'SUPERSEDED' ? 'Une nouvelle révision a remplacé ce plan.' : 'Une situation de livraison a déjà été renseignée ou la journée est verrouillée.'}</p></div>
      </section>
    )
  }

  return (
    <section className="admin-plan-controls" aria-labelledby="plan-controls-title">
      <header>
        <div><p className="admin-eyebrow">Contrôle du plan</p><h3 id="plan-controls-title">Automatique, puis ajustable</h3><p>Le calcul reste la proposition de départ. Vous pouvez modifier les affectations et l’ordre avant tout compte rendu.</p></div>
        <div className="admin-plan-controls__actions">
          <button className="admin-button admin-button--ghost" type="button" onClick={() => { resetEditor(); setEditing((current) => !current) }}><PencilRuler aria-hidden="true" />{editing ? 'Fermer l’éditeur' : 'Modifier le plan'}</button>
          <button className="admin-button admin-button--ghost" type="button" onClick={() => { setReason(''); setError(undefined); setConfirmingRegenerate(true) }}><RefreshCw aria-hidden="true" />Régénérer</button>
          <button className="admin-button admin-button--danger-ghost" type="button" onClick={() => { setReason(''); setError(undefined); setConfirmingCancel(true) }}><XCircle aria-hidden="true" />Annuler le plan</button>
        </div>
      </header>

      {editing && (
        <div className="admin-plan-editor">
          <div className="admin-plan-editor__notice"><Boxes aria-hidden="true" /><span><strong>{assignments.length} colis à distribuer depuis le siège</strong><small>La collecte, les livreurs disponibles et le siège se changent via Régénérer afin de recalculer le rendez-vous complet.</small></span></div>
          <div className="admin-plan-routes">
            {groupedAssignments.map(({ courier, packages, totalWeight }) => (
              <article className={totalWeight > courier.maxWeightKg ? 'is-overloaded' : undefined} key={courier.id}>
                <header><span><Bike aria-hidden="true" /></span><div><strong>{courier.name}</strong><small>{packages.length} colis · {totalWeight.toLocaleString('fr-MG')} / {courier.maxWeightKg.toLocaleString('fr-MG')} kg</small></div></header>
                <div>
                  {packages.map((item, index) => (
                    <section key={item.packageId}>
                      <b>{index + 1}</b>
                      <div><strong>{item.recipientName}</strong><small>{item.destinationLabel} · {item.weightKg} kg</small></div>
                      <CourierCombobox couriers={planCouriers} value={item.distributorCourierId} inputId={`manual-courier-${item.packageId}`} ariaLabel={`Livreur pour ${item.recipientName}`} onChange={(courierId) => assign(item.packageId, courierId)} />
                      <div className="admin-plan-order-actions">
                        <button type="button" disabled={index === 0} aria-label={`Monter ${item.recipientName}`} onClick={() => setAssignments((current) => movePackage(current, item.packageId, -1))}><ArrowUp aria-hidden="true" /></button>
                        <button type="button" disabled={index === packages.length - 1} aria-label={`Descendre ${item.recipientName}`} onClick={() => setAssignments((current) => movePackage(current, item.packageId, 1))}><ArrowDown aria-hidden="true" /></button>
                      </div>
                    </section>
                  ))}
                  {packages.length === 0 && <p>Aucun colis affecté à ce livreur.</p>}
                </div>
              </article>
            ))}
          </div>
          <label className="admin-plan-reason"><span>Raison de la modification</span><textarea rows={2} maxLength={1000} value={reason} placeholder="Ex. Rééquilibrage décidé avec l’équipe" onChange={(event) => setReason(event.target.value)} /></label>
          {error && <p className="admin-form-error" role="alert">{error}</p>}
          <footer><button className="admin-button admin-button--ghost" type="button" onClick={() => { resetEditor(); setEditing(false) }}>Annuler les changements</button><button className="admin-button" type="button" disabled={busy === 'manual'} onClick={() => void saveManual()}>{busy === 'manual' ? <LoaderCircle className="admin-spin" aria-hidden="true" /> : <Save aria-hidden="true" />}{busy === 'manual' ? 'Recalcul des trajets…' : 'Enregistrer et recalculer'}</button></footer>
        </div>
      )}

      {confirmingCancel && <ConfirmDialog title="Annuler ce plan ?" confirmLabel="Annuler le plan" busy={busy === 'cancel'} danger onClose={() => { setConfirmingCancel(false); setError(undefined) }} onConfirm={() => void cancel()}><p>Les colis redeviendront disponibles pour un nouveau plan. La version actuelle restera archivée.</p><label className="admin-plan-reason"><span>Raison obligatoire</span><textarea rows={3} maxLength={1000} value={reason} onChange={(event) => setReason(event.target.value)} /></label>{error && <p className="admin-form-error" role="alert">{error}</p>}</ConfirmDialog>}

      {confirmingRegenerate && <ConfirmDialog title="Régénérer le plan ?" confirmLabel="Régénérer avec ces livreurs" busy={busy === 'regenerate'} onClose={() => { setConfirmingRegenerate(false); setError(undefined) }} onConfirm={() => void regenerate()}><div className="admin-regenerate-form"><p>L’ancien plan sera archivé. Par défaut, seuls ses colis seront repris.</p><fieldset><legend>Livreurs disponibles pour le nouveau calcul</legend>{couriers.filter((courier) => courier.active || regenerationCourierIds.has(courier.id)).map((courier) => <label key={courier.id}><input type="checkbox" checked={regenerationCourierIds.has(courier.id)} onChange={(event) => setRegenerationCourierIds((current) => { const next = new Set(current); if (event.target.checked) next.add(courier.id); else next.delete(courier.id); return next })} /><span>{courier.name}<small>{courier.effectiveMaxWeightKg} kg</small></span></label>)}</fieldset><label className="admin-regenerate-option"><input type="checkbox" checked={includeNewPending} onChange={(event) => setIncludeNewPending(event.target.checked)} /><span><strong>Inclure les nouvelles demandes admissibles</strong><small>Sinon, la régénération reprend exactement les colis de ce plan.</small></span></label><label className="admin-plan-reason"><span>Raison obligatoire</span><textarea rows={3} maxLength={1000} value={reason} onChange={(event) => setReason(event.target.value)} /></label>{error && <p className="admin-form-error" role="alert">{error}</p>}</div></ConfirmDialog>}
    </section>
  )
}
