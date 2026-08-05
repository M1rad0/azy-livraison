import { ChevronDown } from 'lucide-react'

interface WorkspaceIntroProps {
  description: string
  eyebrow: string
  title: string
  titleId: string
}

export function WorkspaceIntro({ description, eyebrow, title, titleId }: WorkspaceIntroProps) {
  return (
    <details className="workspace-hero">
      <summary>
        <div>
          <p className="eyebrow">{eyebrow}</p>
          <h1 id={titleId}>{title}</h1>
        </div>
        <span className="workspace-hero__toggle">
          <span className="workspace-hero__toggle-closed">Afficher le contexte</span>
          <span className="workspace-hero__toggle-open">Réduire</span>
          <ChevronDown aria-hidden="true" />
        </span>
      </summary>
      <div className="workspace-hero__details">
        <p>{description}</p>
        <div className="workspace-progress" aria-label="Progression">
          <span className="is-complete">1 <small>Mode</small></span>
          <span className="is-active">2 <small>Préparation</small></span>
          <span>3 <small>Résultat</small></span>
        </div>
      </div>
    </details>
  )
}
