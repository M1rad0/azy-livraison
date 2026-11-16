import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CourierCompensation, ExpressFuelCalculation } from '../../api/admin'
import { AdminCourierCompensationPanel } from './AdminCourierCompensationPanel'

function calculation(status: ExpressFuelCalculation['status']): ExpressFuelCalculation {
  const calculated = status === 'CALCULATED'
  return {
    requestId: 'express-1',
    clientDisplayName: 'Boutique Soa',
    pickupLabel: 'Analakely',
    status,
    returnToPickup: true,
    version: status === 'ROUTING_FAILED' ? 2 : 3,
    failureReason: status === 'ROUTING_FAILED' ? 'Le service routier est indisponible.' : null,
    totalDistanceMeters: calculated ? 15_000 : 0,
    totalSuggestedFuelAriary: calculated ? 1_875 : 0,
    totalFinalFuelAriary: calculated ? 1_875 : 0,
    lines: calculated ? [{
      requestId: 'express-1',
      clientDisplayName: 'Boutique Soa',
      pickupLabel: 'Analakely',
      courierId: 'courier-1',
      courierName: 'Hery',
      packageIds: ['package-1'],
      repositionDistanceMeters: 5_000,
      automaticDistanceMeters: 15_000,
      finalDistanceMeters: 15_000,
      fuelReimbursementEnabled: true,
      fuelEfficiencyKmPerLiter: 40,
      fuelPricePerLiterAriary: 5_000,
      suggestedFuelAriary: 1_875,
      finalFuelAriary: 1_875,
      manual: false,
      returnToPickup: true,
    }] : [],
  }
}

function compensation(status: ExpressFuelCalculation['status']): CourierCompensation {
  return {
    newAccrualsEnabled: true,
    serviceDate: '2026-09-06',
    closureRevision: 1,
    finalized: false,
    totalSuggestedAriary: 0,
    totalFinalAriary: 0,
    totalSuggestedCommissionAriary: 0,
    totalFinalCommissionAriary: 0,
    totalSuggestedFuelAriary: 0,
    totalFinalFuelAriary: 0,
    pendingReturnCount: 0,
    unresolvedFuelCalculationCount: status === 'ROUTING_FAILED' ? 1 : 0,
    lines: [],
    fuelLines: [],
    fuelCalculations: [calculation(status)],
  }
}

describe('AdminCourierCompensationPanel', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('ne propose plus de nouveaux gains ni de remboursement dans le nouveau fonctionnement', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200,
      json: async () => ({ ...compensation('CALCULATED'), newAccrualsEnabled: false }) }))
    render(<AdminCourierCompensationPanel accessToken="admin-token" serviceDate="2026-09-06" couriers={[]} />)
    expect(await screen.findByText('Ancien suivi des rémunérations')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Valider les rémunérations/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Recalculer/i })).not.toBeInTheDocument()
  })

  it('bloque la validation financière et permet de relancer un calcul routier échoué', async () => {
    const failed = compensation('ROUTING_FAILED')
    const resolved = compensation('CALCULATED')
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => failed })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => resolved.fuelCalculations[0] })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => resolved })
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminCourierCompensationPanel accessToken="admin-token" serviceDate="2026-09-06" couriers={[]} />)

    expect(await screen.findByText('Calcul impossible')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Valider les rémunérations/i })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: /Recalculer/i }))

    expect(await screen.findByText('Calculée')).toBeInTheDocument()
    expect(screen.getByText(/dont 5 km depuis l’express précédent/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: /Valider les rémunérations/i })).toBeEnabled())
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/admin/express-deliveries/express-1/fuel/recalculate',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ version: 2 }),
        headers: expect.objectContaining({ Authorization: 'Bearer admin-token' }),
      }),
    )
  })
})
