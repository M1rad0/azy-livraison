import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ClientClosureOverview, Courier, DailyOperation, DailyOperationPackage } from '../../api/admin'
import { AdminDailyOperationsPanel } from './AdminDailyOperationsPanel'

function jsonResponse(payload: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => payload }
}

const packageFixture: DailyOperationPackage = {
  packageId: 'package-1',
  deliveryRequestId: 'request-1',
  ownerAccountId: 'account-1',
  ownerDisplayName: 'Boutique Soa',
  ownerPhone: '034 00 000 00',
  requestedDate: '2026-08-24',
  pickup: { label: 'Boutique', query: 'Analakely', latitude: -18.91, longitude: 47.52 },
  sequenceNumber: 1,
  recipientName: 'Rakoto',
  faritra: false,
  destination: { label: 'Ampasampito', latitude: -18.89, longitude: 47.54 },
  contact: '032 00 000 00',
  amountToCollectAriary: 25_000,
  mobileMoneyPaid: true,
  pricingZoneName: 'Centre-ville',
  deliveryFeeAriary: 3_000,
  technicalStatus: 'DISPATCHED',
  distributorCourierId: 'courier-1',
  distributorCourierName: 'Hery',
  finalOutcome: null,
  actualCollectedAriary: null,
  finalState: false,
  recoveredAriary: 0,
  chargedDeliveryFeeAriary: 0,
  amountToRemitAriary: 0,
  version: 2,
}

const couriers: Courier[] = [
  {
    id: 'courier-1', name: 'Hery', contact: '034 00 000 01', active: true,
    vehicleType: 'MOTO', effectiveMaxWeightKg: 30, effectiveAverageSpeedKmh: 25,
    effectiveCommissionNumerator: 1, effectiveCommissionDenominator: 3,
    effectiveFuelReimbursementEnabled: true, effectiveFuelEfficiencyKmPerLiter: 40,
    effectiveFuelPricePerLiterAriary: 5_000,
    defaultDepartureTime: '06:00:00', departureLocation: { label: 'Analakely', latitude: -18.91, longitude: 47.52 },
    version: 1, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
  },
  {
    id: 'courier-2', name: 'Tiana', contact: '034 00 000 02', active: false,
    vehicleType: 'BICYCLE', effectiveMaxWeightKg: 10, effectiveAverageSpeedKmh: 12,
    effectiveCommissionNumerator: 1, effectiveCommissionDenominator: 3,
    effectiveFuelReimbursementEnabled: false, effectiveFuelEfficiencyKmPerLiter: null,
    effectiveFuelPricePerLiterAriary: 5_000,
    defaultDepartureTime: '06:30:00', departureLocation: { label: 'Alasora', latitude: -18.96, longitude: 47.57 },
    version: 2, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-20T00:00:00Z',
  },
]

function dayFixture(deliveryPackage: DailyOperationPackage = packageFixture): DailyOperation {
  const finalized = deliveryPackage.finalState ? 1 : 0
  return {
    serviceDate: '2026-08-24',
    status: 'DISPATCHED',
    admissionsClosed: true,
    dispatchPlanId: 'plan-1',
    closureRevision: 0,
    version: 3,
    counts: {
      total: 1,
      finalized,
      awaitingOutcome: 1 - finalized,
      delivered: deliveryPackage.finalOutcome === 'DELIVERED' ? 1 : 0,
      cancelled: deliveryPackage.finalOutcome === 'CANCELLED' ? 1 : 0,
      postponed: deliveryPackage.finalOutcome === 'POSTPONED' ? 1 : 0,
      returnToSender: deliveryPackage.finalOutcome === 'RETURN_TO_SENDER' ? 1 : 0,
      returnsCompleted: deliveryPackage.returnCompletedAt ? 1 : 0,
    },
    totals: {
      recoveredAriary: deliveryPackage.recoveredAriary,
      deliveryFeesAriary: deliveryPackage.chargedDeliveryFeeAriary,
      amountToRemitAriary: deliveryPackage.amountToRemitAriary,
    },
    clients: [{
      accountId: 'account-1', displayName: 'Boutique Soa', phone: '034 00 000 00',
      packageCount: 1, finalizedCount: finalized,
      totals: { recoveredAriary: 0, deliveryFeesAriary: 0, amountToRemitAriary: 0 },
    }],
    packages: [deliveryPackage],
  }
}

function expressOnlyDayFixture(deliveryPackage: DailyOperationPackage): DailyOperation {
  return {
    ...dayFixture(deliveryPackage),
    status: 'OPEN',
    admissionsClosed: false,
    dispatchPlanId: null,
  }
}

