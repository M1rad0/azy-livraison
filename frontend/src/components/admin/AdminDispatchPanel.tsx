import { useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowRightLeft,
  Banknote,
  Bike,
  Boxes,
  CalendarDays,
  Clock3,
  History,
  LoaderCircle,
  MapPin,
  PackageCheck,
  Route,
  Sparkles,
  TriangleAlert,
  Warehouse,
} from 'lucide-react'
import {
  cancelDispatchPlan,
  getDispatchPlan,
  type AdminQueue,
  type Courier,
  type DispatchPackageOutcome,
  type DispatchPlan,
  type DispatchPlanSummary,
  type OperationalSettings,
} from '../../api/admin'
import { createManualDraft, editManualPlan, getActiveManualDraft, type ManualDispatchDraft } from '../../api/manualDispatch'
import type { Location } from '../../api/csvImports'
import type { CourierRoute, RouteStop } from '../../api/optimization'
import { formatOneHourWindow } from '../../deliveryWindows'
import { LocationPicker } from '../LocationPicker'
import { Button } from '../Button'
import { AdminPlanControls } from './AdminPlanControls'
import { ManualDispatchWorkspace } from './ManualDispatchWorkspace'
import { dispatchPlaceText } from './manualDispatchPresentation'
import {
  asConfirmedLocation,
  dispatchStatusLabel,
  errorMessage,
  formatAriary,
  formatDate,
  formatDateTime,
  formatTime,
  locationLabel,
  vehicleLabel,
} from './adminHelpers'

interface AdminDispatchPanelProps {
  accessToken: string
  date: string
  queue?: AdminQueue
  couriers: Courier[]
  settings?: OperationalSettings
  history: DispatchPlanSummary[]
  busy: boolean
  onRefresh?: () => void
  onDateChange: (date: string) => void
  onCreated: (plan: DispatchPlan) => void
  onOpenPayouts?: () => void
}

export function collectionMatrixUsage(
  queue: AdminQueue | undefined,
  courierCount: number,
) {
  const pickupCount = queue?.requests.filter((request) => request.packages.some(
    (deliveryPackage) => deliveryPackage.status === 'PENDING' && !deliveryPackage.finalOutcome,
  )).length ?? 0
  return { pickupCount, pointCount: courierCount + pickupCount + 1 }
}

