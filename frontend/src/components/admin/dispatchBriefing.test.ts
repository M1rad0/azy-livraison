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

  it('generates concise French plain text with the date, landmarks, recipient contact, cash and delivery windows', () => {
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
    expect(text).toContain('Pharmacie du coin · Analakely, Antananarivo — contact : 034 11 222 33 — à encaisser : 5000 Ar — créneau : 08:30–11:45')
    expect(text).toContain('créneau : Matin')
    expect(text).not.toMatch(/Lova|Rakoto|Boutique|Destinataire|Analamanga|Madagascar|SECRET MEETING PLACE|p1|c1|3500|92|latitude|longitude/)
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

  it('adds each seller contact only once per pickup even when several parcels belong to that account', () => {
    const draft = allocatedFixture()
    const text = generateCourierBriefing(buildDispatchBriefing(draft).couriers[0], draft.serviceDate, { client: ' 034 99\n999 99 ' })
    const pickups = text.split('\n\nÉchanges :')[0]
    expect(pickups).toContain('1. Analakely — Boutique — contact vendeur : 034 99 999 99 — 2 colis.')
    expect(pickups.match(/034 99 999 99/g)).toHaveLength(1)
    const packageSections = text.split('\n\nÉchanges :')[1]
    expect(packageSections).not.toContain('034 99 999 99')
    expect(packageSections).not.toContain('Boutique')
    expect(packageSections).not.toContain('Destinataire')
  })

  it('keeps seller accounts distinct by ID when their display names are the same', () => {
    const draft = allocatedFixture()
    draft.packages[0].ownerAccountId = 'seller-a'
    draft.packages[1].ownerAccountId = 'seller-b'
    const text = generateCourierBriefing(buildDispatchBriefing(draft).couriers[0], draft.serviceDate, {
      'seller-a': '034 11 111 11', 'seller-b': '034 22 222 22',
    })
    const pickups = text.split('\n\nÉchanges :')[0]
    expect(pickups).toContain('Boutique — contact vendeur : 034 11 111 11 ; Boutique — contact vendeur : 034 22 222 22')
    expect(pickups.match(/034 11 111 11/g)).toHaveLength(1)
    expect(pickups.match(/034 22 222 22/g)).toHaveLength(1)
    expect(pickups).not.toMatch(/seller-a|seller-b/)
  })

  it('distinguishes unavailable seller profiles from known profiles without a contact', () => {
    const draft = allocatedFixture()
    const courier = buildDispatchBriefing(draft).couriers[0]
    for (const contacts of [undefined, {}, { other: '034' }]) {
      expect(generateCourierBriefing(courier, draft.serviceDate, contacts)).toContain('Boutique — contact vendeur indisponible')
    }
    for (const contact of [null, undefined, '', '   ']) {
      const text = generateCourierBriefing(courier, draft.serviceDate, { client: contact })
      expect(text).toContain('Boutique — contact vendeur non renseigné')
      expect(text).not.toContain('contact vendeur indisponible')
    }
  })

  it('identifies parcels at the same landmark by recipient contact in exchanges and the final list', () => {
    const draft = allocatedFixture()
    draft.packages[0].destination = { label: 'Gare\n routière', query: 'Ambodivona, Antananarivo, Analamanga, Madagascar', latitude: -18.9, longitude: 47.52 }
    draft.packages[1].destination = { ...draft.packages[0].destination }
    draft.packages[0].contact = '034 11 111 11'
    draft.packages[1].contact = '034 22 222 22'
    draft.deliveries = [{ packageId: 'p1', courierId: 'c2', order: 0 }, { packageId: 'p2', courierId: 'c2', order: 1 }, { packageId: 'p3', courierId: 'c2', order: 2 }]
    const couriers = buildDispatchBriefing(draft).couriers
    const collectionText = generateCourierBriefing(couriers[0], draft.serviceDate).split('\n\nÉchanges :')[1]
    expect(collectionText).toContain('- Gare routière · Ambodivona, Antananarivo — contact : 034 11 111 11')
    expect(collectionText).toContain('- Gare routière · Ambodivona, Antananarivo — contact : 034 22 222 22')
    const deliveryText = generateCourierBriefing(couriers[1], draft.serviceDate)
    const exchanges = deliveryText.split('\n\nÉchanges :')[1].split('\n\nTa liste finale de colis :')[0]
    expect(exchanges).toContain('- Gare routière · Ambodivona, Antananarivo — contact : 034 11 111 11')
    expect(exchanges).toContain('- Gare routière · Ambodivona, Antananarivo — contact : 034 22 222 22')
    const final = deliveryText.split('\n\nTa liste finale de colis :')[1]
    expect(final).toContain('1. Gare routière · Ambodivona, Antananarivo — contact : 034 11 111 11 — à encaisser : 5000 Ar')
    expect(final).toContain('2. Gare routière · Ambodivona, Antananarivo — contact : 034 22 222 22 — à encaisser : 5000 Ar')
    expect(deliveryText.split('\n\nÉchanges :')[1]).not.toMatch(/Boutique|Destinataire|Analamanga|Madagascar/)
    expect(final.match(/034 11 111 11/g)).toHaveLength(1)
    expect(final.match(/034 22 222 22/g)).toHaveLength(1)
  })

  it('explicitly indicates missing recipient contacts instead of falling back to personal names', () => {
    const draft = allocatedFixture()
    draft.packages[0].contact = '  '
    const couriers = buildDispatchBriefing(draft).couriers
    const exchangeText = generateCourierBriefing(couriers[0], draft.serviceDate).split('\n\nÉchanges :')[1]
    expect(exchangeText).toContain('Destination 1 — contact non renseigné')
    const finalText = generateCourierBriefing(couriers[2], draft.serviceDate).split('\n\nTa liste finale de colis :')[1]
    expect(finalText).toContain('Destination 1 — contact non renseigné — à encaisser : 5000 Ar')
    expect(finalText).not.toContain('Destinataire 1')
  })

  it('preserves the complete existing French output and uses French when no language is specified', () => {
    const draft = allocatedFixture()
    const courier = buildDispatchBriefing(draft).couriers[1]
    const sellerContacts = { client: '034 99 999 99' }
    const expected = [
      'Briefing du 15/11/2026 — Jean',
      '',
      'Récupérations :',
      '1. Retrait sud — Boutique — contact vendeur : 034 99 999 99 — 1 colis.',
      '',
      'Échanges :',
      'Rencontre avec Marie et Baptiste.',
      'Donner à Marie :',
      '- Destination 3 — contact : 034',
      'Prendre chez Baptiste :',
      '- Destination 2 — contact : 034',
      '',
      'Ta liste finale de colis :',
      '1. Destination 2 — contact : 034 — à encaisser : 5000 Ar',
    ].join('\n')
    expect(generateCourierBriefing(courier, '2026-11-15', sellerContacts)).toBe(expected)
    expect(generateCourierBriefing(courier, '2026-11-15', sellerContacts, 'fr')).toBe(expected)
  })

  it('generates an independent Malagasy briefing using the requested section and exchange wording', () => {
    const draft = allocatedFixture()
    draft.packages[1].destination = { label: 'Gare\n routière', query: 'Ambodivona, Antananarivo, Analamanga, Madagascar', latitude: -18.9, longitude: 47.52 }
    draft.packages[1].contact = ' 034 22\n222 22 '
    draft.packages[1].desiredDeliveryTime = '08:30:00'
    draft.packages[1].desiredDeliveryEndTime = '10:45:00'
    draft.packages[1].deliveryTimePreference = 'CUSTOM'
    const courier = buildDispatchBriefing(draft).couriers[1]
    const contacts = { client: ' 034 99\n999 99 ' }
    const french = generateCourierBriefing(courier, '2026-11-15', contacts, 'fr')
    const malagasy = generateCourierBriefing(courier, '2026-11-15', contacts, 'mg')
    expect(malagasy).toContain('Toromarika ny 15/11/2026 — Jean')
    expect(malagasy).toContain("Entana alaina :\n1. Retrait sud — Boutique — laharan'ny mpivarotra : 034 99 999 99 — 1 entana.")
    expect(malagasy).toContain("Fifanakalozana entana :\nMiona amin'i Marie sy Baptiste.")
    expect(malagasy).toContain("Omena an'i Marie :\n- Destination 3 — laharana : 034")
    expect(malagasy).toContain("Alaina any amin'i Baptiste :\n- Gare routière · Ambodivona, Antananarivo — laharana : 034 22 222 22")
    expect(malagasy).toContain('Ireto avy ny livraisons ataonao :\n1. Gare routière · Ambodivona, Antananarivo — laharana : 034 22 222 22 — vola alaina : 5000 Ar — fotoana : 08:30–10:45')
    expect(malagasy.split('\n\nFifanakalozana entana :')[1]).not.toMatch(/Boutique|Destinataire|Analamanga|Madagascar/)
    expect(malagasy).not.toMatch(/Récupérations :|Échanges :|Rencontre avec|Donner à|Prendre chez|Ta liste finale/)
    expect(generateCourierBriefing(courier, '2026-11-15', contacts, 'fr')).toBe(french)
    expect(generateCourierBriefing(courier, '2026-11-15', contacts)).toBe(french)
  })

  it('translates periods and next-day markers without changing place labels, times or amounts', () => {
    const draft = allocatedFixture()
    draft.packages[0].destination.label = 'Matin Soir (lendemain)'
    draft.packages[0].deliveryTimePreference = 'MORNING'
    draft.packages[1].deliveryTimePreference = 'AFTERNOON'
    draft.packages[2].deliveryTimePreference = 'CUSTOM'
    draft.packages[2].desiredDeliveryTime = '23:30:00'
    draft.packages[2].desiredDeliveryEndTime = '01:15:00'
    draft.packages[2].amountToCollectAriary = 15300
    draft.deliveries = draft.packages.map((item, index) => ({ packageId: item.packageId, courierId: 'c2', order: index }))
    const courier = buildDispatchBriefing(draft).couriers[1]
    const malagasy = generateCourierBriefing(courier, draft.serviceDate, undefined, 'mg')
    expect(malagasy).toContain('Matin Soir (lendemain) — laharana : 034 — vola alaina : 5000 Ar — fotoana : Maraina')
    expect(malagasy).toContain('fotoana : Hariva')
    expect(malagasy).toContain('vola alaina : 15300 Ar — fotoana : 23:30–01:15 (ny ampitso)')
    const french = generateCourierBriefing(courier, draft.serviceDate)
    expect(french).toContain('créneau : Matin')
    expect(french).toContain('créneau : Soir')
    expect(french).toContain('créneau : 23:30–01:15 (lendemain)')
  })

  it('translates empty missions and missing contacts in Malagasy without filling in invented information', () => {
    const draft = allocatedFixture()
    draft.deliveries = [{ packageId: 'p1', courierId: 'c1', order: 0 }, { packageId: 'p2', courierId: 'c1', order: 1 }, { packageId: 'p3', courierId: 'c2', order: 0 }]
    const idle = buildDispatchBriefing(draft).couriers[2]
    expect(generateCourierBriefing(idle, draft.serviceDate, undefined, 'mg')).toContain('Entana alaina :\nTsy misy entana alaina.\n\nFifanakalozana entana :\nTsy misy fifanakalozana entana.\n\nIreto avy ny livraisons ataonao :\nTsy misy livraison.')
    draft.packages[0].contact = ' '
    draft.packages[0].serviceClientName = ' '
    draft.packages[1].serviceClientName = ' '
    const courier = buildDispatchBriefing(draft).couriers[0]
    const missing = generateCourierBriefing(courier, draft.serviceDate, { client: null }, 'mg')
    expect(missing).toContain("Mpivarotra tsy voatondro — laharan'ny mpivarotra tsy voatondro")
    expect(missing).toContain('Destination 1 — laharana tsy voatondro — vola alaina : 5000 Ar')
    const unavailable = generateCourierBriefing(courier, draft.serviceDate, {}, 'mg')
    expect(unavailable).toContain("laharan'ny mpivarotra tsy azo alaina")
    expect(unavailable).not.toContain('contact non renseigné')
  })
})
