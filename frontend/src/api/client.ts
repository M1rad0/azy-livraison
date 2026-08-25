const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export interface ClientLocation {
  label: string
  query?: string
  latitude: number
  longitude: number
}

export interface ClientDefaultPickup {
  label: string
  destinationQuery?: string
  latitude: number
  longitude: number
}

export interface ClientProfile {
  accountId: string
  username: string
  displayName: string
  phone: string
  defaultPickup: ClientDefaultPickup | null
  version: number
}

export interface UpdateClientProfileInput {
  displayName: string
  phone: string
  defaultPickup: ClientDefaultPickup | null
}

export type DeliveryRequestStatus = 'PENDING' | 'PARTIALLY_DISPATCHED' | 'DISPATCHED'
export type DeliveryPackageStatus = 'PENDING' | 'DISPATCHED' | 'UNASSIGNED'
export type PackageWeightSource = 'PROVIDED' | 'DEFAULT_SETTING'

export interface DeliveryPackageRecord {
  id: string
  sequenceNumber: number
  recipientName: string
  faritra: boolean
  destination: ClientLocation
  contact: string
  amountToCollectAriary: number
  mobileMoneyPaid: boolean
  effectiveWeightKg: number
  weightSource: PackageWeightSource
  status: DeliveryPackageStatus
  unassignedReason?: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface CapacitySnapshot {
  limit: number
  current: number
  requested: number
  projected: number
  warning: boolean
  accepted: boolean
}

export interface DeliveryRequestRecord {
  id: string
  ownerAccountId: string
  ownerDisplayName: string
  ownerPhone?: string | null
  requestedDate: string
  pickup: ClientLocation
  capacity: CapacitySnapshot
  status: DeliveryRequestStatus
  version: number
  createdAt: string
  updatedAt: string
  packages: DeliveryPackageRecord[]
}

export interface CreateDeliveryPackageInput {
  recipientName: string
  faritra: boolean
  destination: ClientLocation
  contact: string
  amountToCollectAriary: number
  mobileMoneyPaid: boolean
  weightKg?: number
}

export interface CreateDeliveryRequestInput {
  requestedDate: string
  pickup: ClientLocation
  saveAsDefaultPickup: boolean
  acceptCapacityOverflow: boolean
  packages: CreateDeliveryPackageInput[]
}

interface ApiErrorPayload {
  code?: string
  message?: string
  violations?: Array<{ field: string; message: string }>
}

interface CapacityConfirmationPayload extends ApiErrorPayload {
  code: 'DAILY_CAPACITY_CONFIRMATION_REQUIRED'
  limit: number
  current: number
  requested: number
  projected: number
}

export class ClientApiError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly status: number,
    public readonly violations: Array<{ field: string; message: string }> = [],
  ) {
    super(message)
  }
}

export class CapacityConfirmationRequiredError extends ClientApiError {
  constructor(
    message: string,
    public readonly limit: number,
    public readonly current: number,
    public readonly requested: number,
    public readonly projected: number,
  ) {
    super(message, 'DAILY_CAPACITY_CONFIRMATION_REQUIRED', 409)
  }
}

async function clientRequest<T>(path: string, accessToken: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}/client/${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
      ...init?.headers,
    },
  })
  const payload = await response.json().catch(() => undefined) as T | ApiErrorPayload | undefined

  if (!response.ok) {
    const error = (payload ?? {}) as ApiErrorPayload
    if (response.status === 409 && error.code === 'DAILY_CAPACITY_CONFIRMATION_REQUIRED') {
      const capacity = error as CapacityConfirmationPayload
      throw new CapacityConfirmationRequiredError(
        capacity.message ?? 'Le nombre de colis prévu pour cette journée dépasse la capacité habituelle.',
        capacity.limit,
        capacity.current,
        capacity.requested,
        capacity.projected,
      )
    }
    throw new ClientApiError(
      error.message ?? `La requête a échoué avec le statut ${response.status}.`,
      error.code ?? 'CLIENT_API_ERROR',
      response.status,
      error.violations ?? [],
    )
  }

  return payload as T
}

export function getClientProfile(accessToken: string): Promise<ClientProfile> {
  return clientRequest<ClientProfile>('profile', accessToken)
}

export function updateClientProfile(
  input: UpdateClientProfileInput,
  accessToken: string,
): Promise<ClientProfile> {
  return clientRequest<ClientProfile>('profile', accessToken, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}

export function listDeliveryRequests(accessToken: string): Promise<DeliveryRequestRecord[]> {
  return clientRequest<DeliveryRequestRecord[]>('delivery-requests', accessToken)
}

export function createDeliveryRequest(
  input: CreateDeliveryRequestInput,
  accessToken: string,
): Promise<DeliveryRequestRecord> {
  return clientRequest<DeliveryRequestRecord>('delivery-requests', accessToken, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  })
}
