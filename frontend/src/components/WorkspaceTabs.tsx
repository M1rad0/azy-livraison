import { ClipboardList, PackageCheck, Users } from 'lucide-react'

export type WorkspaceTab = 'plan' | 'deliveries' | 'couriers'

interface WorkspaceTabsProps {
  activeTab: WorkspaceTab
  deliveryMeta: string
  deliveryLabel?: string
  courierCount: number
  errorCounts?: Partial<Record<WorkspaceTab, number>>
  onChange: (tab: WorkspaceTab) => void
}

const tabs = [
  { id: 'plan', label: 'Plan', description: 'Informations générales', icon: ClipboardList },
  { id: 'deliveries', label: 'Livraisons', description: '', icon: PackageCheck },
  { id: 'couriers', label: 'Livreurs', description: '', icon: Users },
] as const

const packageFieldLabels: Record<string, string> = {
  id: 'référence',
  label: 'libellé',
  weightKg: 'poids',
  phone: 'téléphone',
  destination: 'destination',
  destinationQuery: 'destination',
  latitude: 'latitude',
  longitude: 'longitude',
  deliveryWindow: 'créneau de livraison',
  'deliveryWindow.startAt': 'début du créneau',
  'deliveryWindow.endAt': 'fin du créneau',
}

const courierFieldLabels: Record<string, string> = {
  id: 'référence',
  name: 'nom',
  vehicleType: 'véhicule',
  maxWeightKg: 'capacité',
  averageSpeedKmh: 'vitesse',
  departureAt: 'heure de départ',
  departureLocation: 'lieu de départ',
  'departureLocation.label': 'libellé du départ',
  'departureLocation.destinationQuery': 'adresse de départ',
  'departureLocation.latitude': 'latitude du départ',
  'departureLocation.longitude': 'longitude du départ',
}

function withField(base: string, field: string | undefined, labels: Record<string, string>) {
  return field ? `${base} · ${labels[field] ?? 'information à vérifier'}` : base
}

function packageField(field: string | undefined) {
  return field?.replace(/^destination\./, '')
}

export function validationFieldLabel(path: string) {
  const multiPackageMatch = path.match(/^clients\[(\d+)]\.deliveryGroups\[(\d+)]\.packages\[(\d+)](?:\.(.+))?$/)
  if (multiPackageMatch) {
    const base = `Retrait ${Number(multiPackageMatch[1]) + 1} · colis ${Number(multiPackageMatch[3]) + 1}`
    return withField(base, packageField(multiPackageMatch[4]), packageFieldLabels)
  }

  const multiGroupMatch = path.match(/^clients\[(\d+)]\.deliveryGroups\[(\d+)](?:\.(.+))?$/)
  if (multiGroupMatch) {
    const groupFieldLabels: Record<string, string> = {
      id: 'référence',
      name: 'libellé',
      pickup: 'point de récupération',
      'pickup.label': 'libellé du lieu',
      'pickup.destinationQuery': 'adresse du retrait',
      'pickup.latitude': 'latitude du retrait',
      'pickup.longitude': 'longitude du retrait',
      packages: 'liste des colis',
    }
    return withField(`Retrait ${Number(multiGroupMatch[1]) + 1}`, multiGroupMatch[3], groupFieldLabels)
  }

  const clientMatch = path.match(/^clients\[(\d+)](?:\.(.+))?$/)
  if (clientMatch) {
    const clientFieldLabels: Record<string, string> = {
      id: 'référence',
      name: 'client',
      deliveryGroups: 'retrait',
    }
    return withField(`Retrait ${Number(clientMatch[1]) + 1}`, clientMatch[2], clientFieldLabels)
  }

  const packageMatch = path.match(/^packages\[(\d+)](?:\.(.+))?$/)
  if (packageMatch) {
    return withField(`Colis ${Number(packageMatch[1]) + 1}`, packageField(packageMatch[2]), packageFieldLabels)
  }

  const courierMatch = path.match(/^couriers\[(\d+)](?:\.(.+))?$/)
  if (courierMatch) {
    return withField(`Livreur ${Number(courierMatch[1]) + 1}`, courierMatch[2], courierFieldLabels)
  }

  if (path.startsWith('depot')) return 'Point de récupération'
  const labels: Record<string, string> = {
    planId: 'Référence',
    name: 'Nom de la préparation',
    departureAt: 'Date et heure de départ',
    couriers: 'Livreurs',
    packages: 'Colis',
    clients: 'Retraits',
    request: 'Fichier de sauvegarde',
    schemaVersion: 'Fichier de sauvegarde',
    pattern: 'Mode de livraison',
    timezone: 'Heure de départ',
  }
  return labels[path] ?? 'Information à vérifier'
}

export function validationPathTab(path: string): WorkspaceTab {
  if (path.startsWith('couriers')) return 'couriers'
  if (path.startsWith('depot') || path.startsWith('packages') || path.startsWith('clients')) return 'deliveries'
  return 'plan'
}

export function WorkspaceTabs({ activeTab, deliveryMeta, deliveryLabel = 'Livraisons', courierCount, errorCounts, onChange }: WorkspaceTabsProps) {
  return (
    <nav className="workspace-tabs" role="tablist" aria-label="Sections du plan">
      {tabs.map(({ id, label, description, icon: Icon }) => {
        const visibleLabel = id === 'deliveries' ? deliveryLabel : label
        const errorCount = errorCounts?.[id] ?? 0
        const detail = id === 'deliveries'
          ? deliveryMeta
          : id === 'couriers'
            ? `${courierCount} livreur${courierCount > 1 ? 's' : ''}`
            : description
        return (
          <button
            key={id}
            type="button"
            role="tab"
            aria-label={`${visibleLabel}${errorCount > 0 ? ` · ${errorCount} erreur${errorCount > 1 ? 's' : ''}` : ''}`}
            aria-selected={activeTab === id}
            className={activeTab === id ? 'is-active' : ''}
            onClick={() => onChange(id)}
          >
            <span className="workspace-tabs__icon"><Icon aria-hidden="true" /></span>
            <span className="workspace-tabs__content"><strong>{visibleLabel}</strong><small>{detail}</small></span>
            <b className={`workspace-tabs__errors${errorCount === 0 ? ' is-empty' : ''}`} aria-hidden="true">{errorCount}</b>
          </button>
        )
      })}
    </nav>
  )
}
