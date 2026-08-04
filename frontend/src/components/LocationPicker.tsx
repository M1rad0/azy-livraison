import { useEffect, useId, useState } from 'react'
import { createPortal } from 'react-dom'
import { Check, LoaderCircle, MapPin, MapPinned, Search, X } from 'lucide-react'
import { CircleMarker, MapContainer, Popup, TileLayer, useMap, useMapEvents } from 'react-leaflet'
import type { Location } from '../api/csvImports'
import { searchAddress, type GeocodingCandidate } from '../api/geocoding'

const TILE_URL = import.meta.env.VITE_MAP_TILE_URL
  ?? 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const TILE_ATTRIBUTION = import.meta.env.VITE_MAP_TILE_ATTRIBUTION
  ?? '&copy; OpenStreetMap contributors'
const ANTANANARIVO: [number, number] = [-18.895, 47.525]

function hasCoordinates(location: Location) {
  return Number.isFinite(location.latitude) && Number.isFinite(location.longitude)
}

function MapPositionController({ location }: { location: Location }) {
  const map = useMap()

  useEffect(() => {
    if (hasCoordinates(location)) {
      map.setView([location.latitude as number, location.longitude as number], 16, { animate: true })
    }
  }, [location.latitude, location.longitude, map])

  return null
}

function MapClickHandler({ onPick }: { onPick: (latitude: number, longitude: number) => void }) {
  useMapEvents({
    click: (event) => onPick(event.latlng.lat, event.latlng.lng),
  })
  return null
}

