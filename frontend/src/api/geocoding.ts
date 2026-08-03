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

interface GeocodingErrorResponse {
  code?: string
  message?: string
}

export async function searchAddress(
  query: string,
  accessToken: string,
  signal?: AbortSignal,
): Promise<GeocodingSearchResponse> {
  const parameters = new URLSearchParams({ q: query })
  const response = await fetch(`${API_BASE_URL}/geocoding/search?${parameters}`, {
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
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
