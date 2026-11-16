export const PAYOUT_OPERATORS = ['MVola', 'Orange Money', 'Airtel Money'] as const

export const PAYOUT_OPERATOR_ERROR = 'Choisissez MVola, Orange Money ou Airtel Money pour chaque numéro de versement.'

/** Normalize supported names only; an unknown legacy value must be chosen explicitly. */
export function normalizePayoutOperator(value: string): string {
  const key = value.trim().toLocaleLowerCase('fr').replace(/[\s_-]+/g, '')
  if (key === 'mvola') return 'MVola'
  if (key === 'orangemoney') return 'Orange Money'
  if (key === 'airtelmoney') return 'Airtel Money'
  return ''
}