function closureFixture(day: DailyOperation, closed = day.status === 'CLOSED'): ClientClosureOverview {
  const ready = day.counts.awaitingOutcome === 0
  return {
    serviceDate: day.serviceDate,
    dayStatus: day.status,
    dayVersion: day.version,
    currentClosureRevision: day.closureRevision,
    clientCount: day.clients.length,
    closedClientCount: closed ? day.clients.length : 0,
    allClientsClosed: closed && day.clients.length > 0,
    clients: day.clients.map((client) => ({
      clientAccountId: client.accountId,
      clientDisplayName: client.displayName,
      clientPhone: client.phone,
      readyToClose: !closed && ready,
      blockers: !closed && !ready ? ['Un colis reste à finaliser.'] : [],
      packageCount: client.packageCount,
      recoveredAriary: client.totals.recoveredAriary,
      deliveryFeesAriary: client.totals.deliveryFeesAriary,
      amountToRemitAriary: client.totals.amountToRemitAriary,
      closed,
      closureRevision: closed ? Math.max(day.closureRevision, 1) : null,
      reportId: closed ? 'report-1' : null,
      closedAt: closed ? '2026-08-24T18:00:00Z' : null,
      closedByAccountId: closed ? 'admin-1' : null,
      courierCompensationFinalized: false,
      version: closed ? 0 : null,
    })),
  }
}

function dayFetch(day: DailyOperation) {
  return vi.fn().mockImplementation((input: string | URL | Request) => {
    const url = String(input)
    return Promise.resolve(jsonResponse(url.endsWith('/client-closures') ? closureFixture(day) : day))
  })
}

