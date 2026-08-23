import type {
  HubExecution,
  OperationalPolicySnapshot,
  OptimizationResult,
  StrategyMetrics,
} from './optimization'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export type VehicleType = 'MOTO' | 'BICYCLE'
export type DeliveryRequestStatus = 'PENDING' | 'PARTIALLY_DISPATCHED' | 'DISPATCHED' | 'CANCELLED'
export type DeliveryPackageStatus = 'PENDING' | 'DISPATCHED' | 'UNASSIGNED'
export type DeliveryPackageOutcome = 'DELIVERED' | 'CANCELLED' | 'POSTPONED' | 'RETURN_TO_SENDER'
export type DispatchStatus = 'GENERATING' | 'READY' | 'READY_WITH_WARNINGS' | 'FAILED'
export type OperationDayStatus = 'OPEN' | 'DISPATCHING' | 'DISPATCHED' | 'CLOSED'
export type ReportFormat = 'pdf' | 'xlsx'

export interface ConfirmedLocation {
  label?: string
  destinationQuery?: string
  query?: string
  latitude: number
  longitude: number
}

export interface AdminQueueStats {
  dailyLimit: number
  pendingRequestCount: number
  partiallyDispatchedRequestCount: number
  pendingPackageCount: number
  remainingCapacity: number
  capacityExceeded: boolean
}

