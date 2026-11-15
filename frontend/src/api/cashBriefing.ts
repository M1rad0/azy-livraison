import { AdminApiError } from './admin'

export interface CashBriefingTransfer {
  id: string; sellerAccountId: string; sellerName: string; sellerPayoutNumber?: string | null
  courierAccountId: string; courierName: string; amountAriary: number
  status: 'PENDING' | 'CONFIRMED' | 'REPLACED'; movementId?: string | null
}
export interface CashBriefingBatch {
  id: string; planId: string; serviceDate: string
  status: 'ACTIVE' | 'STALE' | 'RELEASED' | 'EXPIRED' | 'COMPLETED' | 'PLAN_INVALID'
  ledgerRevision: number; expiresAt: string; algorithm: string; message: string; mode?: 'AUTO' | 'MANUAL'
  transfers: CashBriefingTransfer[]
  balances: { courierAccountId: string; courierName: string; balanceAriary: number; availableAriary: number; reservedAriary: number }[]
}
export interface CashBriefingPlanning {
  planId: string; serviceDate: string; ledgerRevision: number; batch?: CashBriefingBatch | null
  sellers: { sellerAccountId: string; sellerName: string; sellerPayoutNumber?: string | null; balanceAriary: number; plannedAriary: number; availableAriary: number }[]
  couriers: { courierAccountId: string; courierName: string; balanceAriary: number; reservedElsewhereAriary: number; capacityAriary: number; plannedAriary: number; remainingAriary: number }[]
}
export interface ManualCashBriefingInput {
  planId: string; idempotencyKey: string; expectedLedgerRevision: number; expectedBatchId?: string | null
  transfers: { sellerAccountId: string; courierAccountId: string; amountAriary: number }[]
}
const BASE = `${import.meta.env.VITE_API_BASE_URL ?? '/api'}/admin/cash-briefings`
async function request<T>(path: string, token: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${BASE}${path}`, { ...init, headers: { Accept: 'application/json', Authorization: `Bearer ${token}`, ...init.headers } })
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { message?: string; code?: string }
    throw new AdminApiError(error.message ?? 'Les propositions de versement sont indisponibles.', response.status, error.code)
  }
  return response.json() as Promise<T>
}
const post = (data?: unknown): RequestInit => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data ?? {}) })
export const listCashBriefings = (token: string, planId?: string, signal?: AbortSignal) => request<CashBriefingBatch[]>(planId ? `?planId=${encodeURIComponent(planId)}` : '', token, { signal })
export const getCashBriefingPlanning = (planId: string, token: string, signal?: AbortSignal) => request<CashBriefingPlanning>(`/planning?planId=${encodeURIComponent(planId)}`, token, { signal })
export const saveManualCashBriefing = (input: ManualCashBriefingInput, token: string) => request<CashBriefingBatch>('/manual', token, post(input))
export const prepareCashBriefing = (planId: string, token: string, idempotencyKey: string = crypto.randomUUID(), replacement?: { replaceExisting: true; expectedLedgerRevision: number; expectedBatchId: string | null }) => request<CashBriefingBatch>('/prepare', token, post({ planId, idempotencyKey, ...replacement }))
export const releaseCashBriefing = (id: string, token: string) => request<CashBriefingBatch>(`/${encodeURIComponent(id)}/release`, token, post())
export const revalidateCashBriefing = (id: string, token: string) => request<CashBriefingBatch>(`/${encodeURIComponent(id)}/revalidate`, token, post())
export function confirmCashBriefingTransfer(id: string, input: { paymentMethod: string; reference?: string; note?: string; expectedLedgerRevision: number }, proof: File | null, token: string) {
  let init = post(input)
  if (proof) {
    const body = new FormData()
    body.append('transfer', new Blob([JSON.stringify(input)], { type: 'application/json' }))
    body.append('proof', proof)
    init = { method: 'POST', body }
  }
  return request<CashBriefingBatch>(`/transfers/${encodeURIComponent(id)}/confirm`, token, init)
}
