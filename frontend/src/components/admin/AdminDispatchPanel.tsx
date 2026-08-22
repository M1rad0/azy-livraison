import { useEffect, useMemo, useState } from 'react'
import {
  ArrowRightLeft,
  Bike,
  Boxes,
  CheckCircle2,
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
  createDispatchPlan,
  getDispatchPlan,
  type AdminQueue,
  type Courier,
  type DispatchPackageOutcome,
  type DispatchPlan,
  type DispatchPlanSummary,
  type OperationalSettings,
} from '../../api/admin'
import type { Location } from '../../api/csvImports'
import type { CourierRoute, RouteStop } from '../../api/optimization'
import { LocationPicker } from '../LocationPicker'
import {
  asConfirmedLocation,
  dispatchStatusLabel,
  errorMessage,
  formatAriary,
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
  selectedPackageIds: Set<string>
  onPackageSelectionChange: (packageId: string, selected: boolean) => void
  onCreated: (plan: DispatchPlan) => void
}

export function collectionMatrixUsage(
  queue: AdminQueue | undefined,
  packageIds: ReadonlySet<string>,
  courierCount: number,
) {
  const pickupCount = queue?.requests.filter((request) => request.packages.some(
    (deliveryPackage) => deliveryPackage.status === 'PENDING' && packageIds.has(deliveryPackage.id),
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
                        <div><strong>{deliveryPackage.recipientName}</strong><small><MapPin aria-hidden="true" />{locationLabel(deliveryPackage.destination)}</small></div>
                        <div><strong>{formatTime(deliveryPackage.estimatedDeliveryAt)}</strong><small>{formatAriary(deliveryPackage.mobileMoneyPaid ? 0 : deliveryPackage.amountToCollectAriary)} à encaisser</small></div>
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

export function AdminDispatchPanel({
  accessToken,
  date,
  queue,
  couriers,
  settings,
  history,
  selectedPackageIds,
  onPackageSelectionChange,
  onCreated,
}: AdminDispatchPanelProps) {
  const activeCouriers = couriers.filter((courier) => courier.active)
  const pendingPackages = useMemo(() => queue?.requests.flatMap((request) => request.packages)
    .filter((deliveryPackage) => deliveryPackage.status === 'PENDING') ?? [], [queue])
  const validSelectedIds = useMemo(() => new Set(pendingPackages
    .filter((deliveryPackage) => selectedPackageIds.has(deliveryPackage.id))
    .map((deliveryPackage) => deliveryPackage.id)), [pendingPackages, selectedPackageIds])
  const [scope, setScope] = useState<'ALL' | 'SELECTED'>(validSelectedIds.size > 0 ? 'SELECTED' : 'ALL')
  const [courierIds, setCourierIds] = useState<Set<string>>(new Set())
  const [overrideHub, setOverrideHub] = useState(false)
  const [hub, setHub] = useState<Location>({})
  const [creating, setCreating] = useState(false)
  const [loadingPlanId, setLoadingPlanId] = useState<string>()
  const [message, setMessage] = useState<string>()
  const [visiblePlan, setVisiblePlan] = useState<DispatchPlan>()

  useEffect(() => {
    setCourierIds((current) => new Set([...current].filter((id) => activeCouriers.some((courier) => courier.id === id))))
  }, [couriers])

  useEffect(() => {
    if (validSelectedIds.size === 0 && scope === 'SELECTED') setScope('ALL')
  }, [scope, validSelectedIds])

  async function create() {
    const chosenHub = overrideHub ? asConfirmedLocation(hub) : undefined
    if (courierIds.size === 0) {
      setMessage('Sélectionnez au moins un livreur pour cette journée.')
      return
    }
    if (scope === 'SELECTED' && validSelectedIds.size === 0) {
      setMessage('Sélectionnez les colis à inclure dans ce dispatch.')
      return
    }
    if (scope === 'SELECTED' && validSelectedIds.size > 99) {
      setMessage('Un dispatch peut contenir au maximum 99 colis. Réduisez la sélection.')
      return
    }
    if (scope === 'ALL' && pendingPackages.length > 99) {
      setMessage('La file dépasse 99 colis. Sélectionnez un premier lot de 99 colis maximum.')
      return
    }
    if (courierIds.size > 98) {
      setMessage('Un dispatch peut contenir au maximum 98 livreurs.')
      return
    }
    const packageIdsForRun = scope === 'SELECTED'
      ? validSelectedIds
      : new Set(pendingPackages.map((deliveryPackage) => deliveryPackage.id))
    const { pickupCount, pointCount } = collectionMatrixUsage(queue, packageIdsForRun, courierIds.size)
    if (pointCount > 100) {
      const maximumCouriers = Math.max(0, 99 - pickupCount)
      setMessage(`Ce lot contient ${pickupCount} retraits. Sélectionnez au maximum ${maximumCouriers} livreur${maximumCouriers > 1 ? 's' : ''}, ou réduisez le nombre de retraits.`)
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
      const plan = await createDispatchPlan({
        serviceDate: date,
        courierIds: [...courierIds],
        packageIds: scope === 'SELECTED' ? [...validSelectedIds] : [],
        ...(chosenHub ? { hubOverride: chosenHub } : {}),
      }, idempotencyKey(), accessToken)
      setVisiblePlan(plan)
      onCreated(plan)
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setCreating(false)
    }
  }

  async function openHistory(planId: string) {
    setLoadingPlanId(planId)
    setMessage(undefined)
    try {
      setVisiblePlan(await getDispatchPlan(planId, accessToken))
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setLoadingPlanId(undefined)
    }
  }

  return (
    <section className="admin-panel" aria-labelledby="dispatch-title">
      <header className="admin-panel__heading">
        <div>
          <p className="admin-eyebrow">Une seule stratégie · via siège</p>
          <h2 id="dispatch-title">Préparer le dispatch</h2>
          <p>Choisissez les colis et les livreurs. Le calcul organise les retraits, le rendez-vous puis les tournées.</p>
        </div>
      </header>

      {message && <div className="admin-callout admin-callout--error" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Dispatch impossible</strong><span>{message}</span></div></div>}

      <div className="admin-dispatch-layout">
        <div className="admin-dispatch-builder">
          <section className="admin-builder-step">
            <header><span>1</span><div><strong>Colis du {new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long' }).format(new Date(`${date}T12:00:00+03:00`))}</strong><small>{pendingPackages.length} colis disponible{pendingPackages.length > 1 ? 's' : ''}</small></div></header>
            <div className="admin-segmented" role="group" aria-label="Périmètre du dispatch">
              <button className={scope === 'ALL' ? 'is-active' : undefined} type="button" disabled={pendingPackages.length > 99} onClick={() => setScope('ALL')}><Boxes aria-hidden="true" />Toute la file <b>{pendingPackages.length}</b></button>
              <button className={scope === 'SELECTED' ? 'is-active' : undefined} type="button" disabled={validSelectedIds.size === 0} onClick={() => setScope('SELECTED')}><CheckCircle2 aria-hidden="true" />Sélection <b>{validSelectedIds.size}</b></button>
            </div>
            {pendingPackages.length > 99 && <p className="admin-settings-hint"><TriangleAlert aria-hidden="true" />La matrice routière accepte 99 colis par dispatch : préparez plusieurs lots.</p>}
            {scope === 'SELECTED' && (
              <div className="admin-compact-list">
                {pendingPackages.filter((deliveryPackage) => validSelectedIds.has(deliveryPackage.id)).map((deliveryPackage) => (
                  <label key={deliveryPackage.id}><input type="checkbox" checked onChange={(event) => onPackageSelectionChange(deliveryPackage.id, event.target.checked)} /><span><strong>{deliveryPackage.recipientName}</strong><small>{locationLabel(deliveryPackage.destination)}</small></span></label>
                ))}
              </div>
            )}
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
            <header><span>3</span><div><strong>Point de ralliement</strong><small>Tous les colis y passent avant leur livraison</small></div></header>
            <div className="admin-segmented" role="group" aria-label="Choix du point de ralliement">
              <button className={!overrideHub ? 'is-active' : undefined} type="button" onClick={() => setOverrideHub(false)}><Warehouse aria-hidden="true" />Point par défaut</button>
              <button className={overrideHub ? 'is-active' : undefined} type="button" onClick={() => setOverrideHub(true)}><MapPin aria-hidden="true" />Changer pour ce dispatch</button>
            </div>
            {overrideHub ? (
              <LocationPicker
                accessToken={accessToken}
                value={hub}
                title="Point de ralliement du dispatch"
                description="Choisissez le lieu où les livreurs se retrouvent pour redistribuer tous les colis."
                onChange={setHub}
              />
            ) : settings?.defaultHub ? (
              <div className="admin-location-summary"><Warehouse aria-hidden="true" /><div><small>Siège utilisé</small><strong>{locationLabel(settings.defaultHub)}</strong></div></div>
            ) : (
              <div className="admin-callout admin-callout--warning"><TriangleAlert aria-hidden="true" /><div><strong>Aucun point par défaut</strong><span>Choisissez un point pour ce dispatch ou configurez-le dans Paramètres.</span></div></div>
            )}
          </section>

          <button className="admin-button admin-button--generate" type="button" onClick={() => void create()} disabled={creating || pendingPackages.length === 0}>
            {creating ? <LoaderCircle className="admin-spin" aria-hidden="true" /> : <Sparkles aria-hidden="true" />}
            {creating ? 'Calcul et enregistrement…' : `Dispatcher ${scope === 'ALL' ? pendingPackages.length : validSelectedIds.size} colis`}
          </button>
        </div>

        <aside className="admin-history">
          <div className="admin-subheading"><History aria-hidden="true" /><div><strong>Historique du jour</strong><small>{history.length} plan{history.length > 1 ? 's' : ''} enregistré{history.length > 1 ? 's' : ''}</small></div></div>
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

      {visiblePlan && <DispatchResult plan={visiblePlan} couriers={couriers} />}
    </section>
  )
}
