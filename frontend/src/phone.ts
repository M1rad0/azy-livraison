export const PHONE_NUMBER_PATTERN = '0[0-9]{9}'
export const PHONE_NUMBER_EXAMPLE = '0381415216'
export const PHONE_NUMBER_ERROR = `Indiquez un numéro de 10 chiffres commençant par 0, par exemple ${PHONE_NUMBER_EXAMPLE}.`

/** Presentation/input normalization only: never truncate or silently remove invalid characters. */
export function normalizePhoneNumber(value: string): string {
  const compact = value.trim().replace(/[\s().\-–—]/g, '')
  const international = /^(?:\+261|00261|261)(.*)$/.exec(compact)
  if (!international) return compact
  const national = international[1]
  return national.startsWith('0') ? national : `0${national}`
}

export function isValidPhoneNumber(value: string): boolean {
  return /^0[0-9]{9}$/.test(normalizePhoneNumber(value))
}

/** Bank account identifiers and transaction references are not telephone numbers. */
export function isPhonePayoutOperator(operator: string): boolean {
  return !/(?:banc|banq|bank|virement|iban|rib|swift)/i.test(operator)
}

export function normalizePayoutNumber(number: string, operator: string): string {
  return isPhonePayoutOperator(operator) ? normalizePhoneNumber(number) : number.trim()
}
