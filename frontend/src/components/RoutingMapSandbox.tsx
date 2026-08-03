import { useEffect, useMemo, useState, type FormEvent } from 'react'
import L, { type LeafletMouseEvent, type Marker as LeafletMarker } from 'leaflet'
import {
  CircleMarker,
  GeoJSON,
  MapContainer,
  Marker,
  Popup,
  TileLayer,
  useMap,
  useMapEvents,
} from 'react-leaflet'
import type { GeoJsonObject } from 'geojson'
import 'leaflet/dist/leaflet.css'
import { searchAddress, type GeocodingCandidate } from '../api/geocoding'
import { calculateRoute, type RoutingRouteResponse } from '../api/routing'

type PointRole = 'start' | 'end'

interface SandboxPoint {
  id: PointRole
  label: string
  latitude: number
  longitude: number
  source: 'initial' | 'search' | 'map' | 'drag'
}

const INITIAL_POINTS: Record<PointRole, SandboxPoint> = {
  start: {
    id: 'start',
    label: 'Analakely, Antananarivo',
    latitude: -18.9083181,
    longitude: 47.5262845,
    source: 'initial',
  },
  end: {
    id: 'end',
    label: 'Ankorondrano, Antananarivo',
    latitude: -18.8859714,
    longitude: 47.522924,
    source: 'initial',
  },
}

const TILE_URL = import.meta.env.VITE_MAP_TILE_URL
  ?? 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const TILE_ATTRIBUTION = import.meta.env.VITE_MAP_TILE_ATTRIBUTION
  ?? '&copy; OpenStreetMap contributors'

function ClickCapture({ onPoint }: { onPoint: (latitude: number, longitude: number) => void }) {
  useMapEvents({
    click(event: LeafletMouseEvent) {
      onPoint(event.latlng.lat, event.latlng.lng)
    },
  })
  return null
}

function FitSandboxBounds({ points, route }: {
  points: Record<PointRole, SandboxPoint>
  route?: RoutingRouteResponse
}) {
  const map = useMap()

  useEffect(() => {
    const coordinates = route
      ? route.geometry.coordinates.map(([longitude, latitude]) => [latitude, longitude] as [number, number])
      : Object.values(points).map((point) => [point.latitude, point.longitude] as [number, number])
    map.fitBounds(coordinates, { padding: [42, 42], maxZoom: 16 })
  }, [map, points, route])

  return null
}

function PointSearch({
  role,
  point,
  active,
  accessToken,
  onActivate,
  onSelect,
}: {
  role: PointRole
  point: SandboxPoint
  active: boolean
  accessToken: string
  onActivate: () => void
  onSelect: (candidate: GeocodingCandidate) => void
}) {
  const title = role === 'start' ? 'Départ' : 'Arrivée'
  const [query, setQuery] = useState(point.label)
  const [candidates, setCandidates] = useState<GeocodingCandidate[]>([])
  const [searching, setSearching] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string>()

  useEffect(() => {
    setQuery(point.label)
  }, [point.label])

  async function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSearching(true)
    setCandidates([])
    setErrorMessage(undefined)
    try {
      const response = await searchAddress(query, accessToken)
      setCandidates(response.candidates)
      if (response.candidates.length === 0) setErrorMessage('Aucun résultat trouvé.')
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : 'La recherche a échoué.')
    } finally {
      setSearching(false)
    }
  }

  function select(candidate: GeocodingCandidate) {
    setQuery(candidate.displayName)
    setCandidates([])
    onSelect(candidate)
  }

  return (
    <article className={`sandbox-point${active ? ' sandbox-point--active' : ''}`}>
      <button className="sandbox-point__selector" type="button" onClick={onActivate}>
        <span className={`sandbox-point__badge sandbox-point__badge--${role}`} aria-hidden="true">
          {role === 'start' ? 'A' : 'B'}
        </span>
        <span>
          <strong>{title}</strong>
          <small>{active ? 'Le prochain clic modifiera ce point' : 'Cliquer pour rendre actif'}</small>
        </span>
      </button>

      <form className="sandbox-point__search" onSubmit={(event) => void handleSearch(event)}>
        <label htmlFor={`sandbox-${role}-query`}>Rechercher {title.toLowerCase()}</label>
        <div>
          <input
            id={`sandbox-${role}-query`}
            value={query}
            minLength={3}
            onChange={(event) => setQuery(event.target.value)}
            onFocus={onActivate}
          />
          <button className="button" type="submit" disabled={searching || query.trim().length < 3}>
            {searching ? 'Recherche…' : 'Chercher'}
          </button>
        </div>
      </form>

      {errorMessage && <p className="sandbox-point__error" role="alert">{errorMessage}</p>}

      {candidates.length > 0 && (
        <ul className="sandbox-candidates" aria-label={`Résultats pour ${title.toLowerCase()}`}>
          {candidates.map((candidate) => (
            <li key={candidate.id}>
              <button type="button" onClick={() => select(candidate)}>
                <strong>{candidate.displayName}</strong>
                <small>{candidate.latitude.toFixed(6)}, {candidate.longitude.toFixed(6)}</small>
              </button>
            </li>
          ))}
        </ul>
      )}

      <dl className="sandbox-point__coordinates">
        <div><dt>Latitude</dt><dd>{point.latitude.toFixed(7)}</dd></div>
        <div><dt>Longitude</dt><dd>{point.longitude.toFixed(7)}</dd></div>
      </dl>
    </article>
  )
}

