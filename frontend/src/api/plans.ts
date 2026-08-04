import type { Courier, DeliveryPackage, Location } from './csvImports'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export type PlanningPattern = 'SINGLE_PICKUP_MULTI_DELIVERY'
export type RoutingPreset = 'MAIN_ROADS'

export interface DeliveryPlan {
  schemaVersion: '1.0'
  pattern: PlanningPattern
  planId: string
  name: string
  departureAt: string
  timezone: 'Indian/Antananarivo'
  settings: {
    returnToDepot: boolean
    routingPreset: RoutingPreset
  }
  depot: Location
  couriers: Courier[]
  packages: DeliveryPackage[]
}

export interface PlanningValidationError {
  path: string
  code: string
  message: string
}

export interface PlanValidationResponse {
  valid: boolean
  plan?: DeliveryPlan
  errors: PlanningValidationError[]
}

export class PlanValidationFailure extends Error {
  constructor(
    message: string,
    public readonly errors: PlanningValidationError[],
    public readonly status: number,
  ) {
    super(message)
  }
}

export async function validatePlan(
  plan: DeliveryPlan,
  accessToken: string,
  signal?: AbortSignal,
): Promise<PlanValidationResponse> {
  const response = await fetch(`${API_BASE_URL}/plans/validate`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(plan),
    signal,
  })
  const payload = (await response.json().catch(() => undefined)) as PlanValidationResponse | undefined

  if (!response.ok || !payload?.valid || !payload.plan) {
    const errors = payload?.errors ?? []
    throw new PlanValidationFailure(
      errors[0]?.message ?? `La validation a échoué avec le statut ${response.status}.`,
      errors,
      response.status,
    )
  }

  return payload
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function isOptionalString(value: unknown) {
  return value === undefined || value === null || typeof value === 'string'
}

function isOptionalNumber(value: unknown) {
  return value === undefined || value === null || typeof value === 'number'
}

function isLocation(value: unknown) {
  return isRecord(value)
    && isOptionalString(value.label)
    && isOptionalString(value.destinationQuery)
    && isOptionalNumber(value.latitude)
    && isOptionalNumber(value.longitude)
}

export function isDeliveryPlan(value: unknown): value is DeliveryPlan {
  if (!isRecord(value)) return false
  const plan = value
  return plan.schemaVersion === '1.0'
    && plan.pattern === 'SINGLE_PICKUP_MULTI_DELIVERY'
    && typeof plan.planId === 'string'
    && typeof plan.name === 'string'
    && typeof plan.departureAt === 'string'
    && plan.timezone === 'Indian/Antananarivo'
    && isRecord(plan.settings)
    && typeof plan.settings.returnToDepot === 'boolean'
    && plan.settings.routingPreset === 'MAIN_ROADS'
    && isLocation(plan.depot)
    && Array.isArray(plan.couriers)
    && plan.couriers.every((courier) => isRecord(courier)
      && typeof courier.id === 'string'
      && typeof courier.name === 'string'
      && (courier.vehicleType === 'MOTO' || courier.vehicleType === 'BICYCLE')
      && typeof courier.maxWeightKg === 'number'
      && typeof courier.averageSpeedKmh === 'number')
    && Array.isArray(plan.packages)
    && plan.packages.every((deliveryPackage) => isRecord(deliveryPackage)
      && typeof deliveryPackage.id === 'string'
      && typeof deliveryPackage.label === 'string'
      && typeof deliveryPackage.weightKg === 'number'
      && isOptionalString(deliveryPackage.phone)
      && isLocation(deliveryPackage.destination))
}