export interface DeliveryPackage {
  id: string
  sequenceNumber: number
  recipientName: string
  faritra: boolean
  destination: ConfirmedLocation
  contact: string
  amountToCollectAriary: number
  mobileMoneyPaid: boolean
  pricingZoneId?: string | null
  pricingZoneName?: string | null
  deliveryFeeAriary?: number | null
  distanceFromCenterMeters?: number | null
  pricingZoneMaxDistanceMeters?: number | null
  pricingPolicyVersion?: number | null
  effectiveWeightKg: number
  weightSource: 'PROVIDED' | 'DEFAULT_SETTING'
  status: DeliveryPackageStatus
  unassignedReason?: string
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

export interface DeliveryRequest {
  id: string
  ownerAccountId: string
  ownerDisplayName: string
  ownerPhone?: string | null
  requestedDate: string
  pickup: ConfirmedLocation
  capacity: {
    limit: number
    current: number
    requested: number
    projected: number
    warning: boolean
    accepted: boolean
  }
  status: DeliveryRequestStatus
  version: number
  createdAt: string
  updatedAt: string
  packages: DeliveryPackage[]
}

export interface AdminQueue {
  date: string
  stats: AdminQueueStats
  requests: DeliveryRequest[]
}

export interface Courier {
  id: string
  accountId?: string
  name: string
  contact?: string
  active: boolean
  vehicleType: VehicleType
  maxWeightKg?: number
  averageSpeedKmh?: number
  effectiveMaxWeightKg: number
  effectiveAverageSpeedKmh: number
  defaultDepartureTime: string
  departureLocation: ConfirmedLocation
  version: number
  createdAt: string
  updatedAt: string
}

export interface CourierInput {
  accountId?: string
  name: string
  contact?: string
  active?: boolean
  vehicleType: VehicleType
  maxWeightKg?: number
  averageSpeedKmh?: number
  defaultDepartureTime: string
  departureLocation: ConfirmedLocation
  version?: number
}

export interface VehiclePreset {
  vehicleType: VehicleType
  maxWeightKg: number
  averageSpeedKmh: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface OperationalSettings {
  dailyPackageLimit: number
  defaultHub?: ConfirmedLocation
  defaultPackageWeightKg: number
  pickupServiceMinutes: number
  hubServiceMinutes: number
  deliveryServiceMinutes: number
  earlyToleranceMinutes: number
  lateToleranceMinutes: number
  optimizationTimeLimitSeconds: number
  routingPreset: 'MAIN_ROADS'
  version: number
  createdAt: string
  updatedAt: string
}

export interface DeliveryPricingZone {
  id: string
  name: string
  maxDistanceMeters: number
  deliveryFeeAriary: number
}

export interface DeliveryPricingPolicy {
  version: number
  center: ConfirmedLocation
  zones: DeliveryPricingZone[]
  createdAt: string
  updatedAt: string
}

export interface DeliveryPricingPolicyInput {
  version: number
  center: ConfirmedLocation
  zones: Array<{
    id?: string
    name: string
    maxDistanceMeters: number
    deliveryFeeAriary: number
  }>
}

export interface DispatchPlanSummary {
  id: string
  serviceDate: string
  status: DispatchStatus
  strategy: 'VIA_HUB'
  hub: ConfirmedLocation
  selectedPackageCount: number
  assignedPackageCount?: number
  unassignedPackageCount?: number
  selectedCourierCount: number
  usedCourierCount?: number
  totalDistanceMeters?: number
  totalDurationSeconds?: number
  meetingAt?: string
  redistributionCompletedAt?: string
  completedAt?: string
  failureCode?: string
  failureMessage?: string
  createdAt: string
  completedProcessingAt?: string
}

export interface DispatchPackageOutcome {
  packageId: string
  deliveryRequestId: string
  assigned: boolean
  collectorCourierId?: string
  distributorCourierId?: string
  transferred: boolean
  estimatedDeliveryAt?: string
  deliveryOrder?: number
  unassignedReason?: string
  unassignedMessage?: string
  recipientName: string
  faritra: boolean
  contact: string
  amountToCollectAriary: number
  mobileMoneyPaid: boolean
  effectiveWeightKg: number
  pricingZoneId?: string | null
  pricingZoneName?: string | null
  deliveryFeeAriary?: number | null
  distanceFromCenterMeters?: number | null
  pricingZoneMaxDistanceMeters?: number | null
  pricingPolicyVersion?: number | null
  destination: ConfirmedLocation
  cashToCollectAriary?: number
}

export interface DispatchPlan {
  plan: DispatchPlanSummary
  operationalPolicy?: OperationalPolicySnapshot
  result?: OptimizationResult
  hubExecution?: HubExecution
  metrics?: StrategyMetrics
  packages: DispatchPackageOutcome[]
}

export interface CreateDispatchInput {
  serviceDate: string
  courierIds: string[]
  hubOverride?: ConfirmedLocation
}

export interface DailyFinancialTotals {
  recoveredAriary: number
  deliveryFeesAriary: number
  amountToRemitAriary: number
}

export interface DailyOperationCounts {
  total: number
  finalized: number
  awaitingOutcome: number
  delivered: number
  cancelled: number
  postponed: number
  returnToSender: number
  returnsCompleted: number
}

export interface ClientDaySummary {
  accountId: string
  displayName: string
  phone?: string | null
  packageCount: number
  finalizedCount: number
  totals: DailyFinancialTotals
}

export interface DailyOperationPackage {
  packageId: string
  deliveryRequestId: string
  ownerAccountId: string
  ownerDisplayName: string
  ownerPhone?: string | null
  requestedDate: string
  pickup: ConfirmedLocation
  sequenceNumber: number
  recipientName: string
  faritra: boolean
  destination: ConfirmedLocation
  contact: string
  amountToCollectAriary: number
  mobileMoneyPaid: boolean
  pricingZoneId?: string | null
  pricingZoneName?: string | null
  deliveryFeeAriary?: number | null
  technicalStatus: DeliveryPackageStatus
  unassignedReason?: string | null
  finalOutcome?: DeliveryPackageOutcome | null
  actualCollectedAriary?: number | null
  outcomeNote?: string | null
  outcomeRecordedAt?: string | null
  returnCompletedAt?: string | null
  carriedForwardFromPackageId?: string | null
  carriedForwardToPackageId?: string | null
  dispatchPlanId?: string | null
  distributorCourierId?: string | null
  distributorCourierName?: string | null
  estimatedDeliveryAt?: string | null
  finalState: boolean
  recoveredAriary: number
  chargedDeliveryFeeAriary: number
  amountToRemitAriary: number
  version: number
}

export interface DailyOperation {
  serviceDate: string
  status: OperationDayStatus
  admissionsClosed: boolean
  dispatchPlanId?: string | null
  closureRevision: number
  closedAt?: string | null
  reopenedAt?: string | null
  reopenReason?: string | null
  version: number
  counts: DailyOperationCounts
  totals: DailyFinancialTotals
  clients: ClientDaySummary[]
  packages: DailyOperationPackage[]
}

export interface CarryForwardResult {
  deliveryRequestId: string
  packageId: string
  requestedDate: string
}

export interface DownloadedReport {
  blob: Blob
  filename: string
}

interface ApiErrorPayload {
  code?: string
  message?: string
  errors?: Array<{ message?: string }>
}

export class AdminApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code = 'ADMIN_REQUEST_FAILED',
  ) {
    super(message)
  }
}

