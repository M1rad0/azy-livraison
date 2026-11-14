import { describe, expect, it } from 'vitest'
import { draftFixture } from './manualDispatch.fixtures'
import { assignmentIds, assignPoint, changeManualStep, manualTransfers } from './manualDispatchLayout'
import { groupByGeography } from './manualDispatchGeography'

describe('répartition manuelle', () => {
  it('ne choisit aucun livreur et ne modifie jamais les positions', () => {
    const draft = draftFixture()
    const assigned = assignPoint(draft, 'COLLECTION', 'r1', 'c1')
    expect(draft.collections).toEqual([])
    expect(assigned.collections).toEqual([{ pickupId: 'r1', courierId: 'c1', order: 0 }])
    expect(assigned.deliveries).toEqual([])
    expect(assigned.packages).toBe(draft.packages)
    expect(assigned.pickups).toBe(draft.pickups)
  })

  it('initialise les livraisons chez le collecteur puis préserve les transferts manuels', () => {
    let draft = assignPoint(draftFixture(), 'COLLECTION', 'r1', 'c1')
    draft = assignPoint(draft, 'COLLECTION', 'r2', 'c2')
    draft = changeManualStep(draft, 'DELIVERY')
    expect(assignmentIds(draft, 'DELIVERY', 'c1')).toEqual(['p1', 'p2'])
    draft = assignPoint(draft, 'DELIVERY', 'p2', 'c2')
    draft = changeManualStep(changeManualStep(draft, 'COLLECTION'), 'DELIVERY')
    expect(assignmentIds(draft, 'DELIVERY', 'c2')).toEqual(['p3', 'p2'])
    expect(manualTransfers(draft)).toEqual([{ from: 'c1', to: 'c2', package: draft.packages[1] }])
  })

  it('réordonne par glisser-déposer et renumérote sans doublon', () => {
    let draft = draftFixture()
    for (const id of ['p1', 'p2', 'p3']) draft = assignPoint(draft, 'DELIVERY', id, 'c1')
    draft = assignPoint(draft, 'DELIVERY', 'p3', 'c1', 'p1')
    expect(assignmentIds(draft, 'DELIVERY', 'c1')).toEqual(['p3', 'p1', 'p2'])
    draft = assignPoint(draft, 'DELIVERY', 'p1', 'c2')
    expect(draft.deliveries.filter((d) => d.courierId === 'c1').map((d) => d.order)).toEqual([0, 1])
    draft = assignPoint(draft, 'DELIVERY', 'p2', '')
    expect(draft.deliveries.some((d) => d.packageId === 'p2')).toBe(false)
  })

  it('refuse les identifiants étrangers sans bloquer un colis trop lourd', () => {
    const draft = draftFixture()
    expect(assignPoint(draft, 'DELIVERY', 'p1', 'inconnu')).toBe(draft)
    expect(assignPoint(draft, 'DELIVERY', 'inconnu', 'c1')).toBe(draft)
    expect(assignPoint(draft, 'DELIVERY', 'p1', 'c1').deliveries).toHaveLength(1)
  })

  it('regroupe par proximité indépendamment du livreur, sans changer les coordonnées', () => {
    const center = { latitude: -18.9, longitude: 47.52 }
    const items = [
      { id: 'a', location: { latitude: -18.86, longitude: 47.52 } },
      { id: 'b', location: { latitude: -18.861, longitude: 47.521 } },
      { id: 'c', location: { latitude: -19, longitude: 47.52 } },
    ]
    const groups = groupByGeography(items, center)
    expect(groups).toHaveLength(2)
    expect(groups.find((g) => g.label === 'Nord')?.items.map((p) => p.id).sort()).toEqual(['a', 'b'])
    expect(groups.find((g) => g.label === 'Sud')?.items[0]).toBe(items[2])
  })
})
