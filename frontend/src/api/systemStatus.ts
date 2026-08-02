export type ServiceState = 'UP' | 'DOWN'

export interface SystemStatus {
  service: ServiceState
  database: ServiceState
  checkedAt: string
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export async function getSystemStatus(signal?: AbortSignal): Promise<SystemStatus> {
  const response = await fetch(`${API_BASE_URL}/system/status`, {
    headers: { Accept: 'application/json' },
    signal,
  })

  const payload = (await response.json()) as SystemStatus

  if (!response.ok && response.status !== 503) {
    throw new Error(`L'API a répondu avec le statut ${response.status}.`)
  }

  return payload
}

