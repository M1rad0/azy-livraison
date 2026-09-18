import { useEffect, useId, useRef, useState } from 'react'
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

function MapVisibilityController({ visible }: { visible: boolean }) {
  const map = useMap()

  useEffect(() => {
    if (!visible) return
    const timeout = window.setTimeout(() => map.invalidateSize({ animate: false }), 0)
    return () => window.clearTimeout(timeout)
  }, [map, visible])

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
  const [mobilePanel, setMobilePanel] = useState<'search' | 'map'>('search')
  const searchRequest = useRef<AbortController | null>(null)

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => {
      searchRequest.current?.abort()
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', closeOnEscape)
    }
  }, [onClose])

  async function search() {
    const searchedQuery = query.trim()
    if (searchedQuery.length < 3 || searchRequest.current) return
    const controller = new AbortController()
    searchRequest.current = controller
    setBusy(true)
    setMessage(undefined)
    setCandidates([])
    try {
      const result = await searchAddress(searchedQuery, accessToken, controller.signal)
      if (controller.signal.aborted) return
      setCandidates(result.candidates)
      if (result.candidates.length === 0) setMessage('Aucun lieu trouvé à Madagascar.')
    } catch (error) {
      if (controller.signal.aborted) return
      setMessage(error instanceof Error ? error.message : 'Recherche impossible.')
    } finally {
      if (searchRequest.current === controller) {
        searchRequest.current = null
        setBusy(false)
      }
    }
  }

  function updateQuery(nextQuery: string) {
    searchRequest.current?.abort()
    searchRequest.current = null
    setBusy(false)
    setQuery(nextQuery)
    setCandidates([])
    setMessage(undefined)
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

        <nav className="location-modal__mobile-tabs" aria-label="Mode de sélection du lieu">
          <button type="button" aria-pressed={mobilePanel === 'search'} className={mobilePanel === 'search' ? 'is-active' : undefined} onClick={() => setMobilePanel('search')}>
            <Search aria-hidden="true" />Recherche
          </button>
          <button type="button" aria-pressed={mobilePanel === 'map'} className={mobilePanel === 'map' ? 'is-active' : undefined} onClick={() => setMobilePanel('map')}>
            <MapPinned aria-hidden="true" />Carte
          </button>
        </nav>

        <div className={`location-modal__body is-${mobilePanel}-view`}>
          <aside className="location-modal__search">
            <p>{description}</p>
            <div className="location-modal__searchbar">
              <input
                autoFocus
                aria-label="Rechercher un lieu à Madagascar"
                value={query}
                minLength={3}
                placeholder="Adresse, quartier ou point de repère"
                onChange={(event) => updateQuery(event.target.value)}
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
              <MapVisibilityController visible={mobilePanel === 'map'} />
              <MapClickHandler onPick={selectMapPoint} />
              {hasCoordinates(selection) && (
                <CircleMarker center={[selection.latitude as number, selection.longitude as number]} radius={10} pathOptions={{ color: '#fff', fillColor: '#f45b0b', fillOpacity: 1, weight: 4 }}>
                  <Popup>{selection.destinationQuery || 'Point sélectionné'}</Popup>
                </CircleMarker>
              )}
            </MapContainer>
            <span className="location-modal__map-hint"><MapPin aria-hidden="true" />Vous pouvez aussi cliquer directement sur la carte</span>
            <div className={`location-modal__map-actions${hasCoordinates(selection) ? ' is-confirmed' : ''}`}>
              <div>
                <small>{hasCoordinates(selection) ? 'Point prêt à être utilisé' : 'Touchez la carte pour placer le point'}</small>
                <strong>{selection.destinationQuery || 'Aucun lieu sélectionné'}</strong>
              </div>
              <div>
                <button className="button button--secondary" type="button" onClick={() => setMobilePanel('search')}>
                  <Search aria-hidden="true" />Recherche
                </button>
                <button
                  className="button"
                  type="button"
                  disabled={!hasCoordinates(selection)}
                  onClick={() => {
                    onConfirm(selection)
                    onClose()
                  }}
                >
                  <Check aria-hidden="true" />Utiliser ce point
                </button>
              </div>
            </div>
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
  const locationQuery = value.destinationQuery?.trim()
  const locationName = value.label?.trim()
  const distinctLocationName = locationName && locationQuery && locationName !== locationQuery
    ? locationName
    : undefined

  return (
    <>
      <div className={`location-picker${confirmed ? ' is-confirmed' : ''}`}>
        <span className="location-picker__icon" aria-hidden="true"><MapPin /></span>
        <div className="location-picker__details">
          <small>{confirmed ? 'Lieu confirmé' : 'Lieu à confirmer'}</small>
          <strong>{locationQuery || locationName || (confirmed ? 'Point sélectionné sur la carte' : 'Aucun lieu sélectionné')}</strong>
          {distinctLocationName && <span className="location-picker__label">Libellé : {distinctLocationName}</span>}
          {confirmed && <span className="location-picker__coordinates">Coordonnées : {value.latitude?.toFixed(6)}, {value.longitude?.toFixed(6)}</span>}
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
