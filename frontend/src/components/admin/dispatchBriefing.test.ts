import { describe, expect, it } from 'vitest'
import type { ManualDispatchDraft } from '../../api/manualDispatch'
import { buildDispatchBriefing, generateCourierBriefing } from './dispatchBriefing'
import { draftFixture } from './manualDispatch.fixtures'

function allocatedFixture(): ManualDispatchDraft {
  const draft = draftFixture()
  return {
    ...draft, status: 'FINALIZED', step: 'REVIEW',
    couriers: [...draft.couriers, { ...draft.couriers[0], courierId: 'c3', name: 'Marie' }],
    collections: [{ pickupId: 'r1', courierId: 'c1', order: 0 }, { pickupId: 'r2', courierId: 'c2', order: 0 }],
    deliveries: [{ packageId: 'p1', courierId: 'c3', order: 1 }, { packageId: 'p2', courierId: 'c2', order: 0 }, { packageId: 'p3', courierId: 'c3', order: 0 }],
  }
}

describe('dispatch briefing model', () => {
  it('handles collecting-only, delivering-only and mixed couriers without inventing routes', () => {
    const result = buildDispatchBriefing(allocatedFixture())
    expect(result.warnings).toEqual([])
    expect(result.couriers.map((item) => item.courier.name)).toEqual(['Baptiste', 'Jean', 'Marie'])
    const [collector, mixed, deliverer] = result.couriers
    expect(collector.pickups.map((item) => item.pickup.id)).toEqual(['r1'])
    expect(collector.pickups[0].clients).toEqual(['Boutique'])
    expect(collector.pickups[0].packages.map((item) => item.packageId)).toEqual(['p1', 'p2'])
    expect(collector.deliveries).toEqual([])
    expect(collector.give.map((item) => item.courierId)).toEqual(['c3', 'c2'])
    expect(mixed.pickups.map((item) => item.pickup.id)).toEqual(['r2'])
    expect(mixed.give[0].packages[0].packageId).toBe('p3')
    expect(mixed.take[0].packages[0].packageId).toBe('p2')
    expect(deliverer.pickups).toEqual([])
    expect(deliverer.deliveries.map((item) => item.packageId)).toEqual(['p3', 'p1'])
    expect(deliverer.take).toHaveLength(2)
  })

  it('groups packages by directional peer IDs and keeps bidirectional exchanges separate', () => {
    const draft = allocatedFixture()
    draft.deliveries = [{ packageId: 'p1', courierId: 'c2', order: 1 }, { packageId: 'p2', courierId: 'c2', order: 0 }, { packageId: 'p3', courierId: 'c1', order: 0 }]
    const result = buildDispatchBriefing(draft)
    expect(result.transfers).toHaveLength(2)
    expect(result.transfers[0]).toMatchObject({ fromCourierId: 'c1', toCourierId: 'c2' })
    expect(result.transfers[0].packages.map((item) => item.packageId)).toEqual(['p2', 'p1'])
    expect(result.transfers[1]).toMatchObject({ fromCourierId: 'c2', toCourierId: 'c1' })
    expect(result.couriers[0].give).toHaveLength(1)
    expect(result.couriers[0].take).toHaveLength(1)
    const text = generateCourierBriefing(result.couriers[0], draft.serviceDate)
    expect(text).toContain('Rencontre avec Jean.')
    expect(text).not.toContain('Jean et Jean')
    expect(text.indexOf('Donner à Jean')).toBeLessThan(text.indexOf('Prendre chez Jean'))
  })

  it('has no exchange when collectors retain their packages and keeps an idle courier visible', () => {
    const draft = allocatedFixture()
    draft.deliveries = [{ packageId: 'p1', courierId: 'c1', order: 1 }, { packageId: 'p2', courierId: 'c1', order: 0 }, { packageId: 'p3', courierId: 'c2', order: 0 }]
    const result = buildDispatchBriefing(draft)
    expect(result.transfers).toEqual([])
    expect(result.couriers[0].give).toEqual([])
    expect(result.couriers[0].take).toEqual([])
    expect(generateCourierBriefing(result.couriers[2], draft.serviceDate)).toContain('Aucune récupération.\n\nÉchanges :\nAucun échange de colis.\n\nTa liste finale de colis :\nAucune livraison.')
  })

  it('uses assigned pickup order and does not mutate the snapshot', () => {
    const draft = allocatedFixture()
    draft.collections = [{ pickupId: 'r1', courierId: 'c1', order: 4 }, { pickupId: 'r2', courierId: 'c1', order: 1 }]
    const original = structuredClone(draft)
    expect(buildDispatchBriefing(draft).couriers[0].pickups.map((item) => item.pickup.id)).toEqual(['r2', 'r1'])
    expect(draft).toEqual(original)
  })

  it('warns about missing references and unassigned parcels rather than creating fake peers', () => {
    const draft = allocatedFixture()
    draft.collections = [{ pickupId: 'r1', courierId: 'absent-courier', order: 0 }, { pickupId: 'absent-pickup', courierId: 'c2', order: 0 }]
    draft.deliveries = [{ packageId: 'p1', courierId: 'absent-courier', order: 0 }, { packageId: 'absent-package', courierId: 'c1', order: 0 }]
    draft.pickups[0].packageIds.push('absent-package')
    draft.pickups[1].packageIds = []
    const result = buildDispatchBriefing(draft)
    expect(result.warnings).toEqual(expect.arrayContaining([
      'Un point de récupération fait référence à un colis absent du dispatch.',
      'Une affectation de récupération fait référence à un livreur ou à un point absent du dispatch.',
      'Une affectation de livraison fait référence à un livreur ou à un colis absent du dispatch.',
      'Le point de récupération de Destinataire 3 est absent du dispatch.',
      'Aucun livreur n’est affecté à la livraison de Destinataire 1.',
    ]))
    expect(result.transfers).toEqual([])
    expect(JSON.stringify(result)).not.toContain('absent-courier')
  })

  it('does not duplicate pickups or assignments when historical references conflict', () => {
    const draft = allocatedFixture()
    draft.collections.push({ pickupId: 'r1', courierId: 'c2', order: 1 })
    draft.deliveries.push({ packageId: 'p1', courierId: 'c1', order: 3 })
    draft.pickups[1].packageIds.push('p1')
    const result = buildDispatchBriefing(draft)
    expect(result.warnings).toHaveLength(3)
    expect(result.couriers.flatMap((item) => item.deliveries).filter((item) => item.packageId === 'p1')).toHaveLength(1)
    expect(result.couriers.flatMap((item) => item.pickups).flatMap((item) => item.packages).filter((item) => item.packageId === 'p1')).toHaveLength(1)
  })

  it('distinguishes couriers with the same name using their own ID and contact', () => {
    const draft = allocatedFixture()
    draft.couriers[0].name = 'Jean'
    draft.couriers[0].contact = '034 11 111 11'
    draft.couriers[1].contact = '034 22 222 22'
    draft.deliveries = [{ packageId: 'p1', courierId: 'c2', order: 1 }, { packageId: 'p2', courierId: 'c2', order: 0 }, { packageId: 'p3', courierId: 'c1', order: 0 }]
    const result = buildDispatchBriefing(draft)
    expect(result.transfers).toHaveLength(2)
    expect(result.couriers[0].give[0].courierName).toBe('Jean (034 22 222 22)')
    expect(result.couriers[1].give[0].courierName).toBe('Jean (034 11 111 11)')
  })

  it('generates concise French plain text with the date, landmarks, seller, contact, cash and delivery windows', () => {
    const draft = allocatedFixture()
    draft.hub.label = 'SECRET MEETING PLACE'
    draft.packages[0] = {
      ...draft.packages[0], recipientName: '  Lova\n Rakoto ', contact: ' 034 11\n222 33 ',
      destination: { label: 'Pharmacie du coin', destinationQuery: 'Analakely, Antananarivo, Analamanga, Madagascar', latitude: -18.91, longitude: 47.52 },
      desiredDeliveryTime: '08:30:00', desiredDeliveryEndTime: '11:45:00', deliveryTimePreference: 'CUSTOM',
      pricingSnapshot: { deliveryFeeAriary: 3500 }, effectiveWeightKg: 92,
    }
    draft.packages[2].deliveryTimePreference = 'MORNING'
    const text = generateCourierBriefing(buildDispatchBriefing(draft).couriers[2], '2026-11-15')
    expect(text).toContain('Briefing du 15/11/2026 — Marie')
    expect(text).toContain('Lova Rakoto — Pharmacie du coin · Analakely, Antananarivo — vendeur : Boutique — contact : 034 11 222 33 — à encaisser : 5000 Ar — créneau : 08:30–11:45')
    expect(text).toContain('créneau : Matin')
    expect(text).not.toMatch(/Analamanga|Madagascar|SECRET MEETING PLACE|p1|c1|3500|92|latitude|longitude/)
    expect(text.indexOf('Récupérations :')).toBeLessThan(text.indexOf('Échanges :'))
    expect(text.indexOf('Échanges :')).toBeLessThan(text.indexOf('Ta liste finale de colis :'))
  })

  it('shows Soir and overnight windows without inventing an arrival time', () => {
    const draft = allocatedFixture()
    draft.packages[0].deliveryTimePreference = 'AFTERNOON'
    draft.packages[2].desiredDeliveryTime = '23:30:00'
    draft.packages[2].desiredDeliveryEndTime = '01:00:00'
    draft.packages[2].deliveryTimePreference = 'CUSTOM'
    const text = generateCourierBriefing(buildDispatchBriefing(draft).couriers[2], '2026-11-15')
    expect(text).toContain('créneau : Soir')
    expect(text).toContain('créneau : 23:30–01:00 (lendemain)')
    expect(text).not.toContain('arrivée')
  })
})
