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

export type PortableDeliveryPlan = Omit<DeliveryPlan, 'couriers'> & {
  couriers: Array<Omit<Courier, 'maxWeightKg' | 'averageSpeedKmh'> & {
    maxWeightKg?: number
    averageSpeedKmh?: number
  }>
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

export function isDeliveryPlan(value: unknown): value is PortableDeliveryPlan {
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
      && (courier.maxWeightKg === undefined || typeof courier.maxWeightKg === 'number')
      && (courier.averageSpeedKmh === undefined || typeof courier.averageSpeedKmh === 'number')
      && isOptionalString(courier.departureAt)
      && (courier.departureLocation === undefined || courier.departureLocation === null || isLocation(courier.departureLocation)))
    && Array.isArray(plan.packages)
    && plan.packages.every((deliveryPackage) => isRecord(deliveryPackage)
      && typeof deliveryPackage.id === 'string'
      && typeof deliveryPackage.label === 'string'
      && typeof deliveryPackage.weightKg === 'number'
      && isOptionalString(deliveryPackage.phone)
      && isLocation(deliveryPackage.destination)
      && (deliveryPackage.deliveryWindow === undefined
        || deliveryPackage.deliveryWindow === null
        || (isRecord(deliveryPackage.deliveryWindow)
          && typeof deliveryPackage.deliveryWindow.startAt === 'string'
          && typeof deliveryPackage.deliveryWindow.endAt === 'string')))
}

export function materializeVehicleDefaults(plan: PortableDeliveryPlan): DeliveryPlan {
  return {
    ...plan,
    couriers: plan.couriers.map((courier) => {
      const defaults = courier.vehicleType === 'MOTO'
        ? { maxWeightKg: 30, averageSpeedKmh: 25 }
        : { maxWeightKg: 10, averageSpeedKmh: 12 }
      return {
        ...courier,
        maxWeightKg: courier.maxWeightKg ?? defaults.maxWeightKg,
        averageSpeedKmh: courier.averageSpeedKmh ?? defaults.averageSpeedKmh,
      }
    }),
  }
}