async function adminRequest<T>(path: string, accessToken: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}/admin/${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
      ...init?.headers,
    },
  })

  if (!response.ok) {
    const payload = (await response.json().catch(() => ({}))) as ApiErrorPayload
    throw new AdminApiError(
      payload.message
        ?? payload.errors?.find((error) => error.message)?.message
        ?? `La demande a échoué avec le statut ${response.status}.`,
      response.status,
      payload.code,
    )
  }

  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

function jsonRequest(method: 'POST' | 'PUT', body: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }
}

export function getAdminQueue(date: string, accessToken: string, signal?: AbortSignal) {
  return adminRequest<AdminQueue>(`queue?date=${encodeURIComponent(date)}`, accessToken, { signal })
}

export function listDeliveryRequests(
  accessToken: string,
  filters: {
    date?: string
    from?: string
    to?: string
    status?: DeliveryRequestStatus
    client?: string
    pendingOnly?: boolean
  } = {},
  signal?: AbortSignal,
) {
  const query = new URLSearchParams()
  if (filters.date) query.set('date', filters.date)
  if (filters.from) query.set('from', filters.from)
  if (filters.to) query.set('to', filters.to)
  if (filters.status) query.set('status', filters.status)
  if (filters.client?.trim()) query.set('client', filters.client.trim())
  if (filters.pendingOnly) query.set('pendingOnly', 'true')
  const suffix = query.size > 0 ? `?${query}` : ''
  return adminRequest<DeliveryRequest[]>(`delivery-requests${suffix}`, accessToken, { signal })
}

export function getOperationalSettings(accessToken: string, signal?: AbortSignal) {
  return adminRequest<OperationalSettings>('settings', accessToken, { signal })
}

export function updateOperationalSettings(settings: OperationalSettings, accessToken: string) {
  const { createdAt: _createdAt, updatedAt: _updatedAt, ...input } = settings
  return adminRequest<OperationalSettings>('settings', accessToken, jsonRequest('PUT', input))
}

export function getDeliveryPricingPolicy(accessToken: string, signal?: AbortSignal) {
  return adminRequest<DeliveryPricingPolicy>('settings/pricing', accessToken, { signal })
}

export function updateDeliveryPricingPolicy(input: DeliveryPricingPolicyInput, accessToken: string) {
  return adminRequest<DeliveryPricingPolicy>('settings/pricing', accessToken, jsonRequest('PUT', input))
}

export function listVehiclePresets(accessToken: string, signal?: AbortSignal) {
  return adminRequest<VehiclePreset[]>('vehicle-presets', accessToken, { signal })
}

export function updateVehiclePresets(presets: VehiclePreset[], accessToken: string) {
  return adminRequest<VehiclePreset[]>('vehicle-presets', accessToken, jsonRequest('PUT', presets.map((preset) => ({
    vehicleType: preset.vehicleType,
    maxWeightKg: preset.maxWeightKg,
    averageSpeedKmh: preset.averageSpeedKmh,
    version: preset.version,
  }))))
}

export function listCouriers(accessToken: string, signal?: AbortSignal) {
  return adminRequest<Courier[]>('couriers', accessToken, { signal })
}

export function createCourier(input: CourierInput, accessToken: string) {
  const { active: _active, version: _version, ...body } = input
  return adminRequest<Courier>('couriers', accessToken, jsonRequest('POST', body))
}

export function updateCourier(id: string, input: CourierInput, accessToken: string) {
  return adminRequest<Courier>(`couriers/${id}`, accessToken, jsonRequest('PUT', {
    ...input,
    active: input.active ?? true,
  }))
}

export function disableCourier(id: string, version: number, accessToken: string) {
  return adminRequest<Courier>(`couriers/${id}/disable?version=${version}`, accessToken, { method: 'PATCH' })
}

export function listDispatchPlans(date: string | undefined, accessToken: string, signal?: AbortSignal) {
  const suffix = date ? `?date=${encodeURIComponent(date)}` : ''
  return adminRequest<DispatchPlanSummary[]>(`dispatch-plans${suffix}`, accessToken, { signal })
}

export function getDispatchPlan(id: string, accessToken: string, signal?: AbortSignal) {
  return adminRequest<DispatchPlan>(`dispatch-plans/${id}`, accessToken, { signal })
}

export function createDispatchPlan(
  input: CreateDispatchInput,
  idempotencyKey: string,
  accessToken: string,
) {
  return adminRequest<DispatchPlan>('dispatch-plans', accessToken, {
    ...jsonRequest('POST', input),
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
  })
}

