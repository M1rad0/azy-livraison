export function endOfOneHourWindow(start: string): string {
  const match = /^(\d{2}):(\d{2})/.exec(start)
  if (!match) return ''
  const hours = Number(match[1])
  const minutes = Number(match[2])
  if (hours > 23 || minutes > 59) return ''
  return `${String((hours + 1) % 24).padStart(2, '0')}:${match[2]}`
}

export function formatOneHourWindow(
  start?: string | null,
  end?: string | null,
  endsNextDay?: boolean,
): string | undefined {
  if (!start) return undefined
  const normalizedStart = start.slice(0, 5)
  const normalizedEnd = end?.slice(0, 5) || endOfOneHourWindow(normalizedStart)
  const nextDay = endsNextDay ?? normalizedStart.startsWith('23:')
  return `${normalizedStart}–${normalizedEnd}${nextDay ? ' (lendemain)' : ''}`
}
