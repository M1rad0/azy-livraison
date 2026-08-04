import type { Location } from './csvImports'
import type { DeliveryPlan, RoutingPreset } from './plans'

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
  plan: DeliveryPlan,
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
