import type { ClientFinance } from './admin'

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
  contactNumbers?: ClientContactNumber[]
  payoutDestinations?: ClientPayoutDestination[]
  version: number
}

export interface ClientContactNumber {
  id: string
  label?: string | null
  number: string
  priority: number
  active: boolean
}

export interface ClientPayoutDestination {
  id: string
  operator: string
  accountHolder: string
  number: string
  priority: number
  active: boolean
}

export interface ClientContactNumberInput {
  id?: string
  label?: string | null
  number: string
  priority: number
  active: boolean
}

export interface ClientPayoutDestinationInput {
  id?: string
  operator: string
  accountHolder: string
  number: string
  priority: number
  active: boolean
}

export interface ClientReportTotals {
  packageCount: number
  deliveredCount: number
  cancelledCount: number
  postponedCount: number
  returnRequiredCount: number
  returnedToSenderCount: number
  amountCollectedAriary: number
  deliveryFeesAriary: number
  amountToRemitAriary: number
}

export interface ClientReportSummary {
  reportId: string
  serviceDate: string
  totals: ClientReportTotals
}

export interface ClientReportPackage {
  packageId: string
  recipientName: string
  recipientContact: string
  pickupLabel: string
  destinationLabel: string
  outcome: DeliveryPackageOutcome
  comment?: string | null
  actualAmountCollectedAriary: number
  appliedDeliveryFeeAriary: number
  amountToRemitAriary: number
  mobileMoneyPaid: boolean
  returnedToSender: boolean
}

export interface ClientDailyReport {
  reportId: string
  serviceDate: string
  clientDisplayName: string
  totals: ClientReportTotals
  packages: ClientReportPackage[]
}

export interface ClientReportDownload {
  blob: Blob
  filename: string
}

export interface UpdateClientProfileInput {
  displayName: string
  phone: string
  defaultPickup: ClientDefaultPickup | null
}

export type DeliveryRequestStatus = 'PENDING' | 'PARTIALLY_DISPATCHED' | 'DISPATCHED' | 'CANCELLED'
export type DeliveryPackageStatus = 'PENDING' | 'DISPATCHED' | 'UNASSIGNED'
export type DeliveryPackageOutcome = 'DELIVERED' | 'CANCELLED' | 'POSTPONED' | 'RETURN_TO_SENDER'
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
  pricingZoneId?: string | null
  pricingZoneName?: string | null
  deliveryFeeAriary?: number | null
  calculatedDeliveryFeeAriary?: number | null
  finalDeliveryFeeAriary?: number | null
  distanceFromCenterMeters?: number | null
  pricingZoneMaxDistanceMeters?: number | null
  pricingPolicyVersion?: number | null
  effectiveWeightKg: number
  weightSource: PackageWeightSource
  status: DeliveryPackageStatus
  unassignedReason?: string | null
  finalOutcome?: DeliveryPackageOutcome | null
  actualCollectedAriary?: number | null
  outcomeNote?: string | null
  outcomeRecordedAt?: string | null
  returnCompletedAt?: string | null
  carriedForwardFromPackageId?: string | null
  dispatchPlanId?: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface DeliveryPricingQuote {
  pricingZoneId: string
  pricingZoneName: string
  deliveryFeeAriary: number
  distanceFromCenterMeters: number
  pricingZoneMaxDistanceMeters: number
  pricingPolicyVersion: number
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
  packages: CreateDeliveryPackageInput[]
}

interface ApiErrorPayload {
  code?: string
  message?: string
  violations?: Array<{ field: string; message: string }>
}