function LocationPickerDialog({
  accessToken,
  value,
  title,
  description,
  onConfirm,
  onClose,
}: {
  accessToken: string
  value: Location
  title: string
  description: string
  onConfirm: (location: Location) => void
  onClose: () => void
}) {
  const titleId = useId()
  const [query, setQuery] = useState(value.destinationQuery ?? '')
  const [selection, setSelection] = useState<Location>(value)
  const [candidates, setCandidates] = useState<GeocodingCandidate[]>([])
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string>()

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', closeOnEscape)
    }
  }, [onClose])

  async function search() {
    if (query.trim().length < 3) return
    setBusy(true)
    setMessage(undefined)
    setCandidates([])
    try {
      const result = await searchAddress(query, accessToken)
      setCandidates(result.candidates)
      if (result.candidates.length === 0) setMessage('Aucun lieu trouvé à Madagascar.')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Recherche impossible.')
    } finally {
      setBusy(false)
    }
  }

  function selectCandidate(candidate: GeocodingCandidate) {
    setQuery(candidate.displayName)
    setCandidates([])
    setMessage(undefined)
    setSelection({
      ...selection,
      label: selection.label || candidate.displayName,
      destinationQuery: candidate.displayName,
      latitude: candidate.latitude,
      longitude: candidate.longitude,
    })
  }

  function selectMapPoint(latitude: number, longitude: number) {
    setCandidates([])
    setMessage('Point placé manuellement sur la carte.')
    setSelection({
      ...selection,
      destinationQuery: query.trim() || selection.destinationQuery || 'Point sélectionné sur la carte',
      latitude,
      longitude,
    })
  }

  return createPortal(
    <div className="location-modal-overlay" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <section className="location-modal" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="location-modal__header">
          <div>
            <span aria-hidden="true"><MapPinned /></span>
            <div><p className="eyebrow">Sélection géographique</p><h2 id={titleId}>{title}</h2></div>
          </div>
          <button className="icon-button" type="button" aria-label="Fermer la sélection du lieu" onClick={onClose}><X aria-hidden="true" /></button>
        </header>

        <div className="location-modal__body">
          <aside className="location-modal__search">
            <p>{description}</p>
            <div className="location-modal__searchbar">
              <input
                autoFocus
                aria-label="Rechercher un lieu à Madagascar"
                value={query}
                minLength={3}
                placeholder="Adresse, quartier ou point de repère"
                onChange={(event) => {
                  setQuery(event.target.value)
                  setCandidates([])
                  setMessage(undefined)
                }}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault()
                    void search()
                  }
                }}
              />
              <button className="button" type="button" disabled={busy || query.trim().length < 3} onClick={() => void search()}>
                {busy ? <LoaderCircle className="spin" aria-hidden="true" /> : <Search aria-hidden="true" />}
                {busy ? 'Recherche…' : 'Rechercher'}
              </button>
            </div>
            {message && <p className="inline-message" role="status">{message}</p>}
            {candidates.length > 0 && (
              <ul className="workspace-candidates" aria-label="Résultats de la recherche">
                {candidates.map((candidate) => (
                  <li key={candidate.id}>
                    <button type="button" onClick={() => selectCandidate(candidate)}>
                      <strong>{candidate.displayName}</strong>
                      <small>{candidate.latitude.toFixed(6)}, {candidate.longitude.toFixed(6)}</small>
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <div className={`location-modal__selection${hasCoordinates(selection) ? ' is-confirmed' : ''}`}>
              <MapPin aria-hidden="true" />
              <div>
                <small>{hasCoordinates(selection) ? 'Position confirmée' : 'Aucun point sélectionné'}</small>
                <strong>{selection.destinationQuery || 'Recherchez un lieu ou cliquez sur la carte'}</strong>
                {hasCoordinates(selection) && <span>{selection.latitude?.toFixed(6)}, {selection.longitude?.toFixed(6)}</span>}
              </div>
            </div>
            <button
              className="button button--wide"
              type="button"
              disabled={!hasCoordinates(selection)}
              onClick={() => {
                onConfirm(selection)
                onClose()
              }}
            >
              <Check aria-hidden="true" />Confirmer ce lieu
            </button>
          </aside>

          <div className="location-modal__map">
            <MapContainer center={hasCoordinates(selection) ? [selection.latitude as number, selection.longitude as number] : ANTANANARIVO} zoom={hasCoordinates(selection) ? 16 : 13} scrollWheelZoom>
              <TileLayer url={TILE_URL} attribution={TILE_ATTRIBUTION} />
              <MapPositionController location={selection} />
              <MapClickHandler onPick={selectMapPoint} />
              {hasCoordinates(selection) && (
                <CircleMarker center={[selection.latitude as number, selection.longitude as number]} radius={10} pathOptions={{ color: '#fff', fillColor: '#1f6a54', fillOpacity: 1, weight: 4 }}>
                  <Popup>{selection.destinationQuery || 'Point sélectionné'}</Popup>
                </CircleMarker>
              )}
            </MapContainer>
            <span className="location-modal__map-hint"><MapPin aria-hidden="true" />Vous pouvez aussi cliquer directement sur la carte</span>
          </div>
        </div>
      </section>
    </div>,
    document.body,
  )
}

export function LocationPicker({
  accessToken,
  value,
  title,
  description,
  onChange,
}: {
  accessToken: string
  value: Location
  title: string
  description: string
  onChange: (location: Location) => void
}) {
  const [open, setOpen] = useState(false)
  const confirmed = hasCoordinates(value)

  return (
    <>
      <div className={`location-picker${confirmed ? ' is-confirmed' : ''}`}>
        <span className="location-picker__icon" aria-hidden="true"><MapPin /></span>
        <div>
          <small>{confirmed ? 'Lieu confirmé' : 'Lieu à confirmer'}</small>
          <strong>{value.destinationQuery || 'Aucun lieu sélectionné'}</strong>
          {confirmed && <span>{value.latitude?.toFixed(6)}, {value.longitude?.toFixed(6)}</span>}
        </div>
        <button className="button button--secondary" type="button" onClick={() => setOpen(true)}>
          <MapPinned aria-hidden="true" />{confirmed ? 'Modifier' : 'Choisir sur la carte'}
        </button>
      </div>
      {open && (
        <LocationPickerDialog
          accessToken={accessToken}
          value={value}
          title={title}
          description={description}
          onConfirm={onChange}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  )
}
