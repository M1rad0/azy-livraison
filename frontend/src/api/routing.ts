const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export interface RoutingPoint {
  id: string
  latitude: number
  longitude: number
}

export interface RoutingWaypoint {
  id: string
  inputLatitude: number
  inputLongitude: number
  snappedLatitude: number
  snappedLongitude: number
  distanceToRoadMeters: number
  roadName?: string
}

export interface RoutingLeg {
  fromIndex: number
  toIndex: number
  fromId: string
  toId: string
  distanceMeters: number
  durationSeconds: number
  summary?: string
}

export interface GeoJsonLineString {
  type: 'LineString'
  coordinates: [number, number][]
}

export interface RoutingRouteResponse {
  provider: string
  preset: string
  pointCount: number
  distanceMeters: number
  durationSeconds: number
  geometry: GeoJsonLineString
  waypoints: RoutingWaypoint[]
  legs: RoutingLeg[]
}

interface RoutingErrorResponse {
  code?: string
  message?: string
}

export async function calculateRoute(
  points: RoutingPoint[],
  accessToken: string,
  signal?: AbortSignal,
): Promise<RoutingRouteResponse> {
  const response = await fetch(`${API_BASE_URL}/routing/route`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ points }),
    signal,
  })
  const payload = (await response.json().catch(() => undefined)) as
    | RoutingRouteResponse
    | RoutingErrorResponse
    | undefined

  if (!response.ok || !payload || !('geometry' in payload)) {
    throw new Error(
      payload && 'message' in payload && payload.message
        ? payload.message
        : `Le calcul d'itinéraire a échoué avec le statut ${response.status}.`,
    )
  }
  return payload
}
