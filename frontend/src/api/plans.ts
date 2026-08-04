import type { Courier, DeliveryPackage, Location } from './csvImports'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export type PlanningPattern = 'SINGLE_PICKUP_MULTI_DELIVERY' | 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY'
export type RoutingPreset = 'MAIN_ROADS'

interface BaseDeliveryPlan {
  planId: string
  name: string
  departureAt: string
  timezone: 'Indian/Antananarivo'
  settings: {
    returnToDepot: boolean
    routingPreset: RoutingPreset
  }
  couriers: Courier[]
}

export interface DeliveryPlan extends BaseDeliveryPlan {
  schemaVersion: '1.0'
  pattern: 'SINGLE_PICKUP_MULTI_DELIVERY'
  depot: Location
  packages: DeliveryPackage[]
  clients?: []
}

export interface DeliveryGroup {
  id: string
  name: string
  pickup: Location
  packages: DeliveryPackage[]
}

export interface DeliveryClient {
  id: string
  name: string
  deliveryGroups: DeliveryGroup[]
}

export interface MultiClientDeliveryPlan extends BaseDeliveryPlan {
  schemaVersion: '2.0'
  pattern: 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY'
  clients: DeliveryClient[]
  packages?: []
}

export type AnyDeliveryPlan = DeliveryPlan | MultiClientDeliveryPlan

export type PortableDeliveryPlan = Omit<DeliveryPlan, 'couriers'> & {
  couriers: Array<Omit<Courier, 'maxWeightKg' | 'averageSpeedKmh'> & {
    maxWeightKg?: number
    averageSpeedKmh?: number
  }>
}

export type PortableMultiClientDeliveryPlan = Omit<MultiClientDeliveryPlan, 'couriers'> & {
  couriers: Array<Omit<Courier, 'maxWeightKg' | 'averageSpeedKmh'> & {
    maxWeightKg?: number
    averageSpeedKmh?: number
  }>
}

export type PortableAnyDeliveryPlan = PortableDeliveryPlan | PortableMultiClientDeliveryPlan

export interface PlanningValidationError {
  path: string
  code: string
  message: string
}

export interface PlanValidationResponse {
  valid: boolean
  plan?: AnyDeliveryPlan
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
  plan: AnyDeliveryPlan,
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

function isCourier(value: unknown) {
  return isRecord(value)
    && typeof value.id === 'string'
    && typeof value.name === 'string'
    && (value.vehicleType === 'MOTO' || value.vehicleType === 'BICYCLE')
    && (value.maxWeightKg === undefined || typeof value.maxWeightKg === 'number')
    && (value.averageSpeedKmh === undefined || typeof value.averageSpeedKmh === 'number')
    && isOptionalString(value.departureAt)
    && (value.departureLocation === undefined || value.departureLocation === null || isLocation(value.departureLocation))
}

function isPackage(value: unknown) {
  return isRecord(value)
    && typeof value.id === 'string'
    && typeof value.label === 'string'
    && typeof value.weightKg === 'number'
    && isOptionalString(value.phone)
    && isLocation(value.destination)
    && (value.deliveryWindow === undefined
      || value.deliveryWindow === null
      || (isRecord(value.deliveryWindow)
        && typeof value.deliveryWindow.startAt === 'string'
        && typeof value.deliveryWindow.endAt === 'string'))
}

function hasBasePlanShape(plan: Record<string, unknown>) {
  return typeof plan.planId === 'string'
    && typeof plan.name === 'string'
    && typeof plan.departureAt === 'string'
    && plan.timezone === 'Indian/Antananarivo'
    && isRecord(plan.settings)
    && typeof plan.settings.returnToDepot === 'boolean'
    && plan.settings.routingPreset === 'MAIN_ROADS'
    && Array.isArray(plan.couriers)
    && plan.couriers.every(isCourier)
}

export function isDeliveryPlan(value: unknown): value is PortableAnyDeliveryPlan {
  if (!isRecord(value)) return false
  const plan = value
  if (!hasBasePlanShape(plan)) return false
  if (plan.schemaVersion === '1.0' && plan.pattern === 'SINGLE_PICKUP_MULTI_DELIVERY') {
    return isLocation(plan.depot)
      && Array.isArray(plan.packages)
      && plan.packages.every(isPackage)
  }
  return plan.schemaVersion === '2.0'
    && plan.pattern === 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY'
    && Array.isArray(plan.clients)
    && plan.clients.every((client) => isRecord(client)
      && typeof client.id === 'string'
      && typeof client.name === 'string'
      && Array.isArray(client.deliveryGroups)
      && client.deliveryGroups.every((group) => isRecord(group)
        && typeof group.id === 'string'
        && typeof group.name === 'string'
        && isLocation(group.pickup)
        && Array.isArray(group.packages)
        && group.packages.every(isPackage)))
}

export function materializeVehicleDefaults(plan: PortableDeliveryPlan): DeliveryPlan
export function materializeVehicleDefaults(plan: PortableMultiClientDeliveryPlan): MultiClientDeliveryPlan
export function materializeVehicleDefaults(plan: PortableAnyDeliveryPlan): AnyDeliveryPlan {
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
