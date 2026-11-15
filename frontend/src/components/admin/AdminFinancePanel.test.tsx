import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ClientClosureOverview, ClientFinance, DailyOperation } from '../../api/admin'
import { AdminFinancePanel } from './AdminFinancePanel'
import { LegacyFinancePanel } from './LegacyFinancePanel'

function jsonResponse(payload: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => payload }
}

const day: DailyOperation = {
  serviceDate: '2026-09-16',
  status: 'DISPATCHED',
  admissionsClosed: true,
  dispatchPlanId: 'plan-1',
  closureRevision: 0,
  version: 8,
  counts: { total: 2, finalized: 2, awaitingOutcome: 0, delivered: 2, cancelled: 0, postponed: 0, returnToSender: 0, returnsCompleted: 0 },
  totals: { recoveredAriary: 50_000, deliveryFeesAriary: 8_000, amountToRemitAriary: 42_000 },
  clients: [
    { accountId: 'client-closed', displayName: 'Boutique Soa', phone: '034 00 000 01', packageCount: 1, finalizedCount: 1, totals: { recoveredAriary: 30_000, deliveryFeesAriary: 4_000, amountToRemitAriary: 26_000 } },
    { accountId: 'client-open', displayName: 'Boutique Kanto', phone: '034 00 000 02', packageCount: 1, finalizedCount: 1, totals: { recoveredAriary: 20_000, deliveryFeesAriary: 4_000, amountToRemitAriary: 16_000 } },
  ],
  packages: [],
}

function closures(closed: boolean): ClientClosureOverview {
  return {
    serviceDate: day.serviceDate,
    dayStatus: day.status,
    dayVersion: day.version,
    currentClosureRevision: day.closureRevision,
    clientCount: 2,
    closedClientCount: closed ? 1 : 0,
    allClientsClosed: false,
    clients: day.clients.map((client) => ({
      clientAccountId: client.accountId,
      clientDisplayName: client.displayName,
      clientPhone: client.phone,
      readyToClose: true,
      blockers: [],
      packageCount: client.packageCount,
      recoveredAriary: client.totals.recoveredAriary,
      deliveryFeesAriary: client.totals.deliveryFeesAriary,
      amountToRemitAriary: client.totals.amountToRemitAriary,
      closed: closed && client.accountId === 'client-closed',
      closureRevision: closed && client.accountId === 'client-closed' ? 3 : null,
      reportId: closed && client.accountId === 'client-closed' ? 'report-closed' : null,
      closedAt: closed && client.accountId === 'client-closed' ? '2026-09-16T18:00:00Z' : null,
      courierCompensationFinalized: false,
      version: closed && client.accountId === 'client-closed' ? 0 : null,
    })),
  }
}

const finance: ClientFinance = {
  clientAccountId: 'client-closed',
  totalDueAriary: 26_000,
  totalConfirmedSignedPaymentsAriary: 0,
  netBalanceAriary: 26_000,
  amountOwedToClientAriary: 26_000,
  amountOwedByClientAriary: 0,
  settlements: [{
    id: 'settlement-1', reportId: 'report-closed', serviceDate: day.serviceDate,
    closureRevision: 3, clientDisplayName: 'Boutique Soa', amountDueAriary: 26_000,
    confirmedSignedPaymentsAriary: 0, remainingAriary: 26_000, status: 'UNPAID',
    createdAt: '2026-09-16T18:00:00Z',
  }],
  payments: [],
}

describe('AdminFinancePanel', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('ouvre les nouvelles caisses par défaut sans charger les anciens règlements', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string | URL | Request) => Promise.resolve(jsonResponse(
      String(input).includes('/cash-briefings') ? [] : { activatedAt: '2026-11-15T04:00:00Z', revision: 0, accounts: [], movements: [], obligations: [] },
    )))
    vi.stubGlobal('fetch', fetchMock)
    render(<AdminFinancePanel accessToken="admin-token" couriers={[]} initialDate={day.serviceDate} />)
    expect(screen.getByRole('tab', { name: /Caisses/ })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('heading', { name: /Argent détenu/ })).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/finance/clients/'))).toBe(false)
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/client-closures'))).toBe(false)
  })

  it('affiche immédiatement le règlement du client clôturé sans attendre la clôture générale', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string | URL | Request) => {
      const url = String(input)
      if (url.endsWith('/client-closures')) return Promise.resolve(jsonResponse(closures(true)))
      if (url.endsWith('/finance/clients/client-closed')) return Promise.resolve(jsonResponse(finance))
      return Promise.resolve(jsonResponse(day))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<LegacyFinancePanel accessToken="admin-token" couriers={[]} initialDate={day.serviceDate} />)

    expect(await screen.findByText('Boutique Soa')).toBeInTheDocument()
    expect(screen.getByText('Non versé')).toBeInTheDocument()
    expect(screen.queryByText('Boutique Kanto')).not.toBeInTheDocument()
    expect(screen.queryByText(/journée .* n’est pas clôturée/i)).not.toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/finance/clients/client-closed',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer admin-token' }) }),
    ))
  })

  it('explique que les rapports apparaissent client par client', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string | URL | Request) => {
      const url = String(input)
      return Promise.resolve(jsonResponse(url.endsWith('/client-closures') ? closures(false) : day))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<LegacyFinancePanel accessToken="admin-token" couriers={[]} initialDate={day.serviceDate} />)

    expect(await screen.findByText(/Aucun compte client clôturé/)).toBeInTheDocument()
    expect(screen.getByText(/sans attendre la fin de la journée/)).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/finance/clients/'))).toBe(false)
  })

  it('redirige les rapports du nouveau registre vers Caisses sans action de paiement historique', async () => {
    const managedFinance = { ...finance, settlements: finance.settlements.map((settlement) => ({ ...settlement, cashLedgerManaged: true })) }
    const fetchMock = vi.fn().mockImplementation((input: string | URL | Request) => {
      const url = String(input)
      if (url.endsWith('/client-closures')) return Promise.resolve(jsonResponse(closures(true)))
      if (url.endsWith('/finance/clients/client-closed')) return Promise.resolve(jsonResponse(managedFinance))
      if (url.includes('/cash-briefings')) return Promise.resolve(jsonResponse([]))
      if (url.endsWith('/cash-ledger')) return Promise.resolve(jsonResponse({ activatedAt: '2026-11-15T04:00:00Z', revision: 0, accounts: [], movements: [], obligations: [] }))
      return Promise.resolve(jsonResponse(day))
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<LegacyFinancePanel accessToken="admin-token" couriers={[]} initialDate={day.serviceDate} />)
    expect(await screen.findByText('Ce rapport est suivi dans Caisses')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Confirmer un versement|Ajouter un versement/ })).not.toBeInTheDocument()
    expect(screen.queryByText('Non versé')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Ouvrir les caisses' }))
    expect(await screen.findByRole('heading', { name: /Argent détenu/ })).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input, options]) => String(input).includes('/payments') && (options as RequestInit)?.method === 'POST')).toBe(false)
  })
})
