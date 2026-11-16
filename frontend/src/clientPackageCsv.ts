import {
  quoteDeliveryPrice,
  type ClientLocation,
  type DeliveryPricingOptions,
  type DeliveryPricingQuote,
  type DeliveryRequestKind,
  type DeliveryTimePreference,
} from './api/client'
import { searchAddress } from './api/geocoding'
import { packageWindowInput, type ClientPackageDraft } from './components/ClientPackageDialog'
import { endOfOneHourWindow } from './deliveryWindows'
import { isValidPhoneNumber, normalizePhoneNumber, PHONE_NUMBER_ERROR } from './phone'

export const CLIENT_PACKAGE_CSV_COLUMNS = [
  'recipient_name', 'destination_label', 'destination_query', 'latitude', 'longitude',
  'contact', 'amount_to_collect_ariary', 'faritra', 'mobile_money_paid', 'weight_kg',
  'desired_delivery_time', 'desired_delivery_end_time', 'delivery_time_preference',
] as const

export const CLIENT_PACKAGE_CSV_TEMPLATE = `${CLIENT_PACKAGE_CSV_COLUMNS.join(';')}\nMiora;Bureau Ankorondrano;Ankorondrano, Antananarivo;;;0340000000;25000;non;non;1,5;14:00;15:30;CUSTOM\nRakoto;Boutique Analakely;;-18.9089;47.5212;0320000000;15000;non;oui;;;;NONE\n`
export const MAX_CLIENT_PACKAGES = 500
export const MAX_CLIENT_PACKAGE_CSV_BYTES = 10 * 1024 * 1024

export interface ClientPackageCsvError {
  line: number
  field?: string
  message: string
}

export class ClientPackageCsvFailure extends Error {
  constructor(public readonly errors: ClientPackageCsvError[]) {
    super(errors[0]?.message ?? 'Le fichier ne peut pas être importé.')
  }
}

interface CsvRow {
  line: number
  cells: string[]
}

interface ParsedPackage {
  line: number
  value: Omit<ClientPackageDraft, 'localId'>
}

/** Read quoted CSV cells, including escaped quotes and embedded line breaks. */
function readCsv(content: string): CsvRow[] {
  const text = content.replace(/^\uFEFF/, '')
  const firstLine = text.split(/\r?\n/).find((line) => line.trim()) ?? ''
  const delimiter = firstLine.includes(';') ? ';' : ','
  const rows: CsvRow[] = []
  let cells: string[] = []
  let cell = ''
  let quoted = false
  let closedQuote = false
  let line = 1
  let rowLine = 1
  function finishCell() {
    cells.push(cell.trim())
    cell = ''
    closedQuote = false
  }
  function finishRow() {
    finishCell()
    if (cells.some((value) => value !== '')) rows.push({ line: rowLine, cells })
    cells = []
  }
  for (let index = 0; index < text.length; index++) {
    const character = text[index]
    if (quoted) {
      if (character === '"') {
        if (text[index + 1] === '"') { cell += '"'; index++ }
        else { quoted = false; closedQuote = true }
      } else {
        if (character === '\n') line++
        cell += character
      }
    } else if (character === delimiter) {
      finishCell()
    } else if (character === '\n' || character === '\r') {
      if (character === '\r' && text[index + 1] === '\n') index++
      finishRow()
      line++
      rowLine = line
    } else if (character === '"' && !cell.trim() && !closedQuote) {
      cell = ''
      quoted = true
    } else if ((closedQuote && character.trim()) || character === '"') {
      throw new ClientPackageCsvFailure([{ line, message: 'Guillemets mal placés dans le fichier CSV.' }])
    } else if (!closedQuote) {
      cell += character
    }
  }
  if (quoted) throw new ClientPackageCsvFailure([{ line: rowLine, message: 'Une cellule entre guillemets n’est pas fermée.' }])
  finishRow()
  return rows
}

