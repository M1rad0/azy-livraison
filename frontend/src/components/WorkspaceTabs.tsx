import { ClipboardList, PackageCheck, Users } from 'lucide-react'

export type WorkspaceTab = 'plan' | 'deliveries' | 'couriers'

interface WorkspaceTabsProps {
  activeTab: WorkspaceTab
  deliveryMeta: string
  courierCount: number
  errorCounts?: Partial<Record<WorkspaceTab, number>>
  onChange: (tab: WorkspaceTab) => void
}

const tabs = [
  { id: 'plan', label: 'Plan', description: 'Informations générales', icon: ClipboardList },
  { id: 'deliveries', label: 'Livraisons', description: '', icon: PackageCheck },
  { id: 'couriers', label: 'Livreurs', description: '', icon: Users },
] as const

export function validationPathTab(path: string): WorkspaceTab {
  if (path.startsWith('couriers')) return 'couriers'
  if (path.startsWith('depot') || path.startsWith('packages') || path.startsWith('clients')) return 'deliveries'
  return 'plan'
}

export function WorkspaceTabs({ activeTab, deliveryMeta, courierCount, errorCounts, onChange }: WorkspaceTabsProps) {
  return (
    <nav className="workspace-tabs" role="tablist" aria-label="Sections du plan">
      {tabs.map(({ id, label, description, icon: Icon }) => {
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
            aria-label={`${label}${errorCount > 0 ? ` · ${errorCount} erreur${errorCount > 1 ? 's' : ''}` : ''}`}
            aria-selected={activeTab === id}
            className={activeTab === id ? 'is-active' : ''}
            onClick={() => onChange(id)}
          >
            <span><Icon aria-hidden="true" /></span>
            <span><strong>{label}</strong><small>{detail}</small></span>
            {errorCount > 0 && <b className="workspace-tabs__errors">{errorCount}</b>}
          </button>
        )
      })}
    </nav>
  )
}
