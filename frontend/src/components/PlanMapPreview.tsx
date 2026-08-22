import { useEffect, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import type { GeoJsonObject } from 'geojson'
import {
  CircleMarker,
  GeoJSON,
  MapContainer,
  Popup,
  TileLayer,
  Tooltip,
  useMap,
} from 'react-leaflet'
import { Clock3, LoaderCircle, MapPinned, Route, Ruler, TriangleAlert, X } from 'lucide-react'
import 'leaflet/dist/leaflet.css'
import { calculateRoute, type RoutingRouteResponse } from '../api/routing'
import type { AnyDeliveryPlan } from '../api/plans'

const TILE_URL = import.meta.env.VITE_MAP_TILE_URL
  ?? 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const TILE_ATTRIBUTION = import.meta.env.VITE_MAP_TILE_ATTRIBUTION
  ?? '&copy; OpenStreetMap contributors'
const ROUTE_COLORS = ['#f45b0b', '#0b3557', '#ff8a3d', '#2b648d', '#c94400', '#547f9e', '#f6a15e', '#7897ad']
const MAX_PREVIEW_ROUTES = 20

interface PreviewDestination {
  index: number
  id: string
  label: string
  latitude: number
  longitude: number
  destinationQuery?: string
  pickupLabel: string
  pickupLatitude: number
  pickupLongitude: number
}

interface PreviewRoute {
  destination: PreviewDestination
  color: string
  route?: RoutingRouteResponse
  error?: string
}

function hasCoordinates(location: { latitude?: number; longitude?: number }) {
  return Number.isFinite(location.latitude) && Number.isFinite(location.longitude)
}

function formatDistance(meters: number) {
  return meters >= 1000
    ? `${(meters / 1000).toLocaleString('fr-MG', { maximumFractionDigits: 2 })} km`
    : `${Math.round(meters)} m`
}

function formatDuration(seconds: number) {
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')}`
}

function FitPreviewBounds({ depot, hub, destinations, routes }: {
  depot?: [number, number]
  hub?: [number, number]
  destinations: PreviewDestination[]
  routes: PreviewRoute[]
}) {
  const map = useMap()

  useEffect(() => {
    const points: [number, number][] = []
    if (depot) points.push(depot)
    if (hub) points.push(hub)
    destinations.forEach((destination) => points.push([destination.latitude, destination.longitude]))
    destinations.forEach((destination) => points.push([destination.pickupLatitude, destination.pickupLongitude]))
    routes.forEach((item) => item.route?.geometry.coordinates.forEach(([longitude, latitude]) => points.push([latitude, longitude])))
    if (points.length > 0) map.fitBounds(points, { padding: [42, 42], maxZoom: 16 })
  }, [depot, destinations, hub, map, routes])

  return null
}

export function PlanMapPreview({ plan, accessToken, onClose }: {
  plan: AnyDeliveryPlan
  accessToken: string
  onClose: () => void
}) {
  const depotLocation = plan.pattern === 'SINGLE_PICKUP_MULTI_DELIVERY' ? plan.depot : undefined
  const depotCoordinates = depotLocation && hasCoordinates(depotLocation)
    ? [depotLocation.latitude as number, depotLocation.longitude as number] as [number, number]
    : undefined
  const hubLocation = plan.pattern === 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY' ? plan.redistributionHub : undefined
  const hubCoordinates = hubLocation && hasCoordinates(hubLocation)
    ? [hubLocation.latitude as number, hubLocation.longitude as number] as [number, number]
    : undefined
  const allPackageCount = plan.pattern === 'SINGLE_PICKUP_MULTI_DELIVERY'
    ? plan.packages.length
    : plan.clients.reduce((sum, client) => sum + client.deliveryGroups.reduce((groupSum, group) => groupSum + group.packages.length, 0), 0)
  const unresolvedPickupCount = plan.pattern === 'SINGLE_PICKUP_MULTI_DELIVERY'
    ? (depotCoordinates ? 0 : 1)
    : plan.clients.flatMap((client) => client.deliveryGroups).filter((group) => !hasCoordinates(group.pickup)).length
  const destinations = useMemo(() => {
    if (plan.pattern === 'SINGLE_PICKUP_MULTI_DELIVERY') {
      if (!depotCoordinates) return []
      return plan.packages.flatMap((deliveryPackage, index) => hasCoordinates(deliveryPackage.destination) ? [{
        index,
        id: deliveryPackage.id,
        label: deliveryPackage.label || `Colis ${index + 1}`,
        latitude: deliveryPackage.destination.latitude as number,
        longitude: deliveryPackage.destination.longitude as number,
        destinationQuery: deliveryPackage.destination.destinationQuery,
        pickupLabel: plan.depot.label || 'Point de récupération',
        pickupLatitude: depotCoordinates[0],
        pickupLongitude: depotCoordinates[1],
      }] : [])
    }
    let index = 0
    return plan.clients.flatMap((client) => client.deliveryGroups.flatMap((group) => group.packages.flatMap((deliveryPackage) => {
      const destinationIndex = index++
      return hasCoordinates(group.pickup) && hasCoordinates(deliveryPackage.destination) ? [{
        index: destinationIndex,
        id: deliveryPackage.id,
        label: deliveryPackage.label || `Colis ${destinationIndex + 1}`,
        latitude: deliveryPackage.destination.latitude as number,
        longitude: deliveryPackage.destination.longitude as number,
        destinationQuery: deliveryPackage.destination.destinationQuery,
        pickupLabel: `${client.name || 'Client'} · ${group.name || 'Retrait'}`,
        pickupLatitude: group.pickup.latitude as number,
        pickupLongitude: group.pickup.longitude as number,
      }] : []
    })))
  }, [depotCoordinates?.[0], depotCoordinates?.[1], plan])
  const unresolvedCount = allPackageCount - destinations.length
  const [routes, setRoutes] = useState<PreviewRoute[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = previousOverflow }
  }, [])

  useEffect(() => {
    if (destinations.length === 0) return
    const controller = new AbortController()
    let active = true
    setLoading(true)
    void Promise.all(destinations.slice(0, MAX_PREVIEW_ROUTES).map(async (destination, index): Promise<PreviewRoute> => {
      const color = ROUTE_COLORS[index % ROUTE_COLORS.length]
      try {
        const routeResult = await calculateRoute([
          { id: `pickup-${destination.id}`, latitude: destination.pickupLatitude, longitude: destination.pickupLongitude },
          { id: destination.id || `package-${destination.index + 1}`, latitude: destination.latitude, longitude: destination.longitude },
        ], accessToken, controller.signal)
        return { destination, color, route: routeResult }
      } catch (error) {
        return {
          destination,
          color,
          error: error instanceof Error ? error.message : 'Trajet indisponible.',
        }
      }
    })).then((results) => {
      if (active) setRoutes(results)
    }).finally(() => {
      if (active) setLoading(false)
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [accessToken, destinations])

  const successfulRoutes = routes.filter((item) => item.route)
  const totalDistance = successfulRoutes.reduce((sum, item) => sum + (item.route?.distanceMeters ?? 0), 0)
  const totalDuration = successfulRoutes.reduce((sum, item) => sum + (item.route?.durationSeconds ?? 0), 0)

  return createPortal(
    <div className="map-preview-overlay" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <section className="map-preview-dialog" role="dialog" aria-modal="true" aria-labelledby="map-preview-title">
        <header className="map-preview-header">
          <div className="map-preview-title">
            <span><MapPinned aria-hidden="true" /></span>
            <div>
              <p className="eyebrow">Contrôle avant validation</p>
              <h2 id="map-preview-title">Aperçu des trajets</h2>
            </div>
          </div>
          <button className="icon-button" type="button" aria-label="Fermer la carte" onClick={onClose}><X aria-hidden="true" /></button>
        </header>

        <div className="map-preview-content">
          <div className="plan-preview-map">
            <MapContainer center={[-18.895, 47.525]} zoom={13} scrollWheelZoom>
              <TileLayer url={TILE_URL} attribution={TILE_ATTRIBUTION} />
              <FitPreviewBounds depot={depotCoordinates} hub={hubCoordinates} destinations={destinations} routes={routes} />

              {depotCoordinates && (
                <CircleMarker center={depotCoordinates} radius={11} pathOptions={{ color: '#fff', fillColor: '#0b3557', fillOpacity: 1, weight: 4 }}>
                  <Tooltip permanent direction="top">Retrait</Tooltip>
                  <Popup><strong>{depotLocation?.label || 'Point de récupération'}</strong><br />{depotLocation?.destinationQuery}</Popup>
                </CircleMarker>
              )}

              {hubCoordinates && (
                <CircleMarker center={hubCoordinates} radius={12} pathOptions={{ color: '#fff', fillColor: '#f45b0b', fillOpacity: 1, weight: 4 }}>
                  <Tooltip permanent direction="top">Siège</Tooltip>
                  <Popup><strong>Siège / point de rencontre</strong><br />{hubLocation?.label || hubLocation?.destinationQuery}</Popup>
                </CircleMarker>
              )}

              {plan.pattern === 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY' && destinations.map((destination) => (
                <CircleMarker key={`pickup-${destination.id}-${destination.index}`} center={[destination.pickupLatitude, destination.pickupLongitude]} radius={10} pathOptions={{ color: '#fff', fillColor: '#0b3557', fillOpacity: 1, weight: 4 }}>
                  <Tooltip direction="top">Retrait</Tooltip><Popup><strong>{destination.pickupLabel}</strong></Popup>
                </CircleMarker>
              ))}

              {destinations.map((destination, index) => (
                <CircleMarker
                  key={`${destination.id}-${destination.index}`}
                  center={[destination.latitude, destination.longitude]}
                  radius={8}
                  pathOptions={{ color: '#fff', fillColor: ROUTE_COLORS[index % ROUTE_COLORS.length], fillOpacity: 1, weight: 3 }}
                >
                  <Tooltip permanent direction="top">{destination.index + 1}</Tooltip>
                  <Popup><strong>{destination.label}</strong><br />{destination.destinationQuery}</Popup>
                </CircleMarker>
              ))}

              {successfulRoutes.map((item) => item.route && (
                <GeoJSON
                  key={`${item.destination.id}-${item.route.distanceMeters}`}
                  data={item.route.geometry as GeoJsonObject}
                  style={{ color: item.color, weight: 5, opacity: 0.78 }}
                />
              ))}
            </MapContainer>
            {loading && <div className="map-preview-loading"><LoaderCircle className="spin" aria-hidden="true" />Calcul des trajets…</div>}
          </div>

          <aside className="map-preview-sidebar">
            <div className="map-preview-notice">
              <Route aria-hidden="true" />
              <p><strong>Aperçu de couverture</strong>Chaque ligne relie le retrait à un colis. La répartition finale entre livreurs sera calculée à l’étape suivante.</p>
            </div>

            {unresolvedPickupCount > 0 && (
              <div className="map-preview-warning"><TriangleAlert aria-hidden="true" /><p><strong>{unresolvedPickupCount} retrait{unresolvedPickupCount > 1 ? 's' : ''} à confirmer</strong>Sélectionnez une proposition dans la recherche du point de récupération.</p></div>
            )}
            {unresolvedCount > 0 && (
              <div className="map-preview-warning"><TriangleAlert aria-hidden="true" /><p><strong>{unresolvedCount} destination{unresolvedCount > 1 ? 's' : ''} à confirmer</strong>Utilisez la recherche sous chaque colis pour placer ces points sur la carte.</p></div>
            )}
            {destinations.length > MAX_PREVIEW_ROUTES && (
              <div className="map-preview-warning"><TriangleAlert aria-hidden="true" /><p><strong>Aperçu limité</strong>Les {MAX_PREVIEW_ROUTES} premières liaisons sont tracées pour garder la carte lisible.</p></div>
            )}

            <div className="map-preview-metrics">
              <div><Ruler aria-hidden="true" /><span>Distance cumulée*</span><strong>{successfulRoutes.length ? formatDistance(totalDistance) : '—'}</strong></div>
              <div><Clock3 aria-hidden="true" /><span>Durée cumulée*</span><strong>{successfulRoutes.length ? formatDuration(totalDuration) : '—'}</strong></div>
            </div>
            <small className="map-preview-footnote">* Somme indicative des liaisons individuelles, avant optimisation.</small>

            <div className="map-preview-routes">
              {routes.map((item) => (
                <article key={`${item.destination.id}-${item.destination.index}`}>
                  <span style={{ background: item.color }}>{item.destination.index + 1}</span>
                  <div><strong>{item.destination.label}</strong><small>{item.route ? `${formatDistance(item.route.distanceMeters)} · ${formatDuration(item.route.durationSeconds)}` : item.error}</small></div>
                </article>
              ))}
            </div>
          </aside>
        </div>
      </section>
    </div>,
    document.body,
  )
}
