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
export type DispatchStatus = 'GENERATING' | 'READY' | 'READY_WITH_WARNINGS' | 'FAILED' | 'CANCELLED' | 'SUPERSEDED'
export type OperationDayStatus = 'OPEN' | 'DISPATCHING' | 'DISPATCHED' | 'CLOSED'
export type ReportFormat = 'pdf' | 'xlsx'
export type DeliveryRequestKind = 'NORMAL' | 'EXPRESS'
export type ExpressDeliveryStatus = 'PENDING' | 'CONFIRMED' | 'COMPLETED'

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
  desiredDeliveryTime?: string | null
  pricingZoneId?: string | null
  pricingZoneName?: string | null
  deliveryFeeAriary?: number | null
  calculatedDeliveryFeeAriary?: number | null
  finalDeliveryFeeAriary?: number | null
  deliveryFeeOverrideReason?: string | null
  deliveryFeeOverriddenAt?: string | null
  deliveryFeeVersion?: number
  distanceFromCenterMeters?: number | null
  pricingZoneMaxDistanceMeters?: number | null
  pricingPolicyVersion?: number | null
  pricingPolicyId?: number | null
  baseDeliveryFeeAriary?: number | null
  weightSurchargeAriary?: number | null
  timeSurchargeAriary?: number | null
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
  requestKind: DeliveryRequestKind
  expressStatus?: ExpressDeliveryStatus | null
  createdByAdministration: boolean
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

export interface AdminClient {
  accountId: string
  username: string
  displayName: string
  phone: string
  enabled: boolean
  expressDeliveryEnabled: boolean
  version: number
}

export interface AdminClientInput {
  username: string
  displayName: string
  phone: string
  enabled?: boolean
  expressDeliveryEnabled?: boolean
  temporaryPassword?: string
  version?: number
}

export interface ExpressDelivery {
  request: DeliveryRequest
  couriers: Array<{ id: string; name: string }>
  totalWeightKg: number
  selectedCapacityKg: number
  capacityWarning: boolean
  confirmedAt?: string | null
}

export interface Courier {
  id: string
  accountId?: string
  name: string
  contact?: string
  phoneNumber?: string
  payoutDestination?: CourierPayoutDestination | null
  active: boolean
  vehicleType: VehicleType
  maxWeightKg?: number
  averageSpeedKmh?: number
  effectiveMaxWeightKg: number
  effectiveAverageSpeedKmh: number
  commissionNumerator?: number | null
  commissionDenominator?: number | null
  effectiveCommissionNumerator: number
  effectiveCommissionDenominator: number
  defaultDepartureTime: string
  departureLocation: ConfirmedLocation
  version: number
  createdAt: string
  updatedAt: string
}

export interface CourierPayoutDestination {
  operator: string
  accountHolder: string
  number: string
}

