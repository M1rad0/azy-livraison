import type { ManualDispatchDraft, ManualDispatchStep } from '../../api/manualDispatch'

export type AssignmentPhase = 'COLLECTION' | 'DELIVERY'

export function assignmentIds(draft: ManualDispatchDraft, phase: AssignmentPhase, courierId: string) {
  return phase === 'COLLECTION'
    ? draft.collections.filter((a) => a.courierId === courierId).sort((a, b) => a.order - b.order).map((a) => a.pickupId)
    : draft.deliveries.filter((a) => a.courierId === courierId).sort((a, b) => a.order - b.order).map((a) => a.packageId)
}

/** Move and reorder without mutating snapshots or inventing an assignment. */
export function assignPoint(draft: ManualDispatchDraft, phase: AssignmentPhase, id: string, courierId: string, beforeId?: string): ManualDispatchDraft {
  if (id === beforeId || (courierId && !draft.couriers.some((c) => c.courierId === courierId))) return draft
  const valid = phase === 'COLLECTION' ? draft.pickups.some((p) => p.id === id) : draft.packages.some((p) => p.packageId === id)
  if (!valid) return draft
  const lanes = draft.couriers.map((c) => {
    const ids = assignmentIds(draft, phase, c.courierId).filter((item) => item !== id)
    if (c.courierId === courierId) {
      const position = beforeId ? ids.indexOf(beforeId) : -1
      ids.splice(position < 0 ? ids.length : position, 0, id)
    }
    return { courierId: c.courierId, ids }
  })
  return phase === 'COLLECTION'
    ? { ...draft, collections: lanes.flatMap((lane) => lane.ids.map((pickupId, order) => ({ pickupId, courierId: lane.courierId, order }))) }
    : { ...draft, deliveries: lanes.flatMap((lane) => lane.ids.map((packageId, order) => ({ packageId, courierId: lane.courierId, order }))) }
}

export function changeManualStep(draft: ManualDispatchDraft, step: ManualDispatchStep): ManualDispatchDraft {
  let next = { ...draft, step }
  if (step !== 'DELIVERY') return next
  // At first opening, the collector keeps their packages. Later user edits are preserved.
  for (const pickup of draft.pickups) {
    const collector = draft.collections.find((c) => c.pickupId === pickup.id)?.courierId
    if (!collector) continue
    for (const id of pickup.packageIds) {
      if (!next.deliveries.some((d) => d.packageId === id)) next = assignPoint(next, 'DELIVERY', id, collector)
    }
  }
  return next
}

export function packageCollector(draft: ManualDispatchDraft, packageId: string) {
  const pickup = draft.pickups.find((p) => p.packageIds.includes(packageId))
  return draft.collections.find((c) => c.pickupId === pickup?.id)?.courierId
}

export function manualTransfers(draft: ManualDispatchDraft) {
  return draft.packages.flatMap((p) => {
    const from = packageCollector(draft, p.packageId)
    const to = draft.deliveries.find((d) => d.packageId === p.packageId)?.courierId
    return from && to && from !== to ? [{ from, to, package: p }] : []
  })
}
