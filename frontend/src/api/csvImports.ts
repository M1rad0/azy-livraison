const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

export type ImportKind = 'packages' | 'couriers'

export interface Location {
  label?: string
  destinationQuery?: string
  latitude?: number
  longitude?: number
}

export interface DeliveryPackage {
  id: string
  label: string
  weightKg: number
  phone?: string
  destination: Location
  deliveryWindow?: {
    startAt: string
    endAt: string
  }
}

export interface Courier {
  id: string
  name: string
  vehicleType: 'MOTO' | 'BICYCLE'
  maxWeightKg: number
  averageSpeedKmh: number
  departureAt?: string
  departureLocation?: Location
}

export interface CsvRowError {
  line?: number
  field: string
  code: string
  message: string
}

export interface CsvImportResponse<T> {
  valid: boolean
  importType: 'PACKAGES' | 'COURIERS'
  filename: string
  delimiter?: string
  importedCount: number
  items: T[]
  errors: CsvRowError[]
}

export class CsvImportFailure extends Error {
  constructor(
    message: string,
    public readonly errors: CsvRowError[],
    public readonly status: number,
  ) {
    super(message)
  }
}

export async function importCsv<T>(
  kind: ImportKind,
  file: File,
  accessToken: string,
): Promise<CsvImportResponse<T>> {
  const formData = new FormData()
  formData.append('file', file)

  const response = await fetch(`${API_BASE_URL}/import/${kind}/csv`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
    },
    body: formData,
  })
  const payload = (await response.json().catch(() => undefined)) as CsvImportResponse<T> | undefined

  if (!response.ok || !payload?.valid) {
    throw new CsvImportFailure(
      payload?.errors?.[0]?.message ?? `L'import a échoué avec le statut ${response.status}.`,
      payload?.errors ?? [],
      response.status,
    )
  }

  return payload
}

export async function downloadCsvTemplate(kind: ImportKind, accessToken: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/import/${kind}/template`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  if (!response.ok) {
    throw new CsvImportFailure('Le modèle n’a pas pu être téléchargé.', [], response.status)
  }

  const blob = await response.blob()
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = `${kind}-template.csv`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(objectUrl)
}