export interface CourierInput {
  accountId?: string
  name: string
  contact?: string
  phoneNumber?: string
  payoutDestination?: CourierPayoutDestination | null
  active?: boolean
  vehicleType: VehicleType
  maxWeightKg?: number
  averageSpeedKmh?: number
  commissionNumerator?: number | null
  commissionDenominator?: number | null
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
  defaultCourierCommissionNumerator: number
  defaultCourierCommissionDenominator: number
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

export interface AdvancedPricingPolicy {
  id: number
  clientAccountId?: string | null
  requestKind: DeliveryRequestKind
  clientSpecific: boolean
  version: number
  center: ConfirmedLocation
  defaultWeightKg: number
  zones: DeliveryPricingZone[]
  weightTiers: Array<{ id: string; maxWeightKg: number; surchargeAriary: number }>
  timeSlots: Array<{ id: string; label: string; startTime: string; endTime: string; surchargeAriary: number }>
}

export interface AdvancedPricingPolicyInput {
  clientAccountId?: string | null
  requestKind: DeliveryRequestKind
  version?: number | null
  center: ConfirmedLocation
  defaultWeightKg: number
  zones: Array<{ id?: string; name: string; maxDistanceMeters: number; deliveryFeeAriary: number }>
  weightTiers: Array<{ id?: string; maxWeightKg: number; surchargeAriary: number }>
  timeSlots: Array<{ id?: string; label: string; startTime: string; endTime: string; surchargeAriary: number }>
}

export interface DispatchPlanSummary {
  id: string
  serviceDate: string
  status: DispatchStatus
  mode?: 'AUTOMATIC' | 'MANUAL'
  revision?: number
  version?: number
  supersedesPlanId?: string | null
  archivedAt?: string | null
  archivedByAccountId?: string | null
  archiveReason?: string | null
  editable?: boolean
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
  couriers?: DispatchPlanCourier[]
  stops?: DispatchPlanStop[]
}

export interface DispatchPlanCourier {
  courierId: string
  name: string
  contact?: string | null
  vehicleType: VehicleType
  maxWeightKg: number
  averageSpeedKmh: number
  departureAt: string
  departure: ConfirmedLocation
  used: boolean
  collectionRole: boolean
  distributionRole: boolean
  hubArrivalAt?: string | null
  hubDepartureAt?: string | null
  completedAt?: string | null
  loadAtHubArrivalKg?: number | null
  loadAtHubDepartureKg?: number | null
  routeDistanceMeters?: number | null
  routeDurationSeconds?: number | null
}

export interface DispatchPlanStop {
  id: string
  courierId: string
  order: number
  type: 'START' | 'PICKUP' | 'HUB' | 'DELIVERY' | string
  deliveryRequestId?: string | null
  packageId?: string | null
  label?: string | null
  location: ConfirmedLocation
  estimatedArrivalAt?: string | null
}

export interface CreateDispatchInput {
  serviceDate: string
  courierIds: string[]
  hubOverride?: ConfirmedLocation
}

export interface ManualDispatchAssignmentInput {
  packageId: string
  distributorCourierId: string
  deliveryOrder: number
}

export interface DeliveryFeeUpdateResult {
  packageId: string
  calculatedFeeAriary?: number | null
  finalFeeAriary?: number | null
  mode: 'CALCULATED' | 'MANUAL'
  overrideReason?: string | null
  overriddenAt?: string | null
  overriddenByAccountId?: string | null
  feeVersion: number
  version: number
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
  requestKind?: DeliveryRequestKind
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
  calculatedDeliveryFeeAriary?: number | null
  finalDeliveryFeeAriary?: number | null
  deliveryFeeOverrideReason?: string | null
  deliveryFeeOverriddenAt?: string | null
  deliveryFeeVersion?: number
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
  actualCourierId?: string | null
  actualCourierName?: string | null
  actualCourierContact?: string | null
  actualCourierVehicleType?: VehicleType | null
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

export type PaymentStatus = 'CONFIRMED' | 'VOIDED'
export type ClientPaymentDirection = 'AZY_TO_CLIENT' | 'CLIENT_TO_AZY'
export type SettlementStatus = 'UNPAID' | 'PARTIALLY_PAID' | 'PAID'

export interface PaymentDestination {
  operator: string
  accountHolder: string
  number: string
}

export interface PaymentProof {
  available: boolean
  originalFilename?: string | null
  contentType?: string | null
  sizeBytes?: number | null
  sha256?: string | null
}

export interface ClientSettlement {
  id: string
  reportId: string
  serviceDate: string
  closureRevision: number
  clientDisplayName: string
  amountDueAriary: number
  confirmedSignedPaymentsAriary: number
  remainingAriary: number
  status: SettlementStatus
  createdAt: string
}

export interface ClientPayment {
  id: string
  settlementId: string
  serviceDate: string
  direction: ClientPaymentDirection
  amountAriary: number
  signedAmountAriary: number
  paymentMethod: string
  destination?: PaymentDestination | null
  transactionReference?: string | null
  note?: string | null
  proof: PaymentProof
  status: PaymentStatus
  confirmedAt: string
  confirmedByAccountId?: string | null
  voidedAt?: string | null
  voidedByAccountId?: string | null
  voidReason?: string | null
  version: number
}

export interface ClientFinance {
  clientAccountId: string
  preferredPayoutDestination?: PaymentDestination | null
  totalDueAriary: number
  totalConfirmedSignedPaymentsAriary: number
  netBalanceAriary: number
  amountOwedToClientAriary: number
  amountOwedByClientAriary: number
  settlements: ClientSettlement[]
  payments: ClientPayment[]
}

export interface CreatePaymentInput {
  amountAriary: number
  paymentMethod: string
  destination?: PaymentDestination | null
  transactionReference?: string | null
  note?: string | null
  confirmedAt?: string | null
}

export interface CompensationLine {
  earningId?: string | null
  packageId: string
  courierId: string
  courierName: string
  courierContact?: string | null
  outcome: Extract<DeliveryPackageOutcome, 'DELIVERED' | 'RETURN_TO_SENDER'>
  eligible: boolean
  ineligibilityReason?: string | null
  deliveryFeeAriary: number
  commissionNumerator: number
  commissionDenominator: number
  suggestedAmountAriary: number
  finalAmountAriary?: number | null
  overrideReason?: string | null
  recipientName?: string | null
  destinationLabel?: string | null
}

export interface CourierCompensation {
  serviceDate: string
  closureRevision: number
  finalized: boolean
  finalizedAt?: string | null
  finalizedByAccountId?: string | null
  totalSuggestedAriary: number
  totalFinalAriary: number
  pendingReturnCount: number
  lines: CompensationLine[]
}

export interface CourierEarning {
  id: string
  serviceDate: string
  closureRevision: number
  packageId: string
  outcome: Extract<DeliveryPackageOutcome, 'DELIVERED' | 'RETURN_TO_SENDER'>
  deliveryFeeAriary: number
  commissionNumerator: number
  commissionDenominator: number
  suggestedAmountAriary: number
  finalAmountAriary: number
  overrideReason?: string | null
  createdAt: string
}

export interface CourierPayment {
  id: string
  amountAriary: number
  paymentMethod: string
  destination?: PaymentDestination | null
  transactionReference?: string | null
  note?: string | null
  proof: PaymentProof
  status: PaymentStatus
  confirmedAt: string
  confirmedByAccountId?: string | null
  voidedAt?: string | null
  voidedByAccountId?: string | null
  voidReason?: string | null
  version: number
}

export interface CourierDeliveryHistory {
  packageId: string
  serviceDate: string
  clientDisplayName: string
  recipientName: string
  pickupLabel: string
  destinationLabel: string
  outcome: Extract<DeliveryPackageOutcome, 'DELIVERED' | 'RETURN_TO_SENDER'>
  returnCompletedAt?: string | null
  deliveryFeeAriary: number
  earnedAmountAriary?: number | null
}

export interface CourierFinance {
  courierId: string
  courierName: string
  totalEarnedAriary: number
  totalPaidAriary: number
  remainingToPayAriary: number
  advanceCreditAriary: number
  deliveries: CourierDeliveryHistory[]
  earnings: CourierEarning[]
  payments: CourierPayment[]
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

export function getAdvancedPricingPolicies(accessToken: string, clientAccountId?: string, signal?: AbortSignal) {
  const suffix = clientAccountId ? `?clientAccountId=${encodeURIComponent(clientAccountId)}` : ''
  return adminRequest<AdvancedPricingPolicy[]>(`pricing-policies${suffix}`, accessToken, { signal })
}

export function saveAdvancedPricingPolicy(input: AdvancedPricingPolicyInput, accessToken: string) {
  return adminRequest<AdvancedPricingPolicy>('pricing-policies', accessToken, jsonRequest('PUT', input))
}

export function resetClientPricingPolicy(clientAccountId: string, requestKind: DeliveryRequestKind, accessToken: string) {
  return adminRequest<void>(
    `pricing-policies?clientAccountId=${encodeURIComponent(clientAccountId)}&requestKind=${requestKind}`,
    accessToken,
    { method: 'DELETE' },
  )
}

export function listAdminClients(accessToken: string, signal?: AbortSignal) {
  return adminRequest<AdminClient[]>('clients', accessToken, { signal })
}

export function createAdminClient(input: AdminClientInput, accessToken: string) {
  return adminRequest<{ client: AdminClient; temporaryPassword: string }>(
    'clients', accessToken, jsonRequest('POST', input),
  )
}

export function updateAdminClient(accountId: string, input: AdminClientInput, accessToken: string) {
  return adminRequest<AdminClient>(`clients/${encodeURIComponent(accountId)}`, accessToken, jsonRequest('PUT', input))
}

export function resetAdminClientPassword(accountId: string, accessToken: string) {
  return adminRequest<{ temporaryPassword: string }>(
    `clients/${encodeURIComponent(accountId)}/reset-password`, accessToken, { method: 'POST' },
  )
}

export function createAdminDeliveryRequest(
  clientAccountId: string,
  input: {
    requestedDate: string
    requestKind: DeliveryRequestKind
    pickup: ConfirmedLocation
    saveAsDefaultPickup: boolean
    packages: Array<{
      recipientName: string
      faritra: boolean
      destination: ConfirmedLocation
      contact: string
      amountToCollectAriary: number
      mobileMoneyPaid: boolean
      weightKg?: number
      desiredDeliveryTime?: string
    }>
  },
  accessToken: string,
) {
  return adminRequest<DeliveryRequest>(
    `clients/${encodeURIComponent(clientAccountId)}/delivery-requests`, accessToken, jsonRequest('POST', input),
  )
}

export function quoteAdminDeliveryPrice(
  clientAccountId: string,
  destination: ConfirmedLocation,
  requestKind: DeliveryRequestKind,
  accessToken: string,
  signal?: AbortSignal,
  options: { weightKg?: number; desiredDeliveryTime?: string } = {},
) {
  return adminRequest<{
    pricingZoneId: string
    pricingZoneName: string
    deliveryFeeAriary: number
    distanceFromCenterMeters: number
    pricingZoneMaxDistanceMeters: number
    pricingPolicyVersion: number
    baseDeliveryFeeAriary?: number
    weightSurchargeAriary?: number
    timeSurchargeAriary?: number
    timeSlotLabel?: string | null
  }>(`clients/${encodeURIComponent(clientAccountId)}/delivery-pricing/quote`, accessToken, {
    ...jsonRequest('POST', { destination, requestKind, ...options }),
    signal,
  })
}

export function listExpressDeliveries(accessToken: string, signal?: AbortSignal) {
  return adminRequest<ExpressDelivery[]>('express-deliveries', accessToken, { signal })
}

export function confirmExpressDelivery(id: string, courierIds: string[], version: number, accessToken: string) {
  return adminRequest<ExpressDelivery>(
    `express-deliveries/${encodeURIComponent(id)}/confirm`, accessToken,
    jsonRequest('POST', { courierIds, version }),
  )
}

export function cancelExpressDelivery(id: string, version: number, accessToken: string) {
  return adminRequest<void>(
    `express-deliveries/${encodeURIComponent(id)}/cancel`, accessToken,
    jsonRequest('POST', { version }),
  )
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

export function cancelDispatchPlan(
  id: string,
  expectedVersion: number,
  reason: string,
  accessToken: string,
) {
  return adminRequest<DispatchPlan>(`dispatch-plans/${encodeURIComponent(id)}/cancel`, accessToken,
    jsonRequest('POST', { expectedVersion, reason }))
}

export function regenerateDispatchPlan(
  id: string,
  input: {
    expectedVersion: number
    reason: string
    courierIds: string[]
    hubOverride?: ConfirmedLocation
    includeNewPending: boolean
  },
  idempotencyKey: string,
  accessToken: string,
) {
  return adminRequest<DispatchPlan>(`dispatch-plans/${encodeURIComponent(id)}/regenerate`, accessToken, {
    ...jsonRequest('POST', input),
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
  })
}

export function updateManualDispatchLayout(
  id: string,
  expectedVersion: number,
  reason: string,
  assignments: ManualDispatchAssignmentInput[],
  accessToken: string,
) {
  return adminRequest<DispatchPlan>(`dispatch-plans/${encodeURIComponent(id)}/manual-layout`, accessToken,
    jsonRequest('PUT', { expectedVersion, reason, assignments }))
}

export function updateDeliveryPackageFee(
  packageId: string,
  input: {
    mode: 'CALCULATED' | 'MANUAL'
    manualFeeAriary?: number | null
    reason?: string | null
    version: number
  },
  accessToken: string,
) {
  return adminRequest<DeliveryFeeUpdateResult>(
    `delivery-packages/${encodeURIComponent(packageId)}/delivery-fee`,
    accessToken,
    jsonRequest('PUT', input),
  )
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
    outcome: DeliveryPackageOutcome
    actualCollectedAriary?: number | null
    actualCourierId?: string | null
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

function paymentRequest(input: unknown, proof?: File | null): RequestInit {
  if (!proof) return jsonRequest('POST', input)
  const body = new FormData()
  body.append('payment', new Blob([JSON.stringify(input)], { type: 'application/json' }))
  body.append('proof', proof)
  return { method: 'POST', body }
}

export function getAdminClientFinance(accountId: string, accessToken: string, signal?: AbortSignal) {
  return adminRequest<ClientFinance>(`finance/clients/${encodeURIComponent(accountId)}`, accessToken, { signal })
}

export function createClientPayment(
  settlementId: string,
  input: CreatePaymentInput & { direction: ClientPaymentDirection },
  proof: File | null,
  accessToken: string,
) {
  return adminRequest<ClientPayment>(
    `finance/client-settlements/${encodeURIComponent(settlementId)}/payments`,
    accessToken,
    paymentRequest(input, proof),
  )
}

export function voidClientPayment(paymentId: string, version: number, reason: string, accessToken: string) {
  return adminRequest<ClientPayment>(
    `finance/client-payments/${encodeURIComponent(paymentId)}/void`,
    accessToken,
    jsonRequest('POST', { version, reason }),
  )
}

export function downloadAdminClientPaymentProof(paymentId: string, accessToken: string) {
  return downloadAdminReport(
    `finance/client-payments/${encodeURIComponent(paymentId)}/proof`,
    `justificatif-client-${paymentId}`,
    accessToken,
  )
}

export function getCourierCompensations(serviceDate: string, accessToken: string, signal?: AbortSignal) {
  return adminRequest<CourierCompensation>(
    `finance/delivery-days/${encodeURIComponent(serviceDate)}/courier-compensations`,
    accessToken,
    { signal },
  )
}

export function finalizeCourierCompensations(
  serviceDate: string,
  closureRevision: number,
  overrides: Array<{ packageId: string; finalAmountAriary: number; reason?: string | null }>,
  accessToken: string,
) {
  return adminRequest<CourierCompensation>(
    `finance/delivery-days/${encodeURIComponent(serviceDate)}/courier-compensations/finalize`,
    accessToken,
    jsonRequest('POST', { closureRevision, overrides }),
  )
}

export function getCourierFinance(
  courierId: string,
  accessToken: string,
  filters: { from?: string; to?: string } = {},
  signal?: AbortSignal,
) {
  const query = new URLSearchParams()
  if (filters.from) query.set('from', filters.from)
  if (filters.to) query.set('to', filters.to)
  const suffix = query.size ? `?${query}` : ''
  return adminRequest<CourierFinance>(
    `finance/couriers/${encodeURIComponent(courierId)}${suffix}`,
    accessToken,
    { signal },
  )
}

export function downloadCourierStatement(
  courierId: string,
  format: ReportFormat,
  filters: { from?: string; to?: string },
  accessToken: string,
) {
  const query = new URLSearchParams()
  if (filters.from) query.set('from', filters.from)
  if (filters.to) query.set('to', filters.to)
  const suffix = query.size ? `?${query}` : ''
  const period = [filters.from, filters.to].filter(Boolean).join('-') || 'historique'
  return downloadAdminReport(
    `finance/couriers/${encodeURIComponent(courierId)}/statement.${format}${suffix}`,
    `azy-livraison-livreur-${courierId}-${period}.${format}`,
    accessToken,
  )
}

export function createCourierPayment(
  courierId: string,
  input: CreatePaymentInput,
  proof: File | null,
  accessToken: string,
) {
  return adminRequest<CourierPayment>(
    `finance/couriers/${encodeURIComponent(courierId)}/payments`,
    accessToken,
    paymentRequest(input, proof),
  )
}

export function voidCourierPayment(paymentId: string, version: number, reason: string, accessToken: string) {
  return adminRequest<CourierPayment>(
    `finance/courier-payments/${encodeURIComponent(paymentId)}/void`,
    accessToken,
    jsonRequest('POST', { version, reason }),
  )
}

export function downloadAdminCourierPaymentProof(paymentId: string, accessToken: string) {
  return downloadAdminReport(
    `finance/courier-payments/${encodeURIComponent(paymentId)}/proof`,
    `justificatif-livreur-${paymentId}`,
    accessToken,
  )
}