function idempotencyKey() {
  return globalThis.crypto?.randomUUID?.() ?? `dispatch-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function courierName(courierId: string | undefined, couriers: Courier[], plan?: DispatchPlan) {
  if (!courierId) return 'Non affecté'
  return couriers.find((courier) => courier.id === courierId)?.name
    ?? plan?.hubExecution?.manifests.find((manifest) => manifest.courierId === courierId)?.courierName
    ?? `Livreur ${courierId.slice(0, 6)}`
}

interface CollectionVisit {
  key: string
  name: string
  clientName?: string
  destination: RouteStop['destination']
  estimatedArrivalAt: string
  packages: RouteStop[]
}

function collectionVisits(route: CourierRoute): CollectionVisit[] {
  const visits: CollectionVisit[] = []
  const pickupStops = route.stops
    .filter((stop) => stop.type === 'PICKUP')
    .sort((left, right) => left.order - right.order)

  for (const stop of pickupStops) {
    const locationKey = `${stop.destination.latitude ?? ''}:${stop.destination.longitude ?? ''}`
    const key = `${stop.deliveryGroupId ?? locationKey}:${stop.estimatedArrivalAt}`
    const previous = visits.at(-1)
    if (previous?.key === key) {
      previous.packages.push(stop)
      continue
    }
    visits.push({
      key,
      name: stop.deliveryGroupName ?? stop.destination.label ?? stop.destination.destinationQuery ?? 'Point de retrait',
      clientName: stop.clientName,
      destination: stop.destination,
      estimatedArrivalAt: stop.estimatedArrivalAt,
      packages: [stop],
    })
  }
  return visits
}

export function DispatchResult({ plan, couriers }: { plan: DispatchPlan; couriers: Courier[] }) {
  const assigned = plan.packages.filter((deliveryPackage) => deliveryPackage.assigned)
  const unassigned = plan.packages.filter((deliveryPackage) => !deliveryPackage.assigned)
  const byDistributor = [...assigned.reduce<Map<string, DispatchPackageOutcome[]>>((groups, deliveryPackage) => {
    const distributorId = deliveryPackage.distributorCourierId ?? 'unknown'
    groups.set(distributorId, [...(groups.get(distributorId) ?? []), deliveryPackage])
    return groups
  }, new Map())]
  const collectionRoutes = (plan.result?.routes ?? [])
    .map((route) => ({ route, visits: collectionVisits(route) }))
    .filter(({ visits }) => visits.length > 0)

  return (
    <section className="admin-dispatch-result" aria-labelledby="dispatch-result-title">
      <header>
        <div>
          <p className="admin-eyebrow">Plan enregistré</p>
          <h3 id="dispatch-result-title">Dispatch par le point de ralliement</h3>
        </div>
        <span className={`admin-chip admin-chip--${plan.plan.status.toLowerCase()}`}>
          {dispatchStatusLabel(plan.plan.status)}
        </span>
      </header>

      {plan.plan.status === 'FAILED' ? (
        <div className="admin-callout admin-callout--error">
          <TriangleAlert aria-hidden="true" />
          <div><strong>Le dispatch n’a pas abouti</strong><span>{plan.plan.failureMessage ?? 'Réessayez après avoir vérifié la file et les livreurs.'}</span></div>
        </div>
      ) : (
        <>
          <div className="admin-result-timeline">
            <article><span><Warehouse aria-hidden="true" /></span><div><small>Rendez-vous au siège</small><strong>{formatDateTime(plan.plan.meetingAt)}</strong><p>{locationLabel(plan.plan.hub)}</p></div></article>
            <i aria-hidden="true" />
            <article><span><ArrowRightLeft aria-hidden="true" /></span><div><small>Redistribution terminée</small><strong>{formatTime(plan.plan.redistributionCompletedAt)}</strong><p>{plan.hubExecution?.packageTransfers.filter((transfer) => transfer.transferred).length ?? 0} transfert(s) entre livreurs</p></div></article>
            <i aria-hidden="true" />
            <article><span><PackageCheck aria-hidden="true" /></span><div><small>Dernière livraison prévue</small><strong>{formatTime(plan.plan.completedAt)}</strong><p>{assigned.length} colis affecté{assigned.length > 1 ? 's' : ''}</p></div></article>
          </div>

          {collectionRoutes.length > 0 && (
            <div className="admin-result-section">
              <div className="admin-subheading"><Boxes aria-hidden="true" /><div><strong>Planning des collectes avant le siège</strong><small>Ordre, lieux, heures et colis à récupérer</small></div></div>
              <div className="admin-route-groups admin-collection-routes">
                {collectionRoutes.map(({ route, visits }) => {
                  const manifest = plan.hubExecution?.manifests.find((item) => item.courierId === route.courierId)
                  return (
                  <article key={route.courierId}>
                    <header>
                      <div><span><Bike aria-hidden="true" /></span><div><small>Départ {formatTime(route.departureAt)} · {locationLabel(route.departureLocation)}</small><strong>{route.courierName}</strong></div></div>
                      <b>Siège {formatTime(manifest?.arrivedAt)}</b>
                    </header>
                    <ol>
                      {visits.map((visit, index) => (
                        <li key={visit.key}>
                          <span>{index + 1}</span>
                          <div><strong>{visit.name}</strong><small><MapPin aria-hidden="true" />{locationLabel(visit.destination)}{visit.clientName ? ` · ${visit.clientName}` : ''}</small></div>
                          <div><strong>{formatTime(visit.estimatedArrivalAt)}</strong><small>{visit.packages.map((item) => item.packageLabel).join(' · ')}</small></div>
                        </li>
                      ))}
                    </ol>
                  </article>
                  )
                })}
              </div>
            </div>
          )}

          <div className="admin-result-section">
            <div className="admin-subheading"><Route aria-hidden="true" /><div><strong>Tournées après redistribution</strong><small>Ordre prévu des livraisons depuis le siège</small></div></div>
            <div className="admin-route-groups">
              {byDistributor.map(([distributorId, packages]) => (
                <article key={distributorId}>
                  <header>
                    <div><span><Bike aria-hidden="true" /></span><div><small>Livreur</small><strong>{courierName(distributorId, couriers, plan)}</strong></div></div>
                    <b>{packages.length} colis</b>
                  </header>
                  <ol>
                    {[...packages].sort((a, b) => (a.deliveryOrder ?? 999) - (b.deliveryOrder ?? 999)).map((deliveryPackage) => (
                      <li key={deliveryPackage.packageId}>
                        <span>{deliveryPackage.deliveryOrder ?? '—'}</span>
                        <div><strong>{deliveryPackage.recipientName}</strong><small><MapPin aria-hidden="true" />{locationLabel(deliveryPackage.destination)}{deliveryPackage.pricingZoneName ? ` · ${deliveryPackage.pricingZoneName}` : ''}{formatOneHourWindow(deliveryPackage.desiredDeliveryTime, deliveryPackage.desiredDeliveryEndTime, deliveryPackage.desiredDeliveryEndsNextDay, deliveryPackage.deliveryTimePreference) ? ` · créneau ${formatOneHourWindow(deliveryPackage.desiredDeliveryTime, deliveryPackage.desiredDeliveryEndTime, deliveryPackage.desiredDeliveryEndsNextDay, deliveryPackage.deliveryTimePreference)}` : ''}</small></div>
                        <div><strong>{formatTime(deliveryPackage.estimatedDeliveryAt)}</strong><small>{formatAriary(deliveryPackage.amountToCollectAriary)} à récupérer{deliveryPackage.deliveryFeeAriary != null ? ` · dont ${formatAriary(deliveryPackage.deliveryFeeAriary)} de livraison` : ''}{deliveryPackage.timeSurchargeAriary ? ` · surcharge horaire ${formatAriary(deliveryPackage.timeSurchargeAriary)}` : ''}{deliveryPackage.mobileMoneyPaid ? ' · Mobile Money signalé' : ''}</small></div>
                      </li>
                    ))}
                  </ol>
                </article>
              ))}
              {byDistributor.length === 0 && <p className="admin-muted">Aucune tournée de livraison n’a été produite.</p>}
            </div>
          </div>

          {unassigned.length > 0 && (
            <div className="admin-callout admin-callout--warning">
              <TriangleAlert aria-hidden="true" />
              <div>
                <strong>{unassigned.length} colis non affecté{unassigned.length > 1 ? 's' : ''}</strong>
                <ul>{unassigned.map((deliveryPackage) => <li key={deliveryPackage.packageId}>{deliveryPackage.recipientName} · {deliveryPackage.unassignedMessage ?? deliveryPackage.unassignedReason}</li>)}</ul>
              </div>
            </div>
          )}
        </>
      )}
    </section>
  )
}

function ManualPlanResult({ plan, accessToken, onEdited, onChanged, onOpenPayouts }: {
  plan: DispatchPlan
  accessToken: string
  onEdited: (draft: ManualDispatchDraft) => void
  onChanged: (plan: DispatchPlan) => void
  onOpenPayouts?: () => void
}) {
  const [message, setMessage] = useState<string>()
  const [busy, setBusy] = useState(false)
  const active = plan.plan.status === 'READY' || plan.plan.status === 'READY_WITH_WARNINGS'

  async function edit() {
    if (!window.confirm('Rouvrir ce plan ? Les colis reviendront en préparation jusqu’à sa prochaine validation.')) return
    setBusy(true); setMessage(undefined)
    try { onEdited(await editManualPlan(plan.plan.id, plan.plan.version ?? 0, accessToken)) }
    catch (error) { setMessage(errorMessage(error)) }
    finally { setBusy(false) }
  }
  async function cancel() {
    if (!window.confirm('Annuler ce dispatch et remettre les colis en attente ?')) return
    setBusy(true); setMessage(undefined)
    try { onChanged(await cancelDispatchPlan(plan.plan.id, plan.plan.version ?? 0, 'Annulation du dispatch manuel', accessToken)) }
    catch (error) { setMessage(errorMessage(error)) }
    finally { setBusy(false) }
  }
  return <section className="admin-dispatch-result"><header><div><p className="admin-eyebrow">Plan manuel · {formatDate(plan.plan.serviceDate)}</p><h3>{active ? 'Dispatch validé' : 'Ancien dispatch'}</h3></div>
    <span className={`admin-chip admin-chip--${plan.plan.status.toLowerCase()}`}>{dispatchStatusLabel(plan.plan.status)}</span></header>
    {message && <p role="alert" className="admin-callout admin-callout--error">{message}</p>}
    <p className="admin-muted">{active ? 'Préparez les versements si nécessaire, puis retrouvez les consignes de chaque livreur dans Briefing.' : 'Ce plan n’est plus actif. Les versements et le briefing utilisent uniquement la répartition validée en cours.'}</p>
    <div className="admin-toolbar">
      {onOpenPayouts && active && <Button tone="admin" disabled={busy} onClick={onOpenPayouts}><Banknote aria-hidden="true" />Préparer les versements</Button>}
      {plan.plan.editable && <><Button tone="admin" variant="secondary" disabled={busy} onClick={() => void edit()}>Modifier les affectations</Button>
        <Button tone="admin" variant="secondary" disabled={busy} onClick={() => void cancel()}>Annuler le dispatch</Button></>}
    </div>
  </section>
}

export function AdminDispatchPanel({
  accessToken,
  date,
  queue,
  couriers,
  settings,
  history,
  busy,
  onRefresh,
  onDateChange,
  onCreated,
  onOpenPayouts,
}: AdminDispatchPanelProps) {
  const activeCouriers = couriers.filter((courier) => courier.active)
  const currentQueue = queue?.date === date ? queue : undefined
  const hasActiveOrCompletedPlan = history.some((plan) => (
    plan.status === 'GENERATING'
    || plan.status === 'READY'
    || plan.status === 'READY_WITH_WARNINGS'
  ))
  const pendingPackages = useMemo(() => currentQueue?.requests.flatMap((request) => request.packages)
    .filter((deliveryPackage) => deliveryPackage.status === 'PENDING' && !deliveryPackage.finalOutcome) ?? [], [currentQueue])
  const [courierIds, setCourierIds] = useState<Set<string>>(new Set())
  const [overrideHub, setOverrideHub] = useState(false)
  const [hub, setHub] = useState<Location>({})
  const [creating, setCreating] = useState(false)
  const [loadingPlanId, setLoadingPlanId] = useState<string>()
  const [message, setMessage] = useState<string>()
  const [visiblePlan, setVisiblePlan] = useState<DispatchPlan>()
  const [draft, setDraft] = useState<ManualDispatchDraft>()
  const [loadingDraft, setLoadingDraft] = useState(true)
  const [workspaceOpen, setWorkspaceOpen] = useState(true)
  const historyLoadId = useRef(0)

  useEffect(() => {
    setCourierIds((current) => new Set([...current].filter((id) => activeCouriers.some((courier) => courier.id === id))))
  }, [couriers])

  useEffect(() => {
    ++historyLoadId.current
    setVisiblePlan(undefined)
    setLoadingPlanId(undefined)
    setMessage(undefined)
    setDraft(undefined)
    setWorkspaceOpen(true)
    setLoadingDraft(true)
    const controller = new AbortController()
    getActiveManualDraft(date, accessToken, controller.signal).then((active) => {
      if (!controller.signal.aborted) setDraft(active)
    }).catch((error) => {
      if (!controller.signal.aborted) setMessage(errorMessage(error))
    }).finally(() => { if (!controller.signal.aborted) setLoadingDraft(false) })
    return () => { controller.abort(); ++historyLoadId.current }
  }, [date, accessToken])

  async function create() {
    if (busy || !currentQueue) {
      setMessage('Attendez le chargement complet de la date d’activité.')
      return
    }
    const chosenHub = overrideHub ? asConfirmedLocation(hub) : undefined
    if (courierIds.size === 0) {
      setMessage('Sélectionnez au moins un livreur pour cette journée.')
      return
    }
    if (hasActiveOrCompletedPlan) {
      setMessage('Un plan existe déjà pour cette journée. Ouvrez-le depuis l’historique.')
      return
    }
    if (overrideHub && !chosenHub) {
      setMessage('Confirmez le point de ralliement sur la carte.')
      return
    }
    if (!overrideHub && !settings?.defaultHub) {
      setMessage('Configurez un point de ralliement par défaut ou choisissez-en un pour ce dispatch.')
      return
    }

    setCreating(true)
    setMessage(undefined)
    try {
      const nextDraft = await createManualDraft({
        serviceDate: date,
        courierIds: [...courierIds],
        ...(chosenHub ? { hubOverride: chosenHub } : {}),
      }, idempotencyKey(), accessToken)
      setDraft(nextDraft)
      setWorkspaceOpen(true)
      setVisiblePlan(undefined)
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setCreating(false)
    }
  }

  async function openHistory(planId: string) {
    const loadId = ++historyLoadId.current
    setLoadingPlanId(planId)
    setMessage(undefined)
    try {
      const plan = await getDispatchPlan(planId, accessToken)
      if (loadId === historyLoadId.current) setVisiblePlan(plan)
    } catch (error) {
      if (loadId === historyLoadId.current) setMessage(errorMessage(error))
    } finally {
      if (loadId === historyLoadId.current) setLoadingPlanId(undefined)
    }
  }

  if (draft && workspaceOpen) return <ManualDispatchWorkspace key={draft.id} accessToken={accessToken} draft={draft} onDraftChange={setDraft}
    onFinalized={(plan) => { setDraft(undefined); setVisiblePlan(plan); onCreated(plan); onOpenPayouts?.() }}
    onClose={() => setWorkspaceOpen(false)}
    onCancelled={() => { setDraft(undefined); onRefresh?.() }} />

  return (
    <section className="admin-panel" aria-labelledby="dispatch-title">
      <header className="admin-panel__heading">
        <div>
          <p className="admin-eyebrow">Affectations et tournées</p>
          <h2 id="dispatch-title">Préparer le dispatch</h2>
          <p>Choisissez les livreurs, puis affectez les récupérations et les livraisons. Tous les colis normaux en attente de cette date seront inclus.</p>
        </div>
        <div className="admin-toolbar">
          <label className="admin-date-field">
            <CalendarDays aria-hidden="true" />
            <span>Date d’activité</span>
            <input type="date" value={date} required onChange={(event) => {
              if (event.target.value) onDateChange(event.target.value)
            }} />
          </label>
        </div>
      </header>

      {message && <div className="admin-callout admin-callout--error" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Dispatch impossible</strong><span>{message}</span></div></div>}

      {draft && <div className="admin-callout"><div><strong>Un brouillon est enregistré pour cette date</strong><span>{draft.packages.length} colis · {draft.couriers.length} livreurs</span></div>
        <button type="button" className="admin-button" onClick={() => setWorkspaceOpen(true)}>Reprendre le dispatch</button></div>}

      <div className="admin-dispatch-layout">
        {!draft && <div className="admin-dispatch-builder">
          <section className="admin-builder-step">
            <header><span>1</span><div><strong>File automatique du {new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long' }).format(new Date(`${date}T12:00:00+03:00`))}</strong><small>{busy ? 'Chargement de la file…' : `${pendingPackages.length} colis inclus selon leur ordre d’arrivée`}</small></div></header>
            <div className="admin-location-summary"><Boxes aria-hidden="true" /><div><small>Périmètre du plan</small><strong>Toute la file admissible · {pendingPackages.length} colis</strong></div></div>
            {hasActiveOrCompletedPlan && <p className="admin-settings-hint"><PackageCheck aria-hidden="true" />Le plan unique de cette date est déjà enregistré.</p>}
            {!hasActiveOrCompletedPlan && history.some((plan) => plan.status === 'FAILED') && <p className="admin-settings-hint admin-settings-hint--warning"><TriangleAlert aria-hidden="true" />Le dernier calcul a échoué. Vous pouvez relancer le dispatch.</p>}
          </section>

          <section className="admin-builder-step">
            <header><span>2</span><div><strong>Livreurs en service</strong><small>Seuls les profils actifs sont proposés</small></div></header>
            <div className="admin-courier-selection">
              {activeCouriers.map((courier) => (
                <label className={courierIds.has(courier.id) ? 'is-selected' : undefined} key={courier.id}>
                  <input
                    type="checkbox"
                    checked={courierIds.has(courier.id)}
                    onChange={(event) => setCourierIds((current) => {
                      const next = new Set(current)
                      if (event.target.checked) next.add(courier.id)
                      else next.delete(courier.id)
                      return next
                    })}
                  />
                  <span><Bike aria-hidden="true" /></span>
                  <div><strong>{courier.name}</strong><small>{vehicleLabel(courier.vehicleType)} · {courier.effectiveMaxWeightKg} kg · départ {courier.defaultDepartureTime.slice(0, 5)}</small></div>
                </label>
              ))}
              {activeCouriers.length === 0 && <p className="admin-muted">Aucun livreur actif. Créez ou réactivez un profil dans l’onglet Livreurs.</p>}
            </div>
          </section>

          <section className="admin-builder-step">
            <header><span>3</span><div><strong>Point de ralliement de référence</strong><small>Repère pour les échanges, sans passage imposé</small></div></header>
            <div className="admin-segmented" role="group" aria-label="Choix du point de ralliement">
              <button className={!overrideHub ? 'is-active' : undefined} type="button" onClick={() => setOverrideHub(false)}><Warehouse aria-hidden="true" />Point par défaut</button>
              <button className={overrideHub ? 'is-active' : undefined} type="button" onClick={() => setOverrideHub(true)}><MapPin aria-hidden="true" />Changer pour ce dispatch</button>
            </div>
            {overrideHub ? (
              <LocationPicker
                accessToken={accessToken}
                value={hub}
                title="Point de ralliement du dispatch"
                description="Choisissez un repère pour cette journée. Les échanges seront indiqués sans imposer leur lieu."
                onChange={setHub}
              />
            ) : settings?.defaultHub ? (
              <div className="admin-location-summary"><Warehouse aria-hidden="true" /><div><small>Ralliement de référence</small><strong>{dispatchPlaceText(settings.defaultHub)}</strong></div></div>
            ) : (
              <div className="admin-callout admin-callout--warning"><TriangleAlert aria-hidden="true" /><div><strong>Aucun point par défaut</strong><span>Choisissez un point pour ce dispatch ou configurez-le dans Paramètres.</span></div></div>
            )}
          </section>

          <button className="admin-button admin-button--generate" type="button" onClick={() => void create()} disabled={creating || loadingDraft || busy || !currentQueue || pendingPackages.length === 0 || hasActiveOrCompletedPlan}>
            {creating ? <LoaderCircle className="admin-spin" aria-hidden="true" /> : <Sparkles aria-hidden="true" />}
            {loadingDraft ? 'Recherche d’un brouillon…' : creating ? 'Préparation du brouillon…' : `Organiser ${pendingPackages.length} colis`}
          </button>
        </div>}

        <aside className="admin-history">
          <div className="admin-subheading"><History aria-hidden="true" /><div><strong>Historique du {formatDate(date)}</strong><small>{history.length} plan{history.length > 1 ? 's' : ''} enregistré{history.length > 1 ? 's' : ''}</small></div></div>
          <div className="admin-history-list">
            {history.map((plan) => (
              <button type="button" key={plan.id} className={visiblePlan?.plan.id === plan.id ? 'is-active' : undefined} onClick={() => void openHistory(plan.id)} disabled={loadingPlanId === plan.id}>
                <span className={`admin-history-status is-${plan.status.toLowerCase()}`}><i />{dispatchStatusLabel(plan.status)}</span>
                <strong>{plan.assignedPackageCount ?? 0} / {plan.selectedPackageCount} colis affectés</strong>
                <small><Clock3 aria-hidden="true" />Créé à {formatTime(plan.createdAt)}</small>
                {loadingPlanId === plan.id && <LoaderCircle className="admin-spin" aria-hidden="true" />}
              </button>
            ))}
            {history.length === 0 && <p className="admin-muted">Aucun dispatch enregistré pour cette date.</p>}
          </div>
        </aside>
      </div>

      {visiblePlan && visiblePlan.plan.strategy === 'MANUAL' ? <ManualPlanResult key={visiblePlan.plan.id} plan={visiblePlan} accessToken={accessToken} onOpenPayouts={onOpenPayouts} onEdited={(nextDraft) => { setDraft(nextDraft); setWorkspaceOpen(true); setVisiblePlan(undefined); onRefresh?.() }} onChanged={(plan) => { setVisiblePlan(plan); onCreated(plan) }} /> : visiblePlan && (
        <>
          <DispatchResult plan={visiblePlan} couriers={couriers} />
          <AdminPlanControls
            accessToken={accessToken}
            plan={visiblePlan}
            couriers={couriers}
            onChanged={(nextPlan) => {
              setVisiblePlan(nextPlan)
              onCreated(nextPlan)
            }}
          />
        </>
      )}
    </section>
  )
}
