import { AdminApiError, type DownloadedReport } from './admin'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'
export type CashAccountType = 'AZY' | 'COURIER' | 'SELLER'
export interface CashAccount {
  key: string
  type: CashAccountType
  entityId: string | null
  name: string
  balanceAriary: number
  reservedAriary: number
  availableAriary: number
  payoutNumber?: string | null
}
export interface CashMovement {
  id: string
  kind: string
  fromKey?: string | null
  toKey?: string | null
  amountAriary: number
  deltaAriary?: number | null
  paymentMethod?: string | null
  reference?: string | null
  note?: string | null
  status: 'CONFIRMED' | 'VOIDED'
  createdAt: string
  actorAccountId?: string | null
  proofAvailable: boolean
  reversalOf?: string | null
  voidReason?: string | null
  voidedAt?: string | null
}
export interface CashObligation {
  id: string
  reportId: string
  sellerAccountId: string
  serviceDate: string
  amountDueAriary: number
  paidAriary: number
  remainingAriary: number
  active?: boolean
}
export interface CashLedger {
  activatedAt: string
  revision: number
  accounts: CashAccount[]
  movements: CashMovement[]
  obligations: CashObligation[]
}
export interface CashTransferInput {
  idempotencyKey: string
  fromKey: string
  toKey: string
  amountAriary: number
  paymentMethod: string
  reference?: string
  note?: string
  expectedRevision?: number
}
export interface CashAdjustmentInput {
  idempotencyKey: string
  accountKey: string
  deltaAriary: number
  reason: string
  expectedRevision?: number
}

async function checkedResponse(path: string, token: string, init: RequestInit = {}) {
  const response = await fetch(`${API_BASE_URL}/admin/cash-ledger${path}`, {
    ...init,
    headers: { Accept: 'application/json', Authorization: `Bearer ${token}`, ...init.headers },
  })
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { message?: string; code?: string }
    throw new AdminApiError(error.message ?? `Opération impossible (${response.status}).`, response.status, error.code)
  }
  return response
}

function post(body: unknown): RequestInit {
  return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
}

export async function getCashLedger(token: string, signal?: AbortSignal): Promise<CashLedger> {
  return (await checkedResponse('', token, { signal })).json() as Promise<CashLedger>
}

export async function createCashTransfer(input: CashTransferInput, proof: File | null, token: string) {
  let request = post(input)
  if (proof) {
    const body = new FormData()
    body.append('transfer', new Blob([JSON.stringify(input)], { type: 'application/json' }))
    body.append('proof', proof)
    request = { method: 'POST', body }
  }
  await checkedResponse('/transfers', token, request)
}

export async function adjustCashAccount(input: CashAdjustmentInput, token: string) {
  await checkedResponse('/adjustments', token, post(input))
}

export async function voidCashMovement(id: string, reason: string, expectedRevision: number, token: string) {
  await checkedResponse(`/movements/${encodeURIComponent(id)}/void`, token, post({ reason, expectedRevision }))
}

export async function downloadCashMovementProof(id: string, token: string): Promise<DownloadedReport> {
  const response = await checkedResponse(`/movements/${encodeURIComponent(id)}/proof`, token)
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1]
  const plain = /filename="?([^";]+)"?/i.exec(disposition)?.[1]
  let filename = plain ?? `justificatif-caisse-${id}`
  if (encoded) {
    try { filename = decodeURIComponent(encoded) } catch { /* use the plain filename */ }
  }
  return { blob: await response.blob(), filename }
}
