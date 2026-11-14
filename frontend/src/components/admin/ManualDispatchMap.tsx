import { useEffect, useMemo } from 'react'
import { CircleMarker, MapContainer, Popup, TileLayer, Tooltip, useMap } from 'react-leaflet'
import { latLngBounds, type LatLngTuple } from 'leaflet'
import type { ManualDispatchDraft } from '../../api/manualDispatch'
import { locationLabel, vehicleLabel } from './adminHelpers'
import { manualDispatchCourierColor, validPosition } from './manualDispatchGeography'
import 'leaflet/dist/leaflet.css'
import './ManualDispatchMap.css'

function MapBounds({ positions }: { positions: string }) {
  const map = useMap()
  useEffect(() => {
    const points = JSON.parse(positions) as LatLngTuple[]
    const frame = requestAnimationFrame(() => {
      map.invalidateSize({ pan: false })
      if (points.length) map.fitBounds(latLngBounds(points), { padding: [30, 30], maxZoom: 15, animate: false })
    })
    return () => cancelAnimationFrame(frame)
  }, [map, positions])
  useEffect(() => {
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => map.invalidateSize({ pan: false }))
    observer.observe(map.getContainer())
    return () => observer.disconnect()
  }, [map])
  return null
}

export function ManualDispatchMap({ draft, phase, selectedId, onSelect }: {
  draft: ManualDispatchDraft
  phase: 'COLLECTION' | 'DELIVERY'
  selectedId?: string
  onSelect: (id: string) => void
}) {
  const names = new Map(draft.couriers.map((c) => [c.courierId, c.name]))
  const points = phase === 'COLLECTION'
    ? draft.pickups.map((p) => ({ id: p.id, location: p.location, title: locationLabel(p.location),
      courierId: draft.collections.find((c) => c.pickupId === p.id)?.courierId,
      details: p.packageIds.flatMap((id) => {
        const pkg = draft.packages.find((item) => item.packageId === id)
        return pkg ? [`${pkg.serviceClientName} · ${pkg.recipientName} — ${locationLabel(pkg.destination)}`] : []
      }) }))
    : draft.packages.map((p) => ({ id: p.packageId, location: p.destination, title: p.recipientName,
      courierId: draft.deliveries.find((d) => d.packageId === p.packageId)?.courierId,
      details: [locationLabel(p.destination), p.serviceClientName, p.contact, p.desiredDeliveryTime ? `Créneau : ${p.desiredDeliveryTime.slice(0, 5)}` : ''].filter(Boolean) }))
  // Colocated packages remain independently selectable inside a shared popup.
  const groups = new Map<string, typeof points>()
  for (const point of points) {
    if (!validPosition(point.location)) continue
    const key = `${point.location.latitude}:${point.location.longitude}`
    groups.set(key, [...(groups.get(key) ?? []), point])
  }
  const positions = JSON.stringify([...groups.values()].map((g) => [g[0].location.latitude, g[0].location.longitude])
    .concat(draft.couriers.filter((c) => validPosition(c.departure)).map((c) => [c.departure.latitude, c.departure.longitude]))
    .concat(validPosition(draft.hub) ? [[draft.hub.latitude, draft.hub.longitude]] : []))
  const bounds = useMemo(() => positions, [positions, phase])
  return (
    <section className="manual-dispatch-map" aria-label={phase === 'COLLECTION' ? 'Carte des récupérations' : 'Carte des livraisons'}>
      <MapContainer center={[-18.8792, 47.5079]} zoom={12} className="manual-dispatch-map__canvas" scrollWheelZoom>
        <TileLayer url={import.meta.env.VITE_MAP_TILE_URL ?? 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'}
          attribution={import.meta.env.VITE_MAP_TILE_ATTRIBUTION ?? '&copy; OpenStreetMap contributors'} />
        <MapBounds positions={bounds} />
        {validPosition(draft.hub) && <CircleMarker center={[draft.hub.latitude, draft.hub.longitude]} radius={17}
          pathOptions={{ color: '#17384f', fill: false, dashArray: '4 4', weight: 2 }}>
          <Tooltip>Ralliement de référence · {locationLabel(draft.hub)}</Tooltip>
        </CircleMarker>}
        {draft.couriers.filter((c) => validPosition(c.departure)).map((c) => <CircleMarker key={c.courierId}
          center={[c.departure.latitude, c.departure.longitude]} radius={13}
          pathOptions={{ color: manualDispatchCourierColor(c.courierId), fillColor: '#fff', fillOpacity: 1, weight: 4 }}>
          <Tooltip direction="top"><strong>Départ de {c.name}</strong><br />{locationLabel(c.departure)}<br />{c.departureAt.slice(11, 16)} · {vehicleLabel(c.vehicleType)}</Tooltip>
          <Popup><strong>{c.name}</strong><p>Départ : {locationLabel(c.departure)}</p>{c.contact}</Popup>
        </CircleMarker>)}
        {[...groups].map(([key, group]) => {
          const ids = new Set(group.map((p) => p.courierId))
          const color = ids.size === 1 && group[0].courierId ? manualDispatchCourierColor(group[0].courierId) : '#e46824'
          return <CircleMarker key={`${phase}:${key}`} center={[group[0].location.latitude, group[0].location.longitude]}
            radius={group.some((p) => p.id === selectedId) ? 12 : 9}
            pathOptions={{ color: group.some((p) => p.id === selectedId) ? '#102f46' : '#fff', fillColor: color, fillOpacity: 0.95, weight: 3 }}
            eventHandlers={{ click: () => onSelect(group[0].id) }}>
            <Tooltip direction="top"><strong>{group[0].title}</strong><br />{group.length > 1 ? `${group.length} colis à cet endroit` : group[0].details.slice(0, 2).join(' · ')}<br />{group[0].courierId ? names.get(group[0].courierId) : 'Non affecté'}</Tooltip>
            <Popup minWidth={220} maxWidth={320}>
              <div className="manual-dispatch-map__popup">
                {group.map((p) => <button type="button" key={p.id} draggable onDragStart={(event) => {
                  event.dataTransfer.setData('text/plain', `${phase === 'COLLECTION' ? 'pickup:' : 'package:'}${p.id}`)
                  event.dataTransfer.effectAllowed = 'move'
                }} onClick={() => onSelect(p.id)} className={p.id === selectedId ? 'is-selected' : undefined}>
                  <strong>{p.title}</strong><span>{p.courierId ? names.get(p.courierId) : 'Non affecté'}</span>
                  {p.details.map((detail, i) => <small key={i}>{detail}</small>)}
                </button>)}
              </div>
            </Popup>
          </CircleMarker>
        })}
      </MapContainer>
      <p className="admin-muted">Points pleins : {phase === 'COLLECTION' ? 'récupérations' : 'colis'} · Cercles : départs des livreurs</p>
    </section>
  )
}
