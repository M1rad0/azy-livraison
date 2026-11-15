import type { ManualDispatchDraft } from '../../api/manualDispatch'

export function draftFixture(): ManualDispatchDraft {
  const point = { label: 'Analakely', latitude: -18.91, longitude: 47.52 }
  return {
    id: 'draft-1', serviceDate: '2026-11-14', version: 0, status: 'DRAFT', step: 'COLLECTION', hub: point,
    couriers: ['Baptiste', 'Jean'].map((name, i) => ({ courierId: `c${i + 1}`, name, vehicleType: 'MOTO', maxWeightKg: 1,
      averageSpeedKmh: 20, departure: point, departureAt: '2026-11-14T06:00:00+03:00' })),
    pickups: [{ id: 'r1', location: point, packageIds: ['p1', 'p2'] }, { id: 'r2', location: { ...point, label: 'Retrait sud', latitude: -19 }, packageIds: ['p3'] }],
    packages: ['p1', 'p2', 'p3'].map((packageId, i) => ({ packageId, deliveryRequestId: 'request', ownerAccountId: 'client', serviceClientName: 'Boutique',
      requestedDate: '2026-11-14', pickup: point, recipientName: `Destinataire ${i + 1}`, faritra: false,
      destination: { ...point, label: `Destination ${i + 1}` }, contact: '034', amountToCollectAriary: 5000,
      mobileMoneyPaid: false, effectiveWeightKg: 10 })),
    collections: [], deliveries: [],
  }
}
