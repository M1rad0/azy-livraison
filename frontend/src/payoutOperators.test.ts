import { describe, expect, it } from 'vitest'
import { normalizePayoutOperator, PAYOUT_OPERATORS } from './payoutOperators'

describe('payout operators', () => {
  it('offers only the three mobile-money operators', () => {
    expect(PAYOUT_OPERATORS).toEqual(['MVola', 'Orange Money', 'Airtel Money'])
  })

  it.each([
    ['MVola', 'MVola'], [' mvola ', 'MVola'], ['M_VOLA', 'MVola'],
    ['Orange Money', 'Orange Money'], ['orange-money', 'Orange Money'], ['ORANGE_MONEY', 'Orange Money'],
    ['Airtel Money', 'Airtel Money'], ['airtelmoney', 'Airtel Money'], [' Airtel  Money ', 'Airtel Money'],
  ])('normalizes supported spelling %s', (value, expected) => {
    expect(normalizePayoutOperator(value)).toBe(expected)
  })

  it.each(['', 'Banque', 'Virement', 'Cash', 'Orange', 'Airtel', 'Other MVola'])('does not substitute a supported operator for %s', (value) => {
    expect(normalizePayoutOperator(value)).toBe('')
  })
})
