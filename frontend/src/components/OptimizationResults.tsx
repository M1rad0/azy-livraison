import { useEffect, useMemo, useState, type CSSProperties } from 'react'
import type { GeoJsonObject } from 'geojson'
import {
  ArrowLeft,
  Bike,
  CheckCircle2,
  Clock3,
  Download,
  Gauge,
  LoaderCircle,
  MapPin,
  PackageCheck,
  PackageX,
  Route as RouteIcon,
  Scale,
  Smartphone,
  Sparkles,
  TriangleAlert,
} from 'lucide-react'
import { CircleMarker, GeoJSON, MapContainer, Popup, TileLayer, Tooltip, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import type { OptimizationResult, CourierRoute, UnassignedReason } from '../api/optimization'
import type { DeliveryPlan } from '../api/plans'
import { calculateRoute, type RoutingRouteResponse } from '../api/routing'

const TILE_URL = import.meta.env.VITE_MAP_TILE_URL
  ?? 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const TILE_ATTRIBUTION = import.meta.env.VITE_MAP_TILE_ATTRIBUTION
  ?? '&copy; OpenStreetMap contributors'
const ROUTE_COLORS = ['#ff7657', '#4f8cff', '#a160e8', '#edaf30', '#27a979', '#e55b92', '#5f7bff', '#8b9c27']

interface DrawnRoute {
  courierRoute: CourierRoute
  color: string
  geometry?: RoutingRouteResponse
  error?: string
}

function hasCoordinates(location: { latitude?: number; longitude?: number }) {
  return Number.isFinite(location.latitude) && Number.isFinite(location.longitude)
}

function formatDistance(meters: number) {
  return meters >= 1000
    ? `${(meters / 1000).toLocaleString('fr-MG', { maximumFractionDigits: 2 })} km`
    : `${Math.round(meters).toLocaleString('fr-MG')} m`
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
      const points = [
        ...(hasCoordinates(courierRoute.departureLocation) ? [{
          id: `start-${courierRoute.courierId}`,
          latitude: courierRoute.departureLocation.latitude as number,
          longitude: courierRoute.departureLocation.longitude as number,
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
  }, [accessToken, depot?.[0], depot?.[1], plan.settings.returnToDepot, result])

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
              <CircleMarker center={depot} radius={11} pathOptions={{ color: '#fff', fillColor: '#123c31', fillOpacity: 1, weight: 4 }}>
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
                key={`${route.courierRoute.courierId}-${stop.packageId}`}
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
                <span>{route.courierRoute.stops.length} arrêt{route.courierRoute.stops.length > 1 ? 's' : ''} · {formatDistance(route.courierRoute.distanceMeters)}</span>
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
            <p>Généré le {generatedLabel} avec {result.dataContext.optimizer} et les distances {result.dataContext.routingProvider}.</p>
          </div>
          <button className="button result-export" type="button" onClick={exportResult}><Download aria-hidden="true" />Télécharger le résultat</button>
        </div>
        <div className="workspace-progress" aria-label="Étapes du plan">
          <span className="is-complete">1 <small>Mode</small></span>
          <span className="is-complete">2 <small>Préparation</small></span>
          <span className="is-active">3 <small>Résultat</small></span>
        </div>
      </section>

      <section className="result-metrics" aria-label="Résumé du résultat">
        <article><PackageCheck aria-hidden="true" /><span>Colis affectés</span><strong>{result.summary.assignedPackageCount}<small> / {result.summary.packageCount}</small></strong></article>
        <article><RouteIcon aria-hidden="true" /><span>Livreurs utilisés</span><strong>{result.summary.usedCourierCount}<small> / {result.summary.courierCount}</small></strong></article>
        <article><Scale aria-hidden="true" /><span>Poids confié</span><strong>{result.summary.totalAssignedWeightKg.toLocaleString('fr-MG')}<small> kg</small></strong></article>
        <article><Gauge aria-hidden="true" /><span>Distance totale</span><strong>{formatDistance(result.summary.totalDistanceMeters)}</strong></article>
        <article><Clock3 aria-hidden="true" /><span>Calcul</span><strong>{(result.computationTimeMillis / 1000).toLocaleString('fr-MG', { maximumFractionDigits: 2 })}<small> s</small></strong></article>
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
                  <span className="route-result-count">{route.stops.length} arrêt{route.stops.length > 1 ? 's' : ''}</span>
                </header>
                <div className="route-result-stats">
                  <div><span>Départ</span><strong>{formatDateTime(route.departureAt)}</strong></div>
                  <div><span>Fin estimée</span><strong>{formatDateTime(route.completedAt)}</strong></div>
                  <div><span>Distance</span><strong>{formatDistance(route.distanceMeters)}</strong></div>
                  <div><span>Durée</span><strong>{formatDuration(route.durationSeconds)}</strong></div>
                </div>
                <div className="capacity-meter">
                  <div><span>Charge</span><strong>{route.loadedWeightKg.toLocaleString('fr-MG')} / {route.maxWeightKg.toLocaleString('fr-MG')} kg</strong></div>
                  <div className="capacity-meter__track"><i style={{ width: `${Math.min(100, route.capacityUtilizationPercent)}%` }} /></div>
                  <small>{route.capacityUtilizationPercent.toLocaleString('fr-MG', { maximumFractionDigits: 1 })}% utilisé · {route.remainingCapacityKg.toLocaleString('fr-MG')} kg disponibles</small>
                </div>
                <ol className="route-stop-list">
                  {route.stops.map((stop) => (
                    <li key={stop.packageId}>
                      <span style={{ backgroundColor: color }}>{stop.order}</span>
                      <div>
                        <strong>{stop.packageLabel}</strong>
                        <p><MapPin aria-hidden="true" />{stop.destination.label || stop.destination.destinationQuery || 'Destination confirmée'}</p>
                        {stop.phone && <p><Smartphone aria-hidden="true" />{stop.phone}</p>}
                      </div>
                      <div className="route-stop-meta"><strong>{formatDateTime(stop.estimatedArrivalAt)}</strong><small>{formatDistance(stop.distanceFromPreviousMeters)} · {stop.weightKg.toLocaleString('fr-MG')} kg</small></div>
                    </li>
                  ))}
                </ol>
              </article>
            )
          })}
        </div>
      </section>
    </div>
  )
}
