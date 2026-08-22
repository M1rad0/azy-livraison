import type { ConfirmedLocation, DeliveryPackageStatus, DeliveryRequestStatus, DispatchStatus, VehicleType } from '../../api/admin'
import type { Location } from '../../api/csvImports'

export function antananarivoToday() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Indian/Antananarivo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date())
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${value.year}-${value.month}-${value.day}`
}

export function asPickerLocation(location?: ConfirmedLocation): Location {
  return location
    ? {
        label: location.label,
        destinationQuery: location.destinationQuery ?? location.query,
        latitude: location.latitude,
        longitude: location.longitude,
      }
    : {}
}

export function asConfirmedLocation(location: Location): ConfirmedLocation | undefined {
  if (!Number.isFinite(location.latitude) || !Number.isFinite(location.longitude)) return undefined
  return {
    label: location.label,
    destinationQuery: location.destinationQuery,
    latitude: location.latitude as number,
    longitude: location.longitude as number,
  }
}

export function locationLabel(location?: ConfirmedLocation | Location) {
  const query = location && 'query' in location ? location.query : undefined
  return location?.destinationQuery
    ?? query
    ?? location?.label
    ?? 'Lieu non renseigné'
}

export function formatDate(date: string) {
  return new Intl.DateTimeFormat('fr-FR', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date(`${date}T12:00:00+03:00`))
}

export function formatDateTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('fr-FR', {
    timeZone: 'Indian/Antananarivo',
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

export function formatTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('fr-FR', {
    timeZone: 'Indian/Antananarivo',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

export function formatAriary(value: number) {
  return `${new Intl.NumberFormat('fr-FR').format(value)} Ar`
}

export function requestStatusLabel(status: DeliveryRequestStatus) {
  return {
    PENDING: 'En attente',
    PARTIALLY_DISPATCHED: 'Partiellement dispatchée',
    DISPATCHED: 'Dispatchée',
  }[status]
}

export function packageStatusLabel(status: DeliveryPackageStatus) {
  return {
    PENDING: 'En attente',
    DISPATCHED: 'Dispatché',
    UNASSIGNED: 'Non affecté',
  }[status]
}

export function dispatchStatusLabel(status: DispatchStatus) {
  return {
    GENERATING: 'Calcul en cours',
    READY: 'Prêt',
    READY_WITH_WARNINGS: 'Prêt avec alertes',
    FAILED: 'Échec',
  }[status]
}

export function vehicleLabel(type: VehicleType) {
  return type === 'MOTO' ? 'Moto' : 'Vélo'
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Une erreur inattendue est survenue.'
}
