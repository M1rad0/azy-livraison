import type { ConfirmedLocation, DispatchPlan, VehicleType } from './admin'
import type { DeliveryTimePreference } from './client'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export interface ManualDraftCourier {
  courierId: string
  name: string
  contact?: string
  vehicleType: VehicleType
  maxWeightKg: number
  averageSpeedKmh: number
  departureAt: string
  departure: ConfirmedLocation
}
export interface ManualPackage {
  packageId: string
  deliveryRequestId: string
  ownerAccountId: string
  serviceClientName: string
  requestedDate: string
  pickup: ConfirmedLocation
  recipientName: string
  faritra: boolean
  destination: ConfirmedLocation
  contact: string
  amountToCollectAriary: number
  mobileMoneyPaid: boolean
  effectiveWeightKg: number
  desiredDeliveryTime?: string
  desiredDeliveryEndTime?: string
  deliveryTimePreference?: DeliveryTimePreference
  pricingSnapshot?: { deliveryFeeAriary?: number }
}
export interface ManualPickup { id: string; location: ConfirmedLocation; packageIds: string[] }
export interface ManualCollection { pickupId: string; courierId: string; order: number }
export interface ManualDelivery { packageId: string; courierId: string; order: number }
export type ManualDispatchStep = 'COLLECTION' | 'DELIVERY' | 'REVIEW'
export interface ManualDispatchDraft {
  id: string
  serviceDate: string
  version: number
  status: 'DRAFT' | 'FINALIZED' | 'CANCELLED'
  step: ManualDispatchStep
  hub: ConfirmedLocation
  couriers: ManualDraftCourier[]
  packages: ManualPackage[]
  pickups: ManualPickup[]
  collections: ManualCollection[]
  deliveries: ManualDelivery[]
  finalPlanId?: string
}

async function request<T>(path: string, token: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE_URL}/admin/manual-dispatch-drafts${path}`, {
    ...init,
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...init.headers },
  })
  if (response.status === 204) return undefined as T
  const payload = await response.json().catch(() => undefined)
  if (!response.ok) throw new Error(payload?.message ?? 'Le dispatch n’a pas pu être enregistré.')
  return payload as T
}

export function getActiveManualDraft(date: string, token: string, signal?: AbortSignal) {
  return request<ManualDispatchDraft | undefined>(`/active?date=${encodeURIComponent(date)}`, token, { signal })
}
export function getManualDraftForPlan(planId: string, token: string, signal?: AbortSignal) {
  return request<ManualDispatchDraft | undefined>(`/for-plan/${encodeURIComponent(planId)}`, token, { signal })
}
export function createManualDraft(input: { serviceDate: string; courierIds: string[]; hubOverride?: ConfirmedLocation }, key: string, token: string) {
  return request<ManualDispatchDraft>('', token, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(input) })
}
export function saveManualDraft(id: string, expectedVersion: number, layout: Pick<ManualDispatchDraft, 'step' | 'collections' | 'deliveries' | 'couriers'>, token: string) {
  return request<ManualDispatchDraft>(`/${encodeURIComponent(id)}`, token, { method: 'PUT', body: JSON.stringify({ expectedVersion, ...layout }) })
}
export function finalizeManualDraft(id: string, expectedVersion: number, token: string) {
  return request<DispatchPlan>(`/${encodeURIComponent(id)}/finalize`, token, { method: 'POST', body: JSON.stringify({ expectedVersion }) })
}
export function cancelManualDraft(id: string, expectedVersion: number, token: string) {
  return request<void>(`/${encodeURIComponent(id)}/cancel`, token, { method: 'POST', body: JSON.stringify({ expectedVersion }) })
}
export function editManualPlan(planId: string, expectedVersion: number, token: string) {
  return request<ManualDispatchDraft>(`/from-plan/${encodeURIComponent(planId)}`, token, { method: 'POST', body: JSON.stringify({ expectedVersion }) })
}
