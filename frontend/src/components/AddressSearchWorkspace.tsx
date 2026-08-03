import { useState, type FormEvent } from 'react'
import {
  searchAddress,
  type GeocodingCandidate,
  type GeocodingSearchResponse,
} from '../api/geocoding'

export function AddressSearchWorkspace({ accessToken }: { accessToken: string }) {
  const [query, setQuery] = useState('')
  const [result, setResult] = useState<GeocodingSearchResponse>()
  const [selected, setSelected] = useState<GeocodingCandidate>()
  const [errorMessage, setErrorMessage] = useState<string>()
  const [searching, setSearching] = useState(false)

  async function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSearching(true)
    setResult(undefined)
    setSelected(undefined)
    setErrorMessage(undefined)

    try {
      setResult(await searchAddress(query, accessToken))
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : 'La recherche a échoué.')
    } finally {
      setSearching(false)
    }
  }

  return (
    <section className="geocoding" aria-labelledby="geocoding-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Géographie · Madagascar</p>
          <h2 id="geocoding-title">Rechercher une destination</h2>
        </div>
        <p>Recherche Nominatim limitée à Madagascar · confirmation manuelle</p>
      </div>

      <form className="address-search" onSubmit={(event) => void handleSearch(event)}>
        <label htmlFor="address-query">Adresse, quartier ou point de repère</label>
        <div className="address-search__controls">
          <input
            id="address-query"
            value={query}
            minLength={3}
            maxLength={300}
            placeholder="Ex. Analakely, Antananarivo"
            onChange={(event) => setQuery(event.target.value)}
            required
          />
          <button className="button" type="submit" disabled={searching || query.trim().length < 3}>
            {searching ? 'Recherche…' : 'Rechercher'}
          </button>
        </div>
      </form>

      {errorMessage && <p className="form-error geocoding__message" role="alert">{errorMessage}</p>}

      {result && result.candidates.length === 0 && (
        <p className="geocoding-empty" role="status">
          Aucun lieu trouvé. Ajoutez un quartier, une commune ou un point de repère.
        </p>
      )}

      {result && result.candidates.length > 0 && (
        <div className="geocoding-results">
          <div className="geocoding-results__meta">
            <strong>{result.resultCount} proposition{result.resultCount > 1 ? 's' : ''}</strong>
            <span>{result.cached ? 'Résultat réutilisé depuis le cache' : 'Résultat Nominatim'}</span>
          </div>
          <ul className="candidate-list" aria-label="Résultats de la recherche">
            {result.candidates.map((candidate) => (
              <li key={candidate.id}>
                <button
                  className={`candidate${selected?.id === candidate.id ? ' candidate--selected' : ''}`}
                  type="button"
                  onClick={() => setSelected(candidate)}
                >
                  <span className="candidate__marker" aria-hidden="true">{selected?.id === candidate.id ? '✓' : '·'}</span>
                  <span>
                    <strong>{candidate.displayName}</strong>
                    <small>{candidate.latitude.toFixed(6)}, {candidate.longitude.toFixed(6)}</small>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {selected && (
        <div className="selected-location" role="status">
          <span>Destination sélectionnée</span>
          <strong>{selected.displayName}</strong>
          <code>{selected.latitude.toFixed(6)}, {selected.longitude.toFixed(6)}</code>
        </div>
      )}
    </section>
  )
}
