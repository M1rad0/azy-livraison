import { useEffect, useMemo, useState, type CSSProperties } from 'react'
import type { GeoJsonObject } from 'geojson'
import {
  ArrowLeft,
  FileSpreadsheet,
  Bike,
  CheckCircle2,
  Download,
  BarChart3,
  ListChecks,
  LoaderCircle,
  MapPin,
  PackageCheck,
  PackageX,
  Printer,
  Route as RouteIcon,
  Scale,
  Smartphone,
  Sparkles,
  TriangleAlert,
} from 'lucide-react'
import { CircleMarker, GeoJSON, MapContainer, Popup, TileLayer, Tooltip, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import type { OptimizationResult, CourierRoute, RouteStop, UnassignedReason } from '../api/optimization'
import type { DeliveryPlan } from '../api/plans'
import { calculateRoute, type RoutingRouteResponse } from '../api/routing'

const TILE_URL = import.meta.env.VITE_MAP_TILE_URL
  ?? 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const TILE_ATTRIBUTION = import.meta.env.VITE_MAP_TILE_ATTRIBUTION
  ?? '&copy; OpenStreetMap contributors'
const ROUTE_COLORS = ['#f45b0b', '#0b3557', '#ff8a3d', '#2b648d', '#c94400', '#547f9e', '#f6a15e', '#7897ad']

interface DrawnRoute {
  courierRoute: CourierRoute
  color: string
  geometry?: RoutingRouteResponse
  error?: string
}

function hasCoordinates(location: { latitude?: number; longitude?: number }) {
  return Number.isFinite(location.latitude) && Number.isFinite(location.longitude)
}

function isDeliveryStop(_stop: RouteStop) {
  return true
}

function formatDuration(seconds: number) {
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  const remainingMinutes = minutes % 60
  return `${Math.floor(minutes / 60)} h${remainingMinutes ? ` ${String(remainingMinutes).padStart(2, '0')}` : ''}`
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat('fr-MG', {
    timeZone: 'Indian/Antananarivo',
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('fr-MG', {
    timeZone: 'Indian/Antananarivo',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function formatLocation(location: { label?: string; destinationQuery?: string; latitude?: number; longitude?: number }) {
  if (location.label) return location.label
  if (location.destinationQuery) return location.destinationQuery
  if (hasCoordinates(location)) return `${location.latitude?.toFixed(5)}, ${location.longitude?.toFixed(5)}`
  return 'Destination confirmée'
}

function csvCell(value: string | number | undefined) {
  return `"${String(value ?? '').replaceAll('"', '""')}"`
}

function unassignedTitle(reason: UnassignedReason) {
  const titles: Record<UnassignedReason, string> = {
    DESTINATION_UNRESOLVED: 'Destination à confirmer',
    NO_ROAD_ROUTE: 'Route introuvable',
    OVERWEIGHT_FOR_ALL_COURIERS: 'Colis trop lourd',
    INSUFFICIENT_TOTAL_CAPACITY: 'Capacité totale insuffisante',
    TIME_WINDOW_UNSATISFIABLE: 'Créneau impossible',
    SOLVER_TIMEOUT: 'Calcul interrompu',
    NO_FEASIBLE_ASSIGNMENT: 'Affectation impossible',
  }
  return titles[reason]
}

function FitResultBounds({ routes, depot }: { routes: DrawnRoute[]; depot?: [number, number] }) {
  const map = useMap()

  useEffect(() => {
    const points: [number, number][] = []
    if (depot) points.push(depot)
    routes.forEach((route) => {
      route.geometry?.geometry.coordinates.forEach(([longitude, latitude]) => points.push([latitude, longitude]))
      route.courierRoute.stops.forEach((stop) => {
        if (hasCoordinates(stop.destination)) {
          points.push([stop.destination.latitude as number, stop.destination.longitude as number])
        }
      })
    })
    if (points.length > 0) map.fitBounds(points, { padding: [44, 44], maxZoom: 16 })
  }, [depot, map, routes])

  return null
}

function ResultMap({ result, plan, accessToken }: {
  result: OptimizationResult
  plan: DeliveryPlan
  accessToken: string
}) {
  const [drawnRoutes, setDrawnRoutes] = useState<DrawnRoute[]>([])
  const [loading, setLoading] = useState(false)
  const depot = hasCoordinates(plan.depot)
    ? [plan.depot.latitude as number, plan.depot.longitude as number] as [number, number]
    : undefined

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setLoading(true)
    void Promise.all(result.routes.map(async (courierRoute, index): Promise<DrawnRoute> => {
      const color = ROUTE_COLORS[index % ROUTE_COLORS.length]
      const plannedCourier = plan.couriers.find((courier) => courier.id === courierRoute.courierId)
      const customDeparture = plannedCourier?.departureLocation
      const points = [
        ...(customDeparture && hasCoordinates(customDeparture) ? [{
          id: `start-${courierRoute.courierId}`,
          latitude: customDeparture.latitude as number,
          longitude: customDeparture.longitude as number,
        }] : []),
        ...(depot ? [{
          id: `pickup-${courierRoute.courierId}`,
          latitude: depot[0],
          longitude: depot[1],
        }] : []),
        ...courierRoute.stops.flatMap((stop) => hasCoordinates(stop.destination) ? [{
          id: `${courierRoute.courierId}-${stop.packageId}`,
          latitude: stop.destination.latitude as number,
          longitude: stop.destination.longitude as number,
        }] : []),
        ...(plan.settings.returnToDepot && depot ? [{
          id: `return-${courierRoute.courierId}`,
          latitude: depot[0],
          longitude: depot[1],
        }] : []),
      ]
      try {
        return {
          courierRoute,
          color,
          geometry: await calculateRoute(points, accessToken, controller.signal),
        }
      } catch (error) {
        return {
          courierRoute,
          color,
          error: error instanceof Error ? error.message : 'Tracé indisponible.',
        }
      }
    })).then((routes) => {
      if (active) setDrawnRoutes(routes)
    }).finally(() => {
      if (active) setLoading(false)
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [accessToken, depot?.[0], depot?.[1], plan.couriers, plan.settings.returnToDepot, result])

  return (
    <section className="result-map-card" aria-labelledby="result-map-title">
      <div className="result-section-heading">
        <div><span><MapPin aria-hidden="true" /></span><div><p className="eyebrow">Lecture géographique</p><h2 id="result-map-title">Carte des tournées</h2></div></div>
        <small>Une couleur par livreur</small>
      </div>
      <div className="result-map-layout">
        <div className="result-map" data-testid="optimization-result-map">
          <MapContainer center={[-18.895, 47.525]} zoom={13} scrollWheelZoom>
            <TileLayer url={TILE_URL} attribution={TILE_ATTRIBUTION} />
            <FitResultBounds routes={drawnRoutes} depot={depot} />
            {depot && (
              <CircleMarker center={depot} radius={11} pathOptions={{ color: '#fff', fillColor: '#0b3557', fillOpacity: 1, weight: 4 }}>
                <Tooltip permanent direction="top">Retrait</Tooltip>
                <Popup><strong>{plan.depot.label || 'Point de récupération'}</strong></Popup>
              </CircleMarker>
            )}
            {drawnRoutes.map((route) => route.geometry && (
              <GeoJSON
                key={route.courierRoute.courierId}
                data={route.geometry.geometry as GeoJsonObject}
                style={{ color: route.color, weight: 6, opacity: 0.82 }}
              />
            ))}
            {drawnRoutes.flatMap((route) => route.courierRoute.stops.map((stop) => hasCoordinates(stop.destination) && (
              <CircleMarker
                key={`${route.courierRoute.courierId}-${stop.packageId}-${stop.order}`}
                center={[stop.destination.latitude as number, stop.destination.longitude as number]}
                radius={8}
                pathOptions={{ color: '#fff', fillColor: route.color, fillOpacity: 1, weight: 3 }}
              >
                <Tooltip permanent direction="top">{stop.order}</Tooltip>
                <Popup><strong>{stop.packageLabel}</strong><br />{route.courierRoute.courierName} · {formatDateTime(stop.estimatedArrivalAt)}</Popup>
              </CircleMarker>
            ))) }
          </MapContainer>
          {loading && <div className="result-map-loading"><LoaderCircle className="spin" aria-hidden="true" />Construction des tracés…</div>}
        </div>
        <aside className="result-map-legend">
          {drawnRoutes.map((route) => (
            <article key={route.courierRoute.courierId}>
              <i style={{ backgroundColor: route.color }} />
              <div>
                <strong>{route.courierRoute.courierName}</strong>
                <span>{route.courierRoute.stops.filter(isDeliveryStop).length} livraison{route.courierRoute.stops.filter(isDeliveryStop).length > 1 ? 's' : ''}</span>
                {route.error && <small>{route.error}</small>}
              </div>
            </article>
          ))}
        </aside>
      </div>
    </section>
  )
}

export function OptimizationResults({ result, plan, accessToken, onBack }: {
  result: OptimizationResult
  plan: DeliveryPlan
  accessToken: string
  onBack: () => void
}) {
  const [activeTab, setActiveTab] = useState<'dispatch' | 'details'>('dispatch')
  const generatedLabel = formatDateTime(result.generatedAt)
  const routeColors = useMemo(() => new Map(
    result.routes.map((route, index) => [route.courierId, ROUTE_COLORS[index % ROUTE_COLORS.length]]),
  ), [result.routes])

  function exportResult() {
    const blob = new Blob([`${JSON.stringify(result, null, 2)}\n`], { type: 'application/json;charset=utf-8' })
    const objectUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = `${result.sourcePlanId || 'resultat-azy'}-resultat.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(objectUrl)
  }

  function exportDispatchCsv() {
    const rows: Array<Array<string | number | undefined>> = [[
      'Livreur',
      'Véhicule',
      'Ordre',
      'Colis',
      'Destination',
      'Téléphone',
      'Heure prévue',
      'Statut',
    ]]

    result.routes.forEach((route) => {
      route.stops.filter(isDeliveryStop).forEach((stop) => rows.push([
        route.courierName,
        route.vehicleType === 'MOTO' ? 'Moto' : 'Bicyclette',
        stop.order,
        stop.packageLabel,
        formatLocation(stop.destination),
        stop.phone,
        formatTime(stop.estimatedArrivalAt),
        'Affecté',
      ]))
    })
    result.unassignedPackages.forEach((item) => rows.push([
      '',
      '',
      '',
      item.packageLabel,
      '',
      '',
      '',
      `Non affecté — ${unassignedTitle(item.reason)}`,
    ]))

    const csv = `\uFEFF${rows.map((row) => row.map(csvCell).join(';')).join('\r\n')}\r\n`
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const objectUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = `${result.sourcePlanId || 'resultat-azy'}-livraisons.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(objectUrl)
  }

  return (
    <div className="optimization-results">
      <section className="result-hero" aria-labelledby="result-title">
        <div className="result-hero__topline">
          <button className="button button--light" type="button" onClick={onBack}><ArrowLeft aria-hidden="true" />Modifier le plan</button>
          <span className={`result-status result-status--${result.status.toLowerCase()}`}>
            {result.status === 'SUCCESS' ? <CheckCircle2 aria-hidden="true" /> : <TriangleAlert aria-hidden="true" />}
            {result.status === 'SUCCESS' ? 'Toutes les livraisons sont affectées' : `${result.summary.unassignedPackageCount} colis à examiner`}
          </span>
        </div>
        <div className="result-hero__content">
          <div>
            <p className="eyebrow">Résultat · {plan.name}</p>
            <h1 id="result-title">Les tournées sont prêtes.</h1>
            <p>Plan préparé le {generatedLabel} pour {result.summary.assignedPackageCount} livraison{result.summary.assignedPackageCount > 1 ? 's' : ''}.</p>
          </div>
          <button className="button result-export" type="button" onClick={exportResult}><Download aria-hidden="true" />Télécharger le résultat</button>
        </div>
        <div className="workspace-progress" aria-label="Étapes du plan">
          <span className="is-complete">1 <small>Mode</small></span>
          <span className="is-complete">2 <small>Préparation</small></span>
          <span className="is-active">3 <small>Résultat</small></span>
        </div>
      </section>

      <div className="result-tabs" role="tablist" aria-label="Vues du résultat">
        <button
          type="button"
          role="tab"
          aria-label="Liste des livraisons"
          aria-selected={activeTab === 'dispatch'}
          className={activeTab === 'dispatch' ? 'is-active' : ''}
          onClick={() => setActiveTab('dispatch')}
        >
          <ListChecks aria-hidden="true" />Liste des livraisons
          <small>Vue opérationnelle</small>
        </button>
        <button
          type="button"
          role="tab"
          aria-label="Détails"
          aria-selected={activeTab === 'details'}
          className={activeTab === 'details' ? 'is-active' : ''}
          onClick={() => setActiveTab('details')}
        >
          <BarChart3 aria-hidden="true" />Détails
          <small>Carte et organisation des tournées</small>
        </button>
      </div>

      {activeTab === 'dispatch' ? (
        <section className="dispatch-board" role="tabpanel" aria-labelledby="dispatch-title">
          <div className="result-section-heading">
            <div><span><ListChecks aria-hidden="true" /></span><div><p className="eyebrow">{plan.name} · {generatedLabel}</p><h2 id="dispatch-title">Colis par livreur</h2></div></div>
            <div className="dispatch-heading-meta">
              <small>{result.summary.assignedPackageCount} livraison{result.summary.assignedPackageCount > 1 ? 's' : ''}</small>
              <div className="dispatch-actions" aria-label="Exporter la liste">
                <button className="button button--secondary" type="button" onClick={exportDispatchCsv}><FileSpreadsheet aria-hidden="true" />Excel</button>
                <button className="button button--secondary" type="button" onClick={() => window.print()}><Printer aria-hidden="true" />PDF / Imprimer</button>
              </div>
            </div>
          </div>
          <div className="dispatch-groups">
            {result.routes.map((route, routeIndex) => {
              const color = routeColors.get(route.courierId) ?? ROUTE_COLORS[routeIndex % ROUTE_COLORS.length]
              const deliveries = route.stops.filter(isDeliveryStop)
              return (
                <article className="dispatch-group" key={route.courierId} style={{ '--route-color': color } as CSSProperties}>
                  <header>
                    <span className="dispatch-group__avatar"><Bike aria-hidden="true" /></span>
                    <div><small>{route.vehicleType === 'MOTO' ? 'Moto' : 'Bicyclette'}</small><h3>{route.courierName}</h3></div>
                    <div className="dispatch-group__first"><span>Première livraison</span><strong>{deliveries[0] ? formatTime(deliveries[0].estimatedArrivalAt) : '—'}</strong></div>
                  </header>
                  <ol className="dispatch-list">
                    {deliveries.map((stop, deliveryIndex) => (
                      <li key={stop.packageId}>
                        <time dateTime={stop.estimatedArrivalAt}>{formatTime(stop.estimatedArrivalAt)}</time>
                        <span className="dispatch-list__order" style={{ backgroundColor: color }}>{deliveryIndex + 1}</span>
                        <div className="dispatch-list__package"><strong>{stop.packageLabel}</strong>{stop.phone && <small><Smartphone aria-hidden="true" />{stop.phone}</small>}</div>
                        <div className="dispatch-list__destination"><MapPin aria-hidden="true" /><span>{formatLocation(stop.destination)}</span></div>
                      </li>
                    ))}
                  </ol>
                </article>
              )
            })}
            {result.routes.length === 0 && <p className="dispatch-empty">Aucune tournée exploitable n’a été générée.</p>}
          </div>
          {result.unassignedPackages.length > 0 && (
            <div className="dispatch-unassigned">
              <div><PackageX aria-hidden="true" /><strong>{result.unassignedPackages.length} colis non affecté{result.unassignedPackages.length > 1 ? 's' : ''}</strong></div>
              <ul>{result.unassignedPackages.map((item) => <li key={item.packageId}><span>{item.packageLabel}</span><small>{unassignedTitle(item.reason)}</small></li>)}</ul>
            </div>
          )}
        </section>
      ) : (
        <div className="result-details" role="tabpanel">
          <section className="result-metrics" aria-label="Résumé du résultat">
            <article><PackageCheck aria-hidden="true" /><span>Colis affectés</span><strong>{result.summary.assignedPackageCount}<small> / {result.summary.packageCount}</small></strong></article>
            <article><RouteIcon aria-hidden="true" /><span>Livreurs utilisés</span><strong>{result.summary.usedCourierCount}<small> / {result.summary.courierCount}</small></strong></article>
            <article><Scale aria-hidden="true" /><span>Poids confié</span><strong>{result.summary.totalAssignedWeightKg.toLocaleString('fr-MG')}<small> kg</small></strong></article>
          </section>

          {result.routes.length > 0 && <ResultMap result={result} plan={plan} accessToken={accessToken} />}

          {(result.warnings.length > 0 || result.unassignedPackages.length > 0) && (
            <section className="result-attention" aria-labelledby="attention-title">
              <div className="result-section-heading">
                <div><span><TriangleAlert aria-hidden="true" /></span><div><p className="eyebrow">Action requise</p><h2 id="attention-title">Points à examiner</h2></div></div>
              </div>
              {result.warnings.map((warning, index) => <p className="result-warning" key={`${warning}-${index}`}>{warning}</p>)}
              <div className="unassigned-grid">
                {result.unassignedPackages.map((deliveryPackage) => (
                  <article key={deliveryPackage.packageId}>
                    <PackageX aria-hidden="true" />
                    <div><strong>{deliveryPackage.packageLabel}</strong><span>{unassignedTitle(deliveryPackage.reason)}</span><p>{deliveryPackage.message}</p></div>
                    <b>{deliveryPackage.weightKg.toLocaleString('fr-MG')} kg</b>
                  </article>
                ))}
              </div>
            </section>
          )}

          <section className="result-routes" aria-labelledby="routes-title">
            <div className="result-section-heading">
              <div><span><Sparkles aria-hidden="true" /></span><div><p className="eyebrow">Feuilles de route</p><h2 id="routes-title">Détail par livreur</h2></div></div>
              <small>{result.routes.length} tournée{result.routes.length > 1 ? 's' : ''}</small>
            </div>
            <div className="route-result-grid">
              {result.routes.map((route) => {
                const color = routeColors.get(route.courierId) ?? ROUTE_COLORS[0]
                return (
                  <article className="route-result-card" key={route.courierId} style={{ '--route-color': color } as CSSProperties}>
                <header>
                  <span className="route-result-avatar"><Bike aria-hidden="true" /></span>
                  <div><small>{route.vehicleType === 'MOTO' ? 'Moto' : 'Bicyclette'}</small><h3>{route.courierName}</h3></div>
                  <span className="route-result-count">{route.stops.filter(isDeliveryStop).length} livraison{route.stops.filter(isDeliveryStop).length > 1 ? 's' : ''}</span>
                </header>
                <div className="route-result-stats">
                  <div><span>Départ</span><strong>{formatDateTime(route.departureAt)}</strong></div>
                  <div><span>Fin estimée</span><strong>{formatDateTime(route.completedAt)}</strong></div>
                  <div><span>Durée</span><strong>{formatDuration(route.durationSeconds)}</strong></div>
                </div>
                <div className="capacity-meter">
                  <div><span>Charge</span><strong>{route.loadedWeightKg.toLocaleString('fr-MG')} / {route.maxWeightKg.toLocaleString('fr-MG')} kg</strong></div>
                  <div className="capacity-meter__track"><i style={{ width: `${Math.min(100, route.capacityUtilizationPercent)}%` }} /></div>
                  <small>{route.capacityUtilizationPercent.toLocaleString('fr-MG', { maximumFractionDigits: 1 })}% utilisé · {route.remainingCapacityKg.toLocaleString('fr-MG')} kg disponibles</small>
                </div>
                <ol className="route-stop-list">
                  {route.stops.map((stop) => (
                    <li key={`${stop.packageId}-${stop.order}`}>
                      <span style={{ backgroundColor: color }}>{stop.order}</span>
                      <div>
                        <strong>{stop.packageLabel}</strong>
                        <p><MapPin aria-hidden="true" />{stop.destination.label || stop.destination.destinationQuery || 'Destination confirmée'}</p>
                        {stop.phone && <p><Smartphone aria-hidden="true" />{stop.phone}</p>}
                      </div>
                      <div className="route-stop-meta"><strong>{formatDateTime(stop.estimatedArrivalAt)}</strong><small>{stop.weightKg.toLocaleString('fr-MG')} kg</small></div>
                    </li>
                  ))}
                </ol>
                  </article>
                )
              })}
            </div>
          </section>
        </div>
      )}
    </div>
  )
}
