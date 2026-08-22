import type { Location } from './csvImports'
import type { AnyDeliveryPlan, RoutingPreset } from './plans'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export type OptimizationStatus = 'SUCCESS' | 'SUCCESS_WITH_WARNINGS' | 'NO_SOLUTION'
export type UnassignedReason =
  | 'DESTINATION_UNRESOLVED'
  | 'NO_ROAD_ROUTE'
  | 'OVERWEIGHT_FOR_ALL_COURIERS'
  | 'INSUFFICIENT_TOTAL_CAPACITY'
  | 'TIME_WINDOW_UNSATISFIABLE'
  | 'SOLVER_TIMEOUT'
  | 'NO_FEASIBLE_ASSIGNMENT'

export interface OptimizationResult {
  schemaVersion: string
  sourcePlanId: string
  generatedAt: string
  status: OptimizationStatus
  solverStatus: string
  computationTimeMillis: number
  dataContext: {
    routingProvider: string
    routingPreset: RoutingPreset
    optimizer: string
    optimizerVersion: string
  }
  summary: {
    packageCount: number
    assignedPackageCount: number
    unassignedPackageCount: number
    courierCount: number
    usedCourierCount: number
    totalAssignedWeightKg: number
    totalDistanceMeters: number
    totalDurationSeconds: number
  }
  routes: CourierRoute[]
  unassignedPackages: UnassignedPackage[]
  warnings: string[]
}

export interface CourierRoute {
  courierId: string
  courierName: string
  vehicleType: 'MOTO' | 'BICYCLE'
  loadedWeightKg: number
  maxWeightKg: number
  remainingCapacityKg: number
  capacityUtilizationPercent: number
  departureLocation: Location
  departureAt: string
  completedAt: string
  distanceMeters: number
  durationSeconds: number
  stops: RouteStop[]
}

export interface RouteStop {
  order: number
  type: 'PICKUP' | 'DELIVERY'
  clientId?: string
  clientName?: string
  deliveryGroupId?: string
  deliveryGroupName?: string
  packageId: string
  packageLabel: string
  phone?: string
  weightKg: number
  destination: Location
  estimatedArrivalAt: string
  distanceFromPreviousMeters: number
  durationFromPreviousSeconds: number
  cumulativeDistanceMeters: number
  cumulativeDurationSeconds: number
}

export interface UnassignedPackage {
  packageId: string
  packageLabel: string
  weightKg: number
  reason: UnassignedReason
  message: string
  minimumRequiredCapacityKg?: number
  clientId?: string
  clientName?: string
  deliveryGroupId?: string
  deliveryGroupName?: string
}

export type OptimizationStrategy = 'DIRECT' | 'VIA_HUB'
export type RecommendationCriterion =
  | 'MORE_DELIVERED_PACKAGES'
  | 'MORE_ON_TIME_PACKAGES'
  | 'LOWER_TOTAL_LATENESS'
  | 'LOWER_DISTANCE'
  | 'EARLIER_COMPLETION'
  | 'FEWER_COURIERS'
  | 'EXACT_TIE_DIRECT'

export interface OperationalPolicySnapshot {
  pickupServiceSeconds: number
  hubTransferSeconds: number
  deliveryServiceSeconds: number
  deliveryWindowEarlyToleranceSeconds: number
  deliveryWindowLateToleranceSeconds: number
  windowAppliesTo: string
}

export interface StrategyMetrics {
  packageCount: number
  assignedPackageCount: number
  onTimePackageCount: number
  latePackageCount: number
  totalLatenessSeconds: number
  maxLatenessSeconds: number
  totalDistanceMeters: number
  completedAt?: string
  usedCourierCount: number
  drivingSeconds: number
  waitingSeconds: number
  serviceSeconds: number
}

export interface OptimizationScenario {
  strategy: OptimizationStrategy
  result: OptimizationResult
  metrics: StrategyMetrics
}

export interface ComparisonDelta {
  assignedPackageCount: number
  onTimePackageCount: number
  totalLatenessSeconds: number
  totalDistanceMeters: number
  completionSeconds?: number
  usedCourierCount: number
}

export interface PackageReference {
  packageId: string
  packageLabel: string
  weightKg: number
  clientId?: string
  clientName?: string
  deliveryGroupId?: string
  deliveryGroupName?: string
}

export interface CourierHubManifest {
  courierId: string
  courierName: string
  roles: Array<'COLLECTION' | 'DISTRIBUTION'>
  arrivedAt?: string
  departedAt?: string
  waitingSeconds: number
  loadAtArrivalKg: number
  loadAtDepartureKg: number
  collectedPackages: PackageReference[]
  keptPackages: PackageReference[]
  transferredOutPackages: PackageReference[]
  receivedPackages: PackageReference[]
  deliveredPackages: PackageReference[]
}

export interface PackageTransfer {
  deliveryPackage: PackageReference
  collectorCourierId: string
  collectorCourierName: string
  distributorCourierId?: string
  distributorCourierName?: string
  transferred: boolean
  arrivedAtHub?: string
  departedHub?: string
}

export interface HubExecution {
  hub: Location
  meetingAt: string
  redistributionCompletedAt: string
  collectionDistanceMeters: number
  distributorApproachDistanceMeters: number
  distributionDistanceMeters: number
  hubWaitingSeconds: number
  manifests: CourierHubManifest[]
  packageTransfers: PackageTransfer[]
}

export interface OptimizationComparisonResult {
  schemaVersion: string
  sourcePlanId: string
  generatedAt: string
  recommendedStrategy: OptimizationStrategy
  recommendationCriterion: RecommendationCriterion
  recommendationReason: string
  operationalPolicy: OperationalPolicySnapshot
  direct: OptimizationScenario
  viaHub: OptimizationScenario
  delta: ComparisonDelta
  hubExecution: HubExecution
}

interface OptimizationErrorResponse {
  message?: string
  errors?: { message: string }[]
}

export class OptimizationFailure extends Error {
  constructor(message: string, public readonly status: number) {
    super(message)
  }
}

export async function optimizePlan(
  plan: AnyDeliveryPlan,
  accessToken: string,
  signal?: AbortSignal,
): Promise<OptimizationResult> {
  const response = await fetch(`${API_BASE_URL}/plans/optimize`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(plan),
    signal,
  })
  const payload = (await response.json().catch(() => undefined)) as OptimizationResult | OptimizationErrorResponse | undefined

  if (!response.ok || !payload || !('routes' in payload)) {
    const message = payload && 'message' in payload
      ? payload.message
      : payload && 'errors' in payload
        ? payload.errors?.[0]?.message
        : undefined
    throw new OptimizationFailure(
      message ?? `La génération des tournées a échoué avec le statut ${response.status}.`,
      response.status,
    )
  }

  return payload
}

export async function compareOptimizations(
  plan: AnyDeliveryPlan,
  accessToken: string,
  signal?: AbortSignal,
): Promise<OptimizationComparisonResult> {
  const response = await fetch(`${API_BASE_URL}/plans/compare`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(plan),
    signal,
  })
  const payload = (await response.json().catch(() => undefined)) as OptimizationComparisonResult | OptimizationErrorResponse | undefined

  if (!response.ok || !payload || !('direct' in payload) || !('viaHub' in payload)) {
    const message = payload && 'message' in payload
      ? payload.message
      : payload && 'errors' in payload
        ? payload.errors?.[0]?.message
        : undefined
    throw new OptimizationFailure(
      message ?? `La comparaison des stratégies a échoué avec le statut ${response.status}.`,
      response.status,
    )
  }

  return payload
}