interface CapacityExceededPayload extends ApiErrorPayload {
  code: 'DAILY_CAPACITY_EXCEEDED'
  requestedDate: string
  limit: number
  current: number
  requested: number
  projected: number
  remaining: number
  excess: number
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

export class DailyCapacityExceededError extends ClientApiError {
  constructor(
    message: string,
    public readonly requestedDate: string,
    public readonly limit: number,
    public readonly current: number,
    public readonly requested: number,
    public readonly projected: number,
    public readonly remaining: number,
    public readonly excess: number,
  ) {
    super(message, 'DAILY_CAPACITY_EXCEEDED', 409)
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
    if (response.status === 409 && error.code === 'DAILY_CAPACITY_EXCEEDED') {
      const capacity = error as CapacityExceededPayload
      throw new DailyCapacityExceededError(
        capacity.message ?? 'La capacité maximale de cette journée est atteinte.',
        capacity.requestedDate,
        capacity.limit,
        capacity.current,
        capacity.requested,
        capacity.projected,
        capacity.remaining,
        capacity.excess,
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

export function updateClientContactNumbers(
  version: number,
  items: ClientContactNumberInput[],
  accessToken: string,
): Promise<ClientProfile> {
  return clientRequest<ClientProfile>('profile/contact-numbers', accessToken, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ version, items }),
  })
}

export function updateClientPayoutDestinations(
  version: number,
  items: ClientPayoutDestinationInput[],
  accessToken: string,
): Promise<ClientProfile> {
  return clientRequest<ClientProfile>('profile/payout-destinations', accessToken, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ version, items }),
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

export function cancelDeliveryPackage(
  packageId: string,
  version: number,
  accessToken: string,
): Promise<void> {
  return clientRequest<void>(`delivery-packages/${encodeURIComponent(packageId)}/cancel`, accessToken, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ version }),
  })
}

export function quoteDeliveryPrice(
  destination: ClientLocation,
  accessToken: string,
  signal?: AbortSignal,
): Promise<DeliveryPricingQuote> {
  return clientRequest<DeliveryPricingQuote>('delivery-pricing/quote', accessToken, {
    method: 'POST',
    signal,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ destination }),
  })
}

export function listClientReports(accessToken: string): Promise<ClientReportSummary[]> {
  return clientRequest<ClientReportSummary[]>('reports', accessToken)
}

export function getClientFinance(accessToken: string): Promise<ClientFinance> {
  return clientRequest<ClientFinance>('finance', accessToken)
}

export function getClientReport(serviceDate: string, accessToken: string): Promise<ClientDailyReport> {
  return clientRequest<ClientDailyReport>(`reports/${encodeURIComponent(serviceDate)}`, accessToken)
}

export async function downloadClientReport(
  serviceDate: string,
  format: 'pdf' | 'xlsx',
  accessToken: string,
): Promise<ClientReportDownload> {
  const response = await fetch(`${API_BASE_URL}/client/reports/${encodeURIComponent(serviceDate)}.${format}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  if (!response.ok) {
    const payload = await response.json().catch(() => undefined) as ApiErrorPayload | undefined
    throw new ClientApiError(
      payload?.message ?? `Le rapport n’a pas pu être téléchargé (${response.status}).`,
      payload?.code ?? 'CLIENT_REPORT_DOWNLOAD_FAILED',
      response.status,
      payload?.violations ?? [],
    )
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const utf8Name = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const simpleName = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  return {
    blob: await response.blob(),
    filename: decodeURIComponent(utf8Name ?? simpleName ?? `azy-livraison-${serviceDate}.${format}`),
  }
}

export async function downloadClientPaymentProof(
  paymentId: string,
  accessToken: string,
): Promise<ClientReportDownload> {
  const response = await fetch(`${API_BASE_URL}/client/finance/payments/${encodeURIComponent(paymentId)}/proof`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  if (!response.ok) {
    const payload = await response.json().catch(() => undefined) as ApiErrorPayload | undefined
    throw new ClientApiError(
      payload?.message ?? `Le justificatif n’a pas pu être téléchargé (${response.status}).`,
      payload?.code ?? 'CLIENT_PAYMENT_PROOF_DOWNLOAD_FAILED',
      response.status,
      payload?.violations ?? [],
    )
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const utf8Name = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const simpleName = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  return {
    blob: await response.blob(),
    filename: decodeURIComponent(utf8Name ?? simpleName ?? `justificatif-${paymentId}`),
  }
}
