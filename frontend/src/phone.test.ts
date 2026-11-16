import { describe, expect, it } from 'vitest'
import { isPhonePayoutOperator, isValidPhoneNumber, normalizePhoneNumber, normalizePayoutNumber, PHONE_NUMBER_ERROR, PHONE_NUMBER_EXAMPLE } from './phone'

describe('Madagascar telephone input normalization', () => {
  it.each([
    ['0341679343', '0341679343'], [' 034 16 793 43 ', '0341679343'],
    ['034-16-793-43', '0341679343'], ['(034) 16.793.43', '0341679343'],
    ['+261 34 16 793 43', '0341679343'], ['261341679343', '0341679343'],
    ['00261 34 16 793 43', '0341679343'], ['+261 (0)34 16 793 43', '0341679343'],
    ['0381415216', '0381415216'], ['+261 38 14 152 16', '0381415216'],
    ['0202223344', '0202223344'], ['', ''],
  ])('normalizes %s without changing its digits', (raw, expected) => {
    expect(normalizePhoneNumber(raw)).toBe(expected)
    if (expected) expect(isValidPhoneNumber(raw)).toBe(true)
  })

  it('uses the same current example in placeholders and validation messages', () => {
    expect(PHONE_NUMBER_EXAMPLE).toBe('0381415216')
    expect(PHONE_NUMBER_ERROR).toContain(PHONE_NUMBER_EXAMPLE)
    expect(isValidPhoneNumber(PHONE_NUMBER_EXAMPLE)).toBe(true)
  })

  it.each(['', '034', '03416793431', '341679343', '034167934a', '+999341679343', '0341679343/0320000000', '0341679343 poste 2'])('rejects %s without truncating or dropping significant characters', (value) => {
    expect(isValidPhoneNumber(value)).toBe(false)
    expect(normalizePhoneNumber(value)).not.toBe('0341679343')
  })

  it.each(['MVola', 'Orange Money', 'Airtel Money', ''])('treats %s as a telephone destination', (operator) => {
    expect(isPhonePayoutOperator(operator)).toBe(true)
    expect(normalizePayoutNumber('+261 34 16 793 43', operator)).toBe('0341679343')
  })

  it.each(['Virement bancaire', 'Virement', 'Banque', 'Bank', 'IBAN', 'RIB', 'SWIFT'])('preserves %s identifiers', (operator) => {
    expect(isPhonePayoutOperator(operator)).toBe(false)
    expect(normalizePayoutNumber(' FR76 1234 5678 ', operator)).toBe('FR76 1234 5678')
  })
})
