import type { ConfirmedLocation } from '../../api/admin'

const COLORS = ['#e46824', '#3866a6', '#8a5aaa', '#268678', '#b84c70', '#99732e', '#4a7b98', '#8e6654']

export function manualDispatchCourierColor(id: string) {
  let hash = 2166136261
  for (const char of id) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619)
  return COLORS[(hash >>> 0) % COLORS.length]
}

export function validPosition(point?: ConfirmedLocation) {
  return !!point && Number.isFinite(point.latitude) && Number.isFinite(point.longitude)
    && Math.abs(point.latitude) <= 90 && Math.abs(point.longitude) <= 180
}

function distance(a: ConfirmedLocation, b: ConfirmedLocation) {
  const north = (a.latitude - b.latitude) * 111_195
  const east = (a.longitude - b.longitude) * 111_195 * Math.cos(b.latitude * Math.PI / 180)
  return Math.hypot(north, east)
}

/** A presentation aid only. No assignment, capacity or route optimization. */
export function groupByGeography<T extends { id: string; location: ConfirmedLocation }>(
  items: T[], center: ConfirmedLocation,
): { label: string; items: T[] }[] {
  const clusters: T[][] = []
  const invalid: T[] = []
  for (const item of [...items].sort((a, b) => b.location.latitude - a.location.latitude || a.id.localeCompare(b.id))) {
    if (!validPosition(item.location)) { invalid.push(item); continue }
    const group = clusters.find((members) => members.every((member) => distance(member.location, item.location) <= 1800))
    if (group) group.push(item)
    else clusters.push([item])
  }
  const labels = new Map<string, number>()
  const result = clusters.map((members) => {
    const average = { latitude: members.reduce((n, p) => n + p.location.latitude, 0) / members.length,
      longitude: members.reduce((n, p) => n + p.location.longitude, 0) / members.length }
    const north = average.latitude - center.latitude
    const east = (average.longitude - center.longitude) * Math.cos(center.latitude * Math.PI / 180)
    const sector = (Math.round(Math.atan2(east, north) / (Math.PI / 4)) + 8) % 8
    const label = distance(average, center) < 700 ? 'Centre' : ['Nord', 'Nord-est', 'Est', 'Sud-est', 'Sud', 'Sud-ouest', 'Ouest', 'Nord-ouest'][sector]
    labels.set(label, (labels.get(label) ?? 0) + 1)
    return { label, items: members }
  })
  const counters = new Map<string, number>()
  for (const group of result) {
    const count = (counters.get(group.label) ?? 0) + 1
    counters.set(group.label, count)
    if ((labels.get(group.label) ?? 0) > 1) group.label += ` · ${count}`
  }
  if (invalid.length) result.push({ label: 'Localisation à vérifier', items: invalid })
  return result
}
