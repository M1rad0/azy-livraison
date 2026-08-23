import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { DispatchPlan } from '../../api/admin'
import { AdminPlanControls } from './AdminPlanControls'

function plan(id: string, courierId: string, courierName: string, recipientName: string): DispatchPlan {
  return {
    plan: {
      id,
      serviceDate: '2026-08-24',
      status: 'READY',
      mode: 'AUTOMATIC',
      revision: 1,
      version: 0,
      editable: true,
      strategy: 'VIA_HUB',
      hub: { label: 'Siège', latitude: -18.9, longitude: 47.52 },
      selectedPackageCount: 1,
      assignedPackageCount: 1,
      unassignedPackageCount: 0,
      selectedCourierCount: 1,
      usedCourierCount: 1,
      createdAt: '2026-08-24T06:00:00Z',
    },
    packages: [{
      packageId: `package-${id}`,
      deliveryRequestId: `request-${id}`,
      assigned: true,
      collectorCourierId: courierId,
      distributorCourierId: courierId,
      transferred: false,
      deliveryOrder: 1,
      recipientName,
      faritra: false,
      contact: '034 00 000 00',
      amountToCollectAriary: 10_000,
      mobileMoneyPaid: false,
      effectiveWeightKg: 1,
      destination: { label: 'Destination', latitude: -18.91, longitude: 47.53 },
    }],
    couriers: [{
      courierId,
      name: courierName,
      vehicleType: 'MOTO',
      maxWeightKg: 20,
      averageSpeedKmh: 25,
      departureAt: '2026-08-24T05:00:00Z',
      departure: { label: 'Départ', latitude: -18.92, longitude: 47.5 },
      used: true,
      collectionRole: true,
      distributionRole: true,
      hubDepartureAt: '2026-08-24T06:30:00Z',
    }],
    stops: [],
  }
}

describe('AdminPlanControls', () => {
  it('réinitialise l’éditeur quand une autre révision est ouverte', async () => {
    const first = plan('plan-1', 'courier-1', 'Hery', 'Rakoto')
    const second = plan('plan-2', 'courier-2', 'Fara', 'Soa')
    const { rerender } = render(
      <AdminPlanControls accessToken="token" plan={first} couriers={[]} onChanged={vi.fn()} />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Modifier le plan' }))
    expect(screen.getByLabelText('Livreur pour Rakoto')).toHaveValue('Hery')

    rerender(<AdminPlanControls accessToken="token" plan={second} couriers={[]} onChanged={vi.fn()} />)
    await waitFor(() => expect(screen.queryByLabelText('Livreur pour Rakoto')).not.toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: 'Modifier le plan' }))
    expect(screen.getByLabelText('Livreur pour Soa')).toHaveValue('Fara')
  })
})