describe('AdminDailyOperationsPanel', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('enregistre un état final et conserve Mobile Money comme information', async () => {
    const initial = dayFixture()
    const updatedPackage = {
      ...packageFixture,
      finalOutcome: 'DELIVERED' as const,
      actualCollectedAriary: 25_000,
      actualCourierId: 'courier-2',
      actualCourierName: 'Tiana',
      finalState: true,
      recoveredAriary: 25_000,
      chargedDeliveryFeeAriary: 3_000,
      amountToRemitAriary: 22_000,
      version: 3,
    }
    const updatedDay = dayFixture(updatedPackage)
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(initial))
      .mockResolvedValueOnce(jsonResponse(closureFixture(initial)))
      .mockResolvedValueOnce(jsonResponse(updatedDay))
      .mockResolvedValueOnce(jsonResponse(closureFixture(updatedDay)))
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" couriers={couriers} onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Renseigner' }))
    expect(screen.getByText(/Mobile Money reste informatif/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Montant réellement récupéré/)).toHaveValue(25_000)
    expect(screen.getByLabelText('Livreur réellement intervenu')).toHaveValue('Hery')
    fireEvent.change(screen.getByLabelText('Livreur réellement intervenu'), { target: { value: 'Tiana' } })
    fireEvent.mouseDown(screen.getByRole('option', { name: /Tiana/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cet état' }))

    await waitFor(() => expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/admin/delivery-days/2026-08-24/packages/package-1/outcome',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ outcome: 'DELIVERED', actualCollectedAriary: 25_000, actualCourierId: 'courier-2', note: null, version: 2 }),
      }),
    ))
    expect(await screen.findByText('Réalisé par : Tiana')).toBeInTheDocument()
    expect(screen.getByText('Prévu : Hery')).toBeInTheDocument()
  })

  it('préremplit une correction avec le livreur réel plutôt que le livreur planifié', async () => {
    const delivered = {
      ...packageFixture,
      finalOutcome: 'DELIVERED' as const,
      actualCollectedAriary: 25_000,
      actualCourierId: 'courier-2',
      actualCourierName: 'Tiana',
      finalState: true,
      recoveredAriary: 25_000,
      chargedDeliveryFeeAriary: 3_000,
      amountToRemitAriary: 22_000,
    }
    vi.stubGlobal('fetch', dayFetch(dayFixture(delivered)))

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" couriers={couriers} onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Modifier' }))

    expect(screen.getByLabelText('Livreur réellement intervenu')).toHaveValue('Tiana (inactif)')
    expect(screen.getByText(/Prévu lors du dispatch : Hery/)).toBeInTheDocument()
  })

  it('efface le livreur réel quand un colis est finalement reporté', async () => {
    const delivered = {
      ...packageFixture,
      finalOutcome: 'DELIVERED' as const,
      actualCollectedAriary: 25_000,
      actualCourierId: 'courier-2',
      actualCourierName: 'Tiana',
      finalState: true,
      recoveredAriary: 25_000,
      chargedDeliveryFeeAriary: 3_000,
      amountToRemitAriary: 22_000,
    }
    const postponed = {
      ...delivered,
      finalOutcome: 'POSTPONED' as const,
      actualCollectedAriary: null,
      actualCourierId: null,
      actualCourierName: null,
      recoveredAriary: 0,
      chargedDeliveryFeeAriary: 0,
      amountToRemitAriary: 0,
      version: 3,
    }
    const deliveredDay = dayFixture(delivered)
    const postponedDay = dayFixture(postponed)
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(deliveredDay))
      .mockResolvedValueOnce(jsonResponse(closureFixture(deliveredDay)))
      .mockResolvedValueOnce(jsonResponse(postponedDay))
      .mockResolvedValueOnce(jsonResponse(closureFixture(postponedDay)))
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" couriers={couriers} onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Modifier' }))
    fireEvent.click(screen.getByRole('radio', { name: /Reporté/ }))
    expect(screen.queryByLabelText('Livreur réellement intervenu')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cet état' }))

    await waitFor(() => expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/admin/delivery-days/2026-08-24/packages/package-1/outcome',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ outcome: 'POSTPONED', actualCollectedAriary: null, actualCourierId: null, note: null, version: 2 }),
      }),
    ))
  })

  it('conserve le livreur planifié comme option de secours si les fiches ne sont pas chargées', async () => {
    vi.stubGlobal('fetch', dayFetch(dayFixture()))

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" couriers={[]} onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Renseigner' }))

    expect(screen.getByLabelText('Livreur réellement intervenu')).toHaveValue('Hery')
    fireEvent.focus(screen.getByLabelText('Livreur réellement intervenu'))
    expect(screen.getByRole('option', { name: 'Hery (fiche indisponible)' })).toBeInTheDocument()
  })

  it('permet de renseigner une journée composée uniquement de demandes express sans plan', async () => {
    const expressPackage = {
      ...packageFixture,
      deliveryRequestId: 'express-1',
      requestKind: 'EXPRESS' as const,
      dispatchPlanId: null,
    }
    vi.stubGlobal('fetch', dayFetch(expressOnlyDayFixture(expressPackage)))

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" couriers={couriers} onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    expect(await screen.findByText('Express en cours')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Renseigner' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Clôturer la journée' })).toBeDisabled()
    expect(screen.queryByText(/Aucun plan enregistré/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Renseigner' }))
    expect(screen.getByRole('radio', { name: /Reporté/ })).toBeEnabled()
  })

  it('demande de clôturer le client avant une journée express finalisée sans dispatch normal', async () => {
    const deliveredExpress = {
      ...packageFixture,
      deliveryRequestId: 'express-1',
      requestKind: 'EXPRESS' as const,
      dispatchPlanId: null,
      finalOutcome: 'DELIVERED' as const,
      actualCollectedAriary: 25_000,
      actualCourierId: 'courier-1',
      actualCourierName: 'Hery',
      finalState: true,
      recoveredAriary: 25_000,
      chargedDeliveryFeeAriary: 3_000,
      amountToRemitAriary: 22_000,
    }
    vi.stubGlobal('fetch', dayFetch(expressOnlyDayFixture(deliveredExpress)))

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" couriers={couriers} onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    expect(await screen.findByText('Prêt à clôturer')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clôturer le compte de Boutique Soa' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Clôturer la journée' })).toBeDisabled()
  })

  it('considère un express reporté comme final sans créer automatiquement la demande suivante', async () => {
    const postponedExpress = {
      ...packageFixture,
      deliveryRequestId: 'express-1',
      requestKind: 'EXPRESS' as const,
      dispatchPlanId: null,
      finalOutcome: 'POSTPONED' as const,
      finalState: true,
      actualCollectedAriary: null,
      recoveredAriary: 0,
      chargedDeliveryFeeAriary: 0,
      amountToRemitAriary: 0,
    }
    vi.stubGlobal('fetch', dayFetch(expressOnlyDayFixture(postponedExpress)))

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" couriers={couriers} onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    expect(await screen.findByText('À reprogrammer dans l’onglet Reportés après clôture')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Programmer demain' })).not.toBeInTheDocument()
    expect(screen.getByText('Prêt à clôturer')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clôturer le compte de Boutique Soa' })).toBeEnabled()
  })

  it('déverrouille immédiatement les documents et rémunérations du client clôturé', async () => {
    const delivered = {
      ...packageFixture,
      finalOutcome: 'DELIVERED' as const,
      actualCollectedAriary: 25_000,
      actualCourierId: 'courier-1',
      actualCourierName: 'Hery',
      finalState: true,
      recoveredAriary: 25_000,
      chargedDeliveryFeeAriary: 3_000,
      amountToRemitAriary: 22_000,
    }
    const operationDay = dayFixture(delivered)
    const readyOverview = closureFixture(operationDay)
    const closedOverview: ClientClosureOverview = {
      ...readyOverview,
      dayVersion: 4,
      currentClosureRevision: 1,
      closedClientCount: 1,
      allClientsClosed: true,
      clients: [{
        ...readyOverview.clients[0],
        readyToClose: false,
        closed: true,
        closureRevision: 1,
        reportId: 'report-1',
        closedAt: '2026-08-24T18:00:00Z',
        closedByAccountId: 'admin-1',
        version: 0,
      }],
    }
    const compensation = {
      serviceDate: '2026-08-24', closureRevision: 1, finalized: false,
      totalSuggestedAriary: 0, totalFinalAriary: 0,
      totalSuggestedCommissionAriary: 0, totalFinalCommissionAriary: 0,
      totalSuggestedFuelAriary: 0, totalFinalFuelAriary: 0,
      pendingReturnCount: 0, unresolvedFuelCalculationCount: 0,
      lines: [], fuelLines: [], fuelCalculations: [],
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(operationDay))
      .mockResolvedValueOnce(jsonResponse(readyOverview))
      .mockResolvedValueOnce(jsonResponse(closedOverview))
      .mockResolvedValueOnce(jsonResponse(compensation))
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" couriers={couriers} onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Clôturer le compte de Boutique Soa' }))
    fireEvent.click(screen.getByRole('button', { name: 'Clôturer ce client' }))

    await waitFor(() => expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/admin/delivery-days/2026-08-24/clients/account-1/close',
      expect.objectContaining({ body: JSON.stringify({ version: 3 }) }),
    ))
    expect(await screen.findByRole('button', { name: 'Télécharger le rapport PDF de Boutique Soa' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Modifier' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Clôturer la journée' })).toBeEnabled()

    expect(screen.queryByRole('button', { name: 'Rémunérations livreurs' })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('laisse confirmer le retour physique après la clôture du client', async () => {
    const returnedPackage = {
      ...packageFixture,
      finalOutcome: 'RETURN_TO_SENDER' as const,
      actualCollectedAriary: 0,
      actualCourierId: 'courier-1',
      actualCourierName: 'Hery',
      finalState: true,
      recoveredAriary: 0,
      chargedDeliveryFeeAriary: 3_000,
      amountToRemitAriary: -3_000,
    }
    const operationDay = dayFixture(returnedPackage)
    const closedOverview = closureFixture({ ...operationDay, status: 'CLOSED', closureRevision: 1 }, true)
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(operationDay))
      .mockResolvedValueOnce(jsonResponse({ ...closedOverview, dayStatus: 'DISPATCHED' }))
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" couriers={couriers} onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    expect(await screen.findByRole('button', { name: 'Modifier' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Retour effectué/ })).toBeEnabled()
  })

  it('autorise la clôture sans reprogrammer les reports et fige ceux déjà transmis', async () => {
    const postponed = {
      ...packageFixture,
      finalOutcome: 'POSTPONED' as const,
      finalState: true,
      actualCollectedAriary: null,
    }
    vi.stubGlobal('fetch', dayFetch(dayFixture(postponed)))

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" couriers={couriers} onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    expect(await screen.findByText('Prêt à clôturer')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clôturer le compte de Boutique Soa' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Programmer demain' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clôturer la journée' })).toBeDisabled()

    cleanup()
    const carried = { ...postponed, carriedForwardToPackageId: 'package-next', version: 4 }
    vi.stubGlobal('fetch', dayFetch(dayFixture(carried)))
    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" couriers={couriers} onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    expect(await screen.findByText('Prêt à clôturer')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Modifier' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clôturer le compte de Boutique Soa' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Clôturer la journée' })).toBeDisabled()
  })

  it('affiche les montants réellement facturés pour un colis reporté', async () => {
    const postponed = {
      ...packageFixture,
      finalOutcome: 'POSTPONED' as const,
      finalState: true,
      actualCollectedAriary: null,
      recoveredAriary: 0,
      chargedDeliveryFeeAriary: 0,
      amountToRemitAriary: 0,
    }
    vi.stubGlobal('fetch', dayFetch(dayFixture(postponed)))

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" couriers={couriers} onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    expect(await screen.findByText('Montants réalisés')).toBeInTheDocument()
    expect(screen.queryByText(/25.000 Ar/)).not.toBeInTheDocument()
    expect(screen.queryByText(/frais 3.000 Ar/)).not.toBeInTheDocument()
  })
})
