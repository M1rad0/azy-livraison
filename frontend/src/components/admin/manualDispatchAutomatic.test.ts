import { beforeEach, describe, expect, it, vi } from 'vitest'
import { calculateRoutingMatrix } from '../../api/routing'
import { draftFixture } from './manualDispatch.fixtures'
import { automaticallyAssignPhase } from './manualDispatchAutomatic'
import { assignmentIds, assignPoint, changeManualStep, manualTransfers } from './manualDispatchLayout'

vi.mock('../../api/routing', () => ({ calculateRoutingMatrix: vi.fn() }))
const point = (latitude: number) => ({ latitude, longitude: 0 })
function oppositeAxes() {
  const draft = draftFixture()
  draft.couriers[0].departure = point(0)
  draft.couriers[1].departure = point(10)
  draft.pickups[0].location = point(1)
  draft.pickups[1].location = point(9)
  draft.packages.forEach((pkg, i) => { pkg.destination = point(i < 2 ? 2 : 8) })
  return draft
}

describe('répartition automatique facultative', () => {
  beforeEach(() => {
    vi.mocked(calculateRoutingMatrix).mockReset().mockImplementation(async (points) => ({
      pointCount: points.length,
      distancesMeters: points.map((a) => points.map((b) => Math.hypot(a.latitude - b.latitude, a.longitude - b.longitude) * 1000)),
    }))
  })

  it('répartit les récupérations selon les départs et les axes sans toucher aux colis', async () => {
    const draft = oppositeAxes(), original = structuredClone(draft)
    const next = await automaticallyAssignPhase(draft, 'COLLECTION', 'token')
    expect(assignmentIds(next, 'COLLECTION', 'c1')).toEqual(['r1'])
    expect(assignmentIds(next, 'COLLECTION', 'c2')).toEqual(['r2'])
    expect(next.deliveries).toBe(draft.deliveries)
    expect(next.step).toBe('COLLECTION')
    expect(next.packages).toBe(draft.packages)
    expect(draft).toEqual(original)
  })

  it('ignore intégralement poids, capacité, vitesse et créneaux', async () => {
    const draft = oppositeAxes()
    const next = await automaticallyAssignPhase(draft, 'COLLECTION', 'token')
    draft.couriers.forEach((courier) => { courier.maxWeightKg = .001; courier.averageSpeedKmh = 1 })
    draft.packages.forEach((pkg) => { pkg.effectiveWeightKg = 99999; pkg.desiredDeliveryTime = '00:01' })
    expect((await automaticallyAssignPhase(draft, 'COLLECTION', 'token')).collections).toEqual(next.collections)
  })

  it('commence les livraisons à la dernière récupération et conserve leurs affectations', async () => {
    let draft = assignPoint(oppositeAxes(), 'COLLECTION', 'r1', 'c1')
    draft = assignPoint(draft, 'COLLECTION', 'r2', 'c2')
    draft = changeManualStep(draft, 'DELIVERY')
    const next = await automaticallyAssignPhase(draft, 'DELIVERY', 'token')
    expect(next.collections).toBe(draft.collections)
    expect(manualTransfers(next)).toEqual([])
    expect(assignmentIds(next, 'DELIVERY', 'c1')).toEqual(['p1', 'p2'])
    const points = vi.mocked(calculateRoutingMatrix).mock.calls[0][0]
    expect(points.slice(0, 2).map((p) => p.latitude)).toEqual([1, 9])
    expect(points.some((p) => p.latitude === 0 || p.latitude === 10)).toBe(false)
  })

  it('ne crée pas d’échange pour un petit gain', async () => {
    let draft = draftFixture()
    draft.couriers.forEach((courier) => { courier.departure = point(0) })
    draft.pickups.forEach((pickup) => { pickup.location = point(0) })
    draft.packages.forEach((pkg, i) => { pkg.destination = point(i ? -.1 : .1) })
    draft = assignPoint(assignPoint(draft, 'COLLECTION', 'r1', 'c1'), 'COLLECTION', 'r2', 'c2')
    draft = changeManualStep(draft, 'DELIVERY')
    expect(manualTransfers(await automaticallyAssignPhase(draft, 'DELIVERY', 'token'))).toHaveLength(0)
  })

  it('regroupe deux destinations du même axe quand un échange évite un long détour', async () => {
    let draft = draftFixture()
    draft.couriers.forEach((courier) => { courier.departure = point(0) })
    draft.pickups.forEach((pickup) => { pickup.location = point(0) })
    draft.packages.forEach((pkg, i) => { pkg.destination = point(i ? -10 : 10) })
    draft = assignPoint(assignPoint(draft, 'COLLECTION', 'r1', 'c1'), 'COLLECTION', 'r2', 'c2')
    draft = changeManualStep(draft, 'DELIVERY')
    const next = await automaticallyAssignPhase(draft, 'DELIVERY', 'token')
    expect(assignmentIds(next, 'DELIVERY', 'c1')).toEqual(['p1'])
    expect(new Set(assignmentIds(next, 'DELIVERY', 'c2'))).toEqual(new Set(['p2', 'p3']))
    expect(manualTransfers(next).map((transfer) => transfer.package.packageId)).toEqual(['p2'])
  })

  it('ne force aucun retour au siège et ordonne une route ouverte', async () => {
    const draft = oppositeAxes()
    draft.couriers = draft.couriers.slice(0, 1)
    draft.pickups[0].location = point(9)
    draft.pickups[1].location = point(1)
    const next = await automaticallyAssignPhase(draft, 'COLLECTION', 'token')
    expect(assignmentIds(next, 'COLLECTION', 'c1')).toEqual(['r2', 'r1'])
    expect(next.hub).toBe(draft.hub)
  })

  it('conserve les sens uniques au lieu de symétriser les distances', async () => {
    const draft = oppositeAxes()
    draft.couriers = draft.couriers.slice(0, 1)
    vi.mocked(calculateRoutingMatrix).mockResolvedValue({ pointCount: 3, distancesMeters: [[0, 10, 20], [10, 0, 1000], [20, 1, 0]] })
    const next = await automaticallyAssignPhase(draft, 'COLLECTION', 'token')
    expect(assignmentIds(next, 'COLLECTION', 'c1')).toEqual(['r2', 'r1'])
  })

  it('déduplique les coordonnées et ne fait aucun appel si tous les lieux coïncident', async () => {
    const draft = draftFixture()
    draft.pickups[1].location = draft.hub
    const next = await automaticallyAssignPhase(draft, 'COLLECTION', 'token')
    expect(calculateRoutingMatrix).not.toHaveBeenCalled()
    expect(next.collections).toHaveLength(2)
    expect(new Set(next.collections.map((a) => a.courierId)).size).toBe(2)
  })

  it('reste déterministe, complet et sans doublons après plusieurs propositions', async () => {
    const draft = oppositeAxes()
    const next = await automaticallyAssignPhase(draft, 'COLLECTION', 'token')
    expect((await automaticallyAssignPhase(next, 'COLLECTION', 'token')).collections).toEqual(next.collections)
    expect(new Set(next.collections.map((a) => a.pickupId)).size).toBe(draft.pickups.length)
    next.couriers.forEach((courier) => expect(next.collections.filter((a) => a.courierId === courier.courierId).map((a) => a.order)).toEqual([0]))
  })

  it('refuse les points invalides sans appel ni modification', async () => {
    const draft = oppositeAxes()
    draft.pickups[0].location.latitude = NaN
    await expect(automaticallyAssignPhase(draft, 'COLLECTION', 'token')).rejects.toThrow('Vérifiez les lieux')
    expect(calculateRoutingMatrix).not.toHaveBeenCalled()
    expect(draft.collections).toEqual([])
  })

  it('laisse le brouillon intact si les routes ou le service sont indisponibles', async () => {
    const draft = oppositeAxes(), original = structuredClone(draft)
    vi.mocked(calculateRoutingMatrix).mockImplementation(async (points) => ({ pointCount: points.length,
      distancesMeters: points.map((_, i) => points.map((__, j) => i === j ? 0 : null)) }))
    await expect(automaticallyAssignPhase(draft, 'COLLECTION', 'token')).rejects.toThrow('pas reliés')
    vi.mocked(calculateRoutingMatrix).mockRejectedValueOnce(new Error('Indisponible'))
    await expect(automaticallyAssignPhase(draft, 'COLLECTION', 'token')).rejects.toThrow('Indisponible')
    expect(draft).toEqual(original)
  })

  it('ne touche jamais à une autre étape ou à un plan finalisé', async () => {
    const draft = oppositeAxes()
    await expect(automaticallyAssignPhase(draft, 'DELIVERY', 'token')).rejects.toThrow('étape modifiable')
    draft.status = 'FINALIZED'
    await expect(automaticallyAssignPhase(draft, 'COLLECTION', 'token')).rejects.toThrow('étape modifiable')
    expect(calculateRoutingMatrix).not.toHaveBeenCalled()
  })

  it('termine une grande répartition avec des ordres continus et sans perte', async () => {
    const draft = draftFixture()
    const courier = draft.couriers[0]
    draft.couriers = Array.from({ length: 15 }, (_, i) => ({ ...courier, courierId: `c${i}`, departure: {
      latitude: -18.9 + i * .001, longitude: 47.5,
    } }))
    draft.pickups = Array.from({ length: 220 }, (_, i) => ({ id: `r${i}`, packageIds: [], location: {
      latitude: -18.9 + Math.sin(i) * .05, longitude: 47.5 + Math.cos(i) * .05,
    } }))
    const next = await automaticallyAssignPhase(draft, 'COLLECTION', 'token')
    expect(next.collections).toHaveLength(220)
    expect(new Set(next.collections.map((a) => a.pickupId)).size).toBe(220)
    next.couriers.forEach((c) => {
      const orders = next.collections.filter((a) => a.courierId === c.courierId).map((a) => a.order)
      expect(orders).toEqual(orders.map((_, i) => i))
    })
  })
})
