const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export interface GeocodingBoundingBox {
  south: number
  north: number
  west: number
  east: number
}

export interface GeocodingCandidate {
  id: string
  osmType: string
  osmId: number
  displayName: string
  latitude: number
  longitude: number
  category?: string
  type?: string
  importance?: number
  boundingBox?: GeocodingBoundingBox
}

export interface GeocodingSearchResponse {
  query: string
  provider: string
  cached: boolean
  cachedAt?: string
  resultCount: number
  candidates: GeocodingCandidate[]
}

export interface GeocodingSearchOptions {
  purpose: 'DESTINATION'
  requestKind: 'NORMAL' | 'EXPRESS'
  clientAccountId?: string
}

interface GeocodingErrorResponse {
  code?: string
  message?: string
}

export async function searchAddress(
  query: string,
  accessToken: string,
  signal?: AbortSignal,
  options?: GeocodingSearchOptions,
): Promise<GeocodingSearchResponse> {
  const parameters = new URLSearchParams({ q: query })
  if (options) {
    parameters.set('purpose', options.purpose)
    parameters.set('requestKind', options.requestKind)
    if (options.clientAccountId) parameters.set('clientAccountId', options.clientAccountId)
  }
  const response = await fetch(`${API_BASE_URL}/geocoding/search?${parameters}`, {
    headers: {
      Accept: 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    signal,
  })
  const payload = (await response.json().catch(() => undefined)) as
    | GeocodingSearchResponse
    | GeocodingErrorResponse
    | undefined

  if (!response.ok || !payload || !('candidates' in payload)) {
    throw new Error(
      payload && 'message' in payload && payload.message
        ? payload.message
        : `La recherche a échoué avec le statut ${response.status}.`,
    )
  }
  return payload
}
