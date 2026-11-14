import type { ConfirmedLocation } from '../../api/admin'
import type { ManualDispatchDraft } from '../../api/manualDispatch'
import { calculateRoutingMatrix, type RoutingPoint } from '../../api/routing'
import { validPosition } from './manualDispatchGeography'
import { assignmentIds, packageCollector, type AssignmentPhase } from './manualDispatchLayout'

type Task = { id: string; node: number; collector?: number }
type Insertion = { courier: number; position: number; cost: number }
const EPSILON = 0.01

/** Optional proposal only: no capacity, timetable, forced hub visit or plan validation. */
export async function automaticallyAssignPhase(
  draft: ManualDispatchDraft, phase: AssignmentPhase, accessToken: string, signal?: AbortSignal,
): Promise<ManualDispatchDraft> {
  if (draft.status !== 'DRAFT' || draft.step !== phase || !draft.couriers.length) {
    throw new Error('Ouvrez une étape modifiable avec au moins un livreur pour proposer une répartition.')
  }
  const points: RoutingPoint[] = []
  const positions = new Map<string, number>()
  function node(location: ConfirmedLocation) {
    if (!validPosition(location)) throw new Error('Vérifiez les lieux avant de proposer une répartition. Le brouillon reste inchangé.')
    const key = `${location.latitude},${location.longitude}`
    let index = positions.get(key)
    if (index === undefined) {
      index = points.length
      positions.set(key, index)
      points.push({ id: `point-${index}`, latitude: location.latitude, longitude: location.longitude })
    }
    return index
  }
  const starts = draft.couriers.map((courier) => {
    const pickups = assignmentIds(draft, 'COLLECTION', courier.courierId)
    const last = draft.pickups.find((pickup) => pickup.id === pickups.at(-1))
    return node(phase === 'DELIVERY' && last ? last.location : courier.departure)
  })
  if (phase === 'DELIVERY' && draft.pickups.some((pickup) => !draft.collections.some((a) => a.pickupId === pickup.id))) {
    throw new Error('Affectez toutes les récupérations avant de répartir les livraisons.')
  }
  const tasks: Task[] = phase === 'COLLECTION'
    ? draft.pickups.map((pickup) => ({ id: pickup.id, node: node(pickup.location) }))
    : draft.packages.map((pkg) => ({ id: pkg.packageId, node: node(pkg.destination),
      collector: draft.couriers.findIndex((courier) => courier.courierId === packageCollector(draft, pkg.packageId)) }))
  if (!tasks.length) return draft
  if (tasks.some((task) => task.collector === -1)) throw new Error('Vérifiez les livreurs affectés aux récupérations avant de continuer.')
  // Coincident positions need no network request. Otherwise keep OSRM's directed distances;
  // an unavailable route is never replaced by a straight-line approximation.
  const matrix = points.length === 1 ? [[0]] : (await calculateRoutingMatrix(points, accessToken, signal)).distancesMeters
  signal?.throwIfAborted()
  const distance = (from: number, to: number) => matrix[from][to] ?? Infinity
  const routesCost = (route: Task[], courier: number) => {
    let cost = 0, previous = starts[courier]
    for (const task of route) { cost += distance(previous, task.node) + exchangeCost(task, courier); previous = task.node }
    return cost
  }
  function exchangeCost(task: Task, courier: number) {
    if (task.collector === undefined || task.collector === courier) return 0
    // A conservative preference, not a fictional meeting leg: avoid an exchange for a
    // negligible saving, especially when the two collection endpoints are far apart.
    return Math.max(1500, Math.min(distance(starts[task.collector], starts[courier]), distance(starts[courier], starts[task.collector])))
  }
  function insertion(route: Task[], courier: number, task: Task): Insertion | undefined {
    let best: Insertion | undefined
    for (let position = 0; position <= route.length; position++) {
      const before = position ? route[position - 1].node : starts[courier]
      const after = route[position]?.node
      const cost = distance(before, task.node) + (after === undefined ? 0 : distance(task.node, after) - distance(before, after)) + exchangeCost(task, courier)
      if (Number.isFinite(cost) && (!best || cost < best.cost - EPSILON
        || (Math.abs(cost - best.cost) <= EPSILON && position > best.position))) best = { courier, position, cost }
    }
    return best
  }
  function greedy(order: Task[], keepCollectors = false) {
    const routes: Task[][] = starts.map(() => [])
    for (const task of order) {
      let best: Insertion | undefined
      for (let courier = 0; courier < starts.length; courier++) {
        if (keepCollectors && task.collector !== courier) continue
        const candidate = insertion(routes[courier], courier, task)
        if (candidate && (!best || candidate.cost < best.cost - EPSILON
          || (Math.abs(candidate.cost - best.cost) <= EPSILON && routes[courier].length < routes[best.courier].length))) best = candidate
      }
      if (!best) return undefined
      routes[best.courier].splice(best.position, 0, task)
    }
    return routes
  }
  let improvementEvaluations = 0
  function improve(routes: Task[][]) {
    // Bounded, deterministic best relocations shorten routes and keep nearby stops
    // together. No fixed vehicle cost: using another courier is never penalized.
    for (let round = 0; round < 20; round++) {
      let best: { from: number; index: number; insertion: Insertion; saving: number } | undefined
      for (let from = 0; from < routes.length; from++) {
        const route = routes[from]
        for (let index = 0; index < route.length; index++) {
          const task = route[index]
          const before = index ? route[index - 1].node : starts[from]
          const after = route[index + 1]?.node
          const removed = distance(before, task.node) + (after === undefined ? 0 : distance(task.node, after) - distance(before, after)) + exchangeCost(task, from)
          if (!Number.isFinite(removed)) continue
          for (let courier = 0; courier < routes.length; courier++) {
            const target = courier === from ? route.filter((_, i) => i !== index) : routes[courier]
            // Keep the optional button responsive even for a large day's workload.
            // The current candidate is always complete when this budget is reached.
            improvementEvaluations += target.length + 1
            if (improvementEvaluations > 1_000_000) return routes
            const candidate = insertion(target, courier, task)
            const saving = candidate ? removed - candidate.cost : 0
            if (candidate && saving > EPSILON && (!best || saving > best.saving + EPSILON)) best = { from, index, insertion: candidate, saving }
          }
        }
      }
      if (!best) break
      const [task] = routes[best.from].splice(best.index, 1)
      routes[best.insertion.courier].splice(best.insertion.position, 0, task)
    }
    return routes
  }
  const stable = [...tasks].sort((a, b) => a.id.localeCompare(b.id))
  const farthestFirst = [...stable].sort((a, b) => {
    const nearest = (task: Task) => Math.min(...starts.map((start) => distance(start, task.node)))
    return nearest(b) - nearest(a) || a.id.localeCompare(b.id)
  })
  const candidates = [greedy(farthestFirst), greedy(stable), ...(phase === 'DELIVERY' ? [greedy(farthestFirst, true)] : [])]
    .filter((routes): routes is Task[][] => !!routes)
  // Also consider a complete manual layout: never replace a better existing route
  // merely because one greedy starting point was unlucky.
  const existing = draft.couriers.map((courier) => assignmentIds(draft, phase, courier.courierId)
    .map((id) => tasks.find((task) => task.id === id)).filter((task): task is Task => !!task))
  if (new Set(existing.flat().map((task) => task.id)).size === tasks.length && existing.flat().length === tasks.length
    && existing.every((route, courier) => Number.isFinite(routesCost(route, courier)))) candidates.push(existing)
  let best: Task[][] | undefined, bestCost = Infinity
  for (const candidate of candidates) {
    const routes = improve(candidate)
    const cost = routes.reduce((sum, route, courier) => sum + routesCost(route, courier), 0)
    if (cost < bestCost - EPSILON) { best = routes; bestCost = cost }
  }
  if (!best) throw new Error('Certains lieux ne sont pas reliés par la route. Le brouillon reste inchangé ; vous pouvez les affecter manuellement.')
  return phase === 'COLLECTION'
    ? { ...draft, collections: best.flatMap((route, courier) => route.map((task, order) => ({ pickupId: task.id, courierId: draft.couriers[courier].courierId, order }))) }
    : { ...draft, deliveries: best.flatMap((route, courier) => route.map((task, order) => ({ packageId: task.id, courierId: draft.couriers[courier].courierId, order }))) }
}
