import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DailyOperation, DailyOperationPackage } from '../../api/admin'
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
  finalOutcome: null,
  actualCollectedAriary: null,
  finalState: false,
  recoveredAriary: 0,
  chargedDeliveryFeeAriary: 0,
  amountToRemitAriary: 0,
  version: 2,
}

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
      finalState: true,
      recoveredAriary: 25_000,
      chargedDeliveryFeeAriary: 3_000,
      amountToRemitAriary: 22_000,
      version: 3,
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(initial))
      .mockResolvedValueOnce(jsonResponse(dayFixture(updatedPackage)))
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Renseigner' }))
    expect(screen.getByText(/Mobile Money reste informatif/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Montant réellement récupéré/)).toHaveValue(25_000)
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer cet état' }))

    await waitFor(() => expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/admin/delivery-days/2026-08-24/packages/package-1/outcome',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ outcome: 'DELIVERED', actualCollectedAriary: 25_000, note: null, version: 2 }),
      }),
    ))
  })

  it('interdit la clôture tant qu’un report n’est pas programmé et fige celui déjà transmis', async () => {
    const postponed = {
      ...packageFixture,
      finalOutcome: 'POSTPONED' as const,
      finalState: true,
      actualCollectedAriary: null,
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(dayFixture(postponed))))

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    expect(await screen.findByText('1 colis reporté reste à programmer')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clôturer la journée' })).toBeDisabled()

    cleanup()
    const carried = { ...postponed, carriedForwardToPackageId: 'package-next', version: 4 }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(dayFixture(carried))))
    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    expect(await screen.findByText('Tous les colis ont un état final')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Modifier' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clôturer la journée' })).toBeEnabled()
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
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(dayFixture(postponed))))

    render(<AdminDailyOperationsPanel accessToken="admin-token" date="2026-08-24" onDateChange={vi.fn()} onOpenDispatch={vi.fn()} onChanged={vi.fn()} />)

    expect(await screen.findByText('Montants réalisés')).toBeInTheDocument()
    expect(screen.queryByText(/25.000 Ar/)).not.toBeInTheDocument()
    expect(screen.queryByText(/frais 3.000 Ar/)).not.toBeInTheDocument()
  })
})