export function parseClientPackageCsv(content: string, requestKind: DeliveryRequestKind = 'NORMAL'): ParsedPackage[] {
  if (content.includes('\0')) throw new ClientPackageCsvFailure([{ line: 1, message: 'Ce fichier contient des données non textuelles.' }])
  const rows = readCsv(content)
  if (rows.length < 2) throw new ClientPackageCsvFailure([{ line: 1, message: 'Ajoutez au moins une ligne de colis sous les en-têtes.' }])
  const headers = rows[0].cells.map((header) => header.toLowerCase())
  const errors: ClientPackageCsvError[] = []
  for (const header of headers) {
    if (!CLIENT_PACKAGE_CSV_COLUMNS.includes(header as typeof CLIENT_PACKAGE_CSV_COLUMNS[number])) {
      errors.push({ line: 1, field: header, message: 'Colonne inconnue. Utilisez les en-têtes du modèle.' })
    } else if (headers.indexOf(header) !== headers.lastIndexOf(header)) {
      errors.push({ line: 1, field: header, message: 'Cette colonne est présente plusieurs fois.' })
    }
  }
  for (const field of ['recipient_name', 'contact', 'amount_to_collect_ariary']) {
    if (!headers.includes(field)) errors.push({ line: 1, field, message: 'Cette colonne est nécessaire.' })
  }
  if (!headers.includes('destination_query') && !(headers.includes('latitude') && headers.includes('longitude'))) {
    errors.push({ line: 1, field: 'destination_query', message: 'Ajoutez une destination textuelle ou les colonnes latitude et longitude.' })
  }
  if (rows.length - 1 > MAX_CLIENT_PACKAGES) errors.push({ line: 1, message: `Un import est limité à ${MAX_CLIENT_PACKAGES} colis.` })
  if (errors.length) throw new ClientPackageCsvFailure(errors)

  const expressCurrentTime = new Intl.DateTimeFormat('fr-FR', {
    timeZone: 'Indian/Antananarivo', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(new Date())

  const packages = rows.slice(1).map((row): ParsedPackage => {
    const field = (name: string) => row.cells[headers.indexOf(name)] ?? ''
    const error = (name: string, message: string) => errors.push({ line: row.line, field: name, message })
    if (row.cells.length !== headers.length) error('colonnes', 'Le nombre de cellules ne correspond pas aux en-têtes.')
    const text = (name: string, max: number, required = false) => {
      const value = field(name)
      if (required && !value) error(name, 'Cette valeur est nécessaire.')
      if (value.length > max) error(name, `Limitez cette valeur à ${max} caractères.`)
      return value
    }
    const numeric = (name: string) => {
      const value = field(name).replace(/[\s\u00a0]/g, '').replace(',', '.')
      if (!value) return undefined
      const number = /^[-+]?\d+(?:\.\d+)?$/.test(value) ? Number(value) : NaN
      if (!Number.isFinite(number)) error(name, 'Utilisez un nombre valide.')
      return number
    }
    const boolean = (name: string) => {
      const value = field(name).toLowerCase()
      if (['', 'non', 'no', 'false', '0'].includes(value)) return false
      if (['oui', 'yes', 'true', '1'].includes(value)) return true
      error(name, 'Utilisez oui/non, true/false ou 1/0.')
      return false
    }
    const recipientName = text('recipient_name', 200, true)
    const contact = normalizePhoneNumber(text('contact', 100, true))
    if (contact && !isValidPhoneNumber(contact)) error('contact', PHONE_NUMBER_ERROR)
    const destinationQuery = text('destination_query', 500)
    const destinationLabel = text('destination_label', 200) || destinationQuery.split(',')[0].slice(0, 200)
    const latitude = numeric('latitude')
    const longitude = numeric('longitude')
    if ((latitude === undefined) !== (longitude === undefined)) error('latitude/longitude', 'Fournissez les deux coordonnées ensemble.')
    if (latitude !== undefined && (latitude < -90 || latitude > 90)) error('latitude', 'La latitude doit être comprise entre -90 et 90.')
    if (longitude !== undefined && (longitude < -180 || longitude > 180)) error('longitude', 'La longitude doit être comprise entre -180 et 180.')
    if (!destinationQuery && latitude === undefined && longitude === undefined) error('destination_query', 'Indiquez un lieu à rechercher ou deux coordonnées.')
    const amount = numeric('amount_to_collect_ariary')
    if (amount === undefined || !Number.isSafeInteger(amount) || amount < 0) error('amount_to_collect_ariary', 'Le montant à récupérer doit être un entier positif ou nul.')
    const weight = numeric('weight_kg')
    if (weight !== undefined && (!(weight > 0) || weight > 9_999_999.999)) error('weight_kg', 'Le poids doit être supérieur à zéro et ne pas dépasser 9 999 999,999 kg.')
    const parseTime = (name: string) => {
      const time = field(name)
      if (!time) return ''
      const match = /^(\d{1,2}):([0-5]\d)(?::00)?$/.exec(time)
      if (!match || Number(match[1]) > 23) { error(name, 'Utilisez une heure au format HH:mm, par exemple 14:00.'); return '' }
      return `${match[1].padStart(2, '0')}:${match[2]}`
    }
    let desiredDeliveryTime = parseTime('desired_delivery_time')
    let desiredDeliveryEndTime = parseTime('desired_delivery_end_time')
    const rawPreference = field('delivery_time_preference').toUpperCase()
    const preferences: Record<string, DeliveryTimePreference> = { NONE: 'NONE', CUSTOM: 'CUSTOM', MORNING: 'MORNING', MATIN: 'MORNING', AFTERNOON: 'AFTERNOON', SOIR: 'AFTERNOON' }
    const inferredPreference = field('desired_delivery_time') || field('desired_delivery_end_time') ? 'CUSTOM' : 'NONE'
    const deliveryTimePreference = rawPreference ? preferences[rawPreference] : inferredPreference
    if (rawPreference && !deliveryTimePreference) error('delivery_time_preference', 'Utilisez NONE, CUSTOM, MORNING (MATIN) ou AFTERNOON (SOIR).')
    if (deliveryTimePreference === 'CUSTOM') {
      if (!field('desired_delivery_time')) error('desired_delivery_time', 'Indiquez le début du créneau personnalisé.')
      if (!field('desired_delivery_end_time')) {
        if (rawPreference === 'CUSTOM') error('desired_delivery_end_time', 'Indiquez la fin du créneau personnalisé.')
        else if (desiredDeliveryTime) desiredDeliveryEndTime = endOfOneHourWindow(desiredDeliveryTime)
      }
      if (desiredDeliveryTime && desiredDeliveryEndTime === desiredDeliveryTime) error('desired_delivery_end_time', 'Le début et la fin du créneau doivent être différents. Une fin plus tôt correspond au lendemain.')
      if (requestKind === 'EXPRESS' && desiredDeliveryTime && desiredDeliveryTime <= expressCurrentTime) error('desired_delivery_time', 'L’heure souhaitée doit être postérieure à maintenant pour un express.')
    } else if (deliveryTimePreference) {
      if (field('desired_delivery_time') || field('desired_delivery_end_time')) error('delivery_time_preference', 'Choisissez une période ou un créneau personnalisé, sans les combiner. Le mode NONE ne comporte pas d’heures.')
      desiredDeliveryTime = ''; desiredDeliveryEndTime = ''
      if (requestKind === 'EXPRESS' && deliveryTimePreference === 'MORNING' && expressCurrentTime >= '12:00') error('delivery_time_preference', 'Le choix Matin est indisponible à partir de midi pour un express.')
    }
    return {
      line: row.line,
      value: {
        recipientName,
        contact,
        destination: {
          label: destinationLabel || recipientName,
          ...(destinationQuery ? { destinationQuery } : {}),
          ...(latitude !== undefined && longitude !== undefined ? { latitude, longitude } : {}),
        },
        amountToCollectAriary: String(amount ?? 0),
        weightKg: weight === undefined ? '' : String(weight),
        faritra: boolean('faritra'),
        mobileMoneyPaid: boolean('mobile_money_paid'),
        desiredDeliveryTime,
        desiredDeliveryEndTime,
        deliveryTimePreference,
      },
    }
  })
  if (errors.length) throw new ClientPackageCsvFailure(errors)
  return packages
}

export interface ClientPackageCsvImportOptions {
  accessToken: string
  requestKind: DeliveryRequestKind
  clientAccountId?: string
  signal: AbortSignal
  quotePrice?: (
    destination: ClientLocation,
    signal: AbortSignal,
    options: DeliveryPricingOptions & { requestKind: DeliveryRequestKind },
  ) => Promise<DeliveryPricingQuote>
  onProgress?: (completed: number, total: number) => void
}

export async function prepareClientPackageCsv(content: string, options: ClientPackageCsvImportOptions): Promise<ClientPackageDraft[]> {
  // Validate every row before issuing any address or tariff lookup.
  const parsed = parseClientPackageCsv(content, options.requestKind)
  const packages: ClientPackageDraft[] = []
  const destinations = new Map<string, Awaited<ReturnType<typeof searchAddress>>['candidates'][number] | null>()
  for (const row of parsed) {
    options.signal.throwIfAborted()
    const value: ClientPackageDraft = {
      ...row.value,
      localId: globalThis.crypto?.randomUUID?.() ?? `package-${Date.now()}-${Math.random()}`,
    }
    try {
      if (value.destination.latitude === undefined || value.destination.longitude === undefined) {
        const query = value.destination.destinationQuery as string
        if (!destinations.has(query)) {
          const result = await searchAddress(query, options.accessToken, options.signal, {
            purpose: 'DESTINATION', requestKind: options.requestKind, clientAccountId: options.clientAccountId,
          })
          destinations.set(query, result.candidates[0] ?? null)
        }
        const first = destinations.get(query)
        if (!first) throw new Error('Aucun lieu trouvé dans la zone desservie. Choisissez le lieu sur la carte.')
        value.destination = { ...value.destination, destinationQuery: first.displayName, latitude: first.latitude, longitude: first.longitude }
        value.importResolvedPlace = first.displayName
      }
      const destination = {
        label: value.destination.label as string,
        query: value.destination.destinationQuery,
        latitude: value.destination.latitude as number,
        longitude: value.destination.longitude as number,
      }
      const pricingOptions = {
        requestKind: options.requestKind,
        ...(value.weightKg ? { weightKg: Number(value.weightKg) } : {}),
        ...packageWindowInput(value),
      }
      value.pricingQuote = options.quotePrice
        ? await options.quotePrice(destination, options.signal, pricingOptions)
        : await quoteDeliveryPrice(destination, options.accessToken, options.signal, pricingOptions)
    } catch (error) {
      if (options.signal.aborted) throw error
      value.importWarning = error instanceof Error ? error.message : 'Vérifiez le lieu et le tarif de ce colis.'
    }
    packages.push(value)
    options.onProgress?.(packages.length, parsed.length)
  }
  return packages
}