export function cancelAdminDeliveryPackage(packageId: string, version: number, accessToken: string) {
  return adminRequest<void>(`delivery-packages/${encodeURIComponent(packageId)}/cancel`, accessToken, {
    ...jsonRequest('POST', { version }),
  })
}

export function getDeliveryDay(serviceDate: string, accessToken: string, signal?: AbortSignal) {
  return adminRequest<DailyOperation>(`delivery-days/${encodeURIComponent(serviceDate)}`, accessToken, { signal })
}

export function updateDeliveryPackageOutcome(
  serviceDate: string,
  packageId: string,
  input: {
    outcome: Exclude<DeliveryPackageOutcome, 'CANCELLED'>
    actualCollectedAriary?: number | null
    note?: string | null
    version: number
  },
  accessToken: string,
) {
  return adminRequest<DailyOperation>(
    `delivery-days/${encodeURIComponent(serviceDate)}/packages/${encodeURIComponent(packageId)}/outcome`,
    accessToken,
    jsonRequest('PUT', input),
  )
}

export function markReturnCompleted(
  serviceDate: string,
  packageId: string,
  version: number,
  accessToken: string,
) {
  return adminRequest<DailyOperation>(
    `delivery-days/${encodeURIComponent(serviceDate)}/packages/${encodeURIComponent(packageId)}/return-completed`,
    accessToken,
    jsonRequest('POST', { version }),
  )
}

export function carryForwardDeliveryPackage(
  serviceDate: string,
  packageId: string,
  pickup: ConfirmedLocation,
  version: number,
  accessToken: string,
) {
  return adminRequest<CarryForwardResult>(
    `delivery-days/${encodeURIComponent(serviceDate)}/packages/${encodeURIComponent(packageId)}/carry-forward`,
    accessToken,
    jsonRequest('POST', {
      pickup: {
        label: pickup.label?.trim() || pickup.query?.trim() || pickup.destinationQuery?.trim() || 'Point de retrait',
        query: pickup.query?.trim() || pickup.destinationQuery?.trim() || null,
        latitude: pickup.latitude,
        longitude: pickup.longitude,
      },
      version,
    }),
  )
}

export function closeDeliveryDay(serviceDate: string, version: number, accessToken: string) {
  return adminRequest<DailyOperation>(
    `delivery-days/${encodeURIComponent(serviceDate)}/close`,
    accessToken,
    jsonRequest('POST', { version }),
  )
}

export function reopenDeliveryDay(serviceDate: string, version: number, reason: string, accessToken: string) {
  return adminRequest<DailyOperation>(
    `delivery-days/${encodeURIComponent(serviceDate)}/reopen`,
    accessToken,
    jsonRequest('POST', { version, reason }),
  )
}

function reportFilename(response: Response, fallback: string): string {
  const disposition = response.headers.get('Content-Disposition')
  const utf8 = disposition?.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (utf8) {
    try {
      return decodeURIComponent(utf8)
    } catch {
      return utf8
    }
  }
  return disposition?.match(/filename="?([^";]+)"?/i)?.[1] ?? fallback
}

async function downloadAdminReport(path: string, fallback: string, accessToken: string): Promise<DownloadedReport> {
  const response = await fetch(`${API_BASE_URL}/admin/${path}`, {
    headers: {
      Accept: '*/*',
      Authorization: `Bearer ${accessToken}`,
    },
  })
  if (!response.ok) {
    const payload = await response.json().catch(() => ({})) as ApiErrorPayload
    throw new AdminApiError(
      payload.message ?? `Le rapport n'a pas pu être généré (${response.status}).`,
      response.status,
      payload.code,
    )
  }
  return { blob: await response.blob(), filename: reportFilename(response, fallback) }
}

export function downloadGeneralDailyReport(serviceDate: string, format: ReportFormat, accessToken: string) {
  return downloadAdminReport(
    `delivery-days/${encodeURIComponent(serviceDate)}/reports/general.${format}`,
    `azy-livraison-${serviceDate}.${format}`,
    accessToken,
  )
}

export function downloadClientDailyReport(
  serviceDate: string,
  accountId: string,
  format: ReportFormat,
  accessToken: string,
) {
  return downloadAdminReport(
    `delivery-days/${encodeURIComponent(serviceDate)}/reports/clients/${encodeURIComponent(accountId)}.${format}`,
    `azy-livraison-client-${serviceDate}.${format}`,
    accessToken,
  )
}