function haversineMeters(start: SandboxPoint, end: SandboxPoint) {
  const radians = (degrees: number) => degrees * Math.PI / 180
  const earthRadius = 6_371_000
  const latitudeDelta = radians(end.latitude - start.latitude)
  const longitudeDelta = radians(end.longitude - start.longitude)
  const startLatitude = radians(start.latitude)
  const endLatitude = radians(end.latitude)
  const value = Math.sin(latitudeDelta / 2) ** 2
    + Math.cos(startLatitude) * Math.cos(endLatitude) * Math.sin(longitudeDelta / 2) ** 2
  return 2 * earthRadius * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value))
}

function formatDistance(meters: number) {
  return meters >= 1000
    ? `${(meters / 1000).toLocaleString('fr-MG', { maximumFractionDigits: 3 })} km`
    : `${Math.round(meters).toLocaleString('fr-MG')} m`
}

function formatDuration(seconds: number) {
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  return `${Math.floor(minutes / 60)} h ${minutes % 60} min`
}

export function RoutingMapSandbox({ accessToken }: { accessToken: string }) {
  const [points, setPoints] = useState<Record<PointRole, SandboxPoint>>(INITIAL_POINTS)
  const [activeRole, setActiveRole] = useState<PointRole>('start')
  const [route, setRoute] = useState<RoutingRouteResponse>()
  const [routing, setRouting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string>()
  const icons = useMemo(() => ({
    start: L.divIcon({ className: 'sandbox-pin sandbox-pin--start', html: '<span><b>A</b></span>', iconSize: [34, 42], iconAnchor: [17, 40] }),
    end: L.divIcon({ className: 'sandbox-pin sandbox-pin--end', html: '<span><b>B</b></span>', iconSize: [34, 42], iconAnchor: [17, 40] }),
  }), [])
  const straightLineMeters = haversineMeters(points.start, points.end)
  const detourPercent = route && straightLineMeters > 0
    ? Math.round((route.distanceMeters / straightLineMeters - 1) * 100)
    : undefined

  function updatePoint(role: PointRole, update: Omit<SandboxPoint, 'id'>) {
    setPoints((current) => ({ ...current, [role]: { id: role, ...update } }))
    setRoute(undefined)
    setErrorMessage(undefined)
  }

  function selectCandidate(role: PointRole, candidate: GeocodingCandidate) {
    updatePoint(role, {
      label: candidate.displayName,
      latitude: candidate.latitude,
      longitude: candidate.longitude,
      source: 'search',
    })
    setActiveRole(role === 'start' ? 'end' : 'start')
  }

  function placeActivePoint(latitude: number, longitude: number) {
    updatePoint(activeRole, {
      label: `Point placé sur la carte (${latitude.toFixed(5)}, ${longitude.toFixed(5)})`,
      latitude,
      longitude,
      source: 'map',
    })
  }

  function dragPoint(role: PointRole, marker: LeafletMarker) {
    const position = marker.getLatLng()
    updatePoint(role, {
      label: `Marqueur déplacé (${position.lat.toFixed(5)}, ${position.lng.toFixed(5)})`,
      latitude: position.lat,
      longitude: position.lng,
      source: 'drag',
    })
  }

  async function requestRoute() {
    setRouting(true)
    setErrorMessage(undefined)
    try {
      setRoute(await calculateRoute([
        { id: 'start', latitude: points.start.latitude, longitude: points.start.longitude },
        { id: 'end', latitude: points.end.latitude, longitude: points.end.longitude },
      ], accessToken))
    } catch (error) {
      setRoute(undefined)
      setErrorMessage(error instanceof Error ? error.message : 'Le calcul a échoué.')
    } finally {
      setRouting(false)
    }
  }

  function swapPoints() {
    setPoints((current) => ({
      start: { ...current.end, id: 'start' },
      end: { ...current.start, id: 'end' },
    }))
    setRoute(undefined)
  }

  function reset() {
    setPoints(INITIAL_POINTS)
    setActiveRole('start')
    setRoute(undefined)
    setErrorMessage(undefined)
  }

  return (
    <section className="routing-sandbox" aria-labelledby="routing-sandbox-title">
      <div className="section-heading routing-sandbox__heading">
        <div>
          <p className="eyebrow">Branche expérimentale · Diagnostic</p>
          <h2 id="routing-sandbox-title">Bac à sable cartographique</h2>
        </div>
        <p>Cliquez sur la carte ou déplacez les marqueurs, puis comparez le tracé OSRM.</p>
      </div>

      <div className="routing-sandbox__layout">
        <aside className="routing-sandbox__panel">
          <PointSearch
            role="start"
            point={points.start}
            active={activeRole === 'start'}
            accessToken={accessToken}
            onActivate={() => setActiveRole('start')}
            onSelect={(candidate) => selectCandidate('start', candidate)}
          />
          <PointSearch
            role="end"
            point={points.end}
            active={activeRole === 'end'}
            accessToken={accessToken}
            onActivate={() => setActiveRole('end')}
            onSelect={(candidate) => selectCandidate('end', candidate)}
          />

          <div className="routing-sandbox__actions">
            <button className="button" type="button" onClick={() => void requestRoute()} disabled={routing}>
              {routing ? 'Calcul OSRM…' : 'Calculer l’itinéraire'}
            </button>
            <button className="button button--secondary" type="button" onClick={swapPoints}>Inverser</button>
            <button className="button button--secondary" type="button" onClick={reset}>Réinitialiser</button>
          </div>

          {errorMessage && <p className="form-error" role="alert">{errorMessage}</p>}

          <div className="sandbox-metrics" aria-live="polite">
            <article>
              <span>Vol d’oiseau</span>
              <strong>{formatDistance(straightLineMeters)}</strong>
            </article>
            <article>
              <span>Distance routière</span>
              <strong>{route ? formatDistance(route.distanceMeters) : '—'}</strong>
            </article>
            <article>
              <span>Durée OSRM</span>
              <strong>{route ? formatDuration(route.durationSeconds) : '—'}</strong>
            </article>
            <article>
              <span>Détour routier</span>
              <strong>{detourPercent === undefined ? '—' : `${detourPercent >= 0 ? '+' : ''}${detourPercent} %`}</strong>
            </article>
          </div>
        </aside>

        <div className="routing-map" aria-label="Carte interactive du routage">
          <MapContainer center={[-18.895, 47.525]} zoom={14} scrollWheelZoom>
            <TileLayer url={TILE_URL} attribution={TILE_ATTRIBUTION} />
            <ClickCapture onPoint={placeActivePoint} />
            <FitSandboxBounds points={points} route={route} />

            {(['start', 'end'] as PointRole[]).map((role) => (
              <Marker
                key={role}
                position={[points[role].latitude, points[role].longitude]}
                icon={icons[role]}
                draggable
                eventHandlers={{ dragend: (event) => dragPoint(role, event.target as LeafletMarker) }}
              >
                <Popup>
                  <strong>{role === 'start' ? 'Départ' : 'Arrivée'}</strong><br />
                  {points[role].label}
                </Popup>
              </Marker>
            ))}

            {route && (
              <GeoJSON
                key={`${route.distanceMeters}-${route.geometry.coordinates.length}`}
                data={route.geometry as GeoJsonObject}
                style={{ color: '#e06b3d', weight: 6, opacity: 0.9 }}
              />
            )}

            {route?.waypoints.map((waypoint) => (
              <CircleMarker
                key={`snapped-${waypoint.id}`}
                center={[waypoint.snappedLatitude, waypoint.snappedLongitude]}
                radius={7}
                pathOptions={{ color: '#173f35', fillColor: '#fffdf7', fillOpacity: 1, weight: 3 }}
              >
                <Popup>
                  <strong>Point recalé OSRM</strong><br />
                  {waypoint.roadName || 'Route sans nom'}<br />
                  Écart : {formatDistance(waypoint.distanceToRoadMeters)}
                </Popup>
              </CircleMarker>
            ))}
          </MapContainer>
        </div>
      </div>

      {route && (
        <div className="sandbox-route-details">
          <div>
            <span>Profil</span>
            <strong>{route.provider} · {route.preset}</strong>
          </div>
          {route.waypoints.map((waypoint) => (
            <div key={waypoint.id}>
              <span>{waypoint.id === 'start' ? 'Départ recalé' : 'Arrivée recalée'}</span>
              <strong>{waypoint.roadName || 'Route sans nom'} · écart {formatDistance(waypoint.distanceToRoadMeters)}</strong>
            </div>
          ))}
        </div>
      )}
    </section>
  )
}
