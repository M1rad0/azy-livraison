import { describe, expect, it } from 'vitest'
import { endOfOneHourWindow, formatOneHourWindow } from './deliveryWindows'

describe('deliveryWindows', () => {
  it('calcule toujours une durée d’une heure sans imposer une heure ronde', () => {
    expect(endOfOneHourWindow('08:00')).toBe('09:00')
    expect(endOfOneHourWindow('18:30')).toBe('19:30')
  })

  it('indique explicitement le passage au lendemain', () => {
    expect(endOfOneHourWindow('23:30')).toBe('00:30')
    expect(formatOneHourWindow('23:30:00', '00:30:00', true))
      .toBe('23:30–00:30 (lendemain)')
  })
})
