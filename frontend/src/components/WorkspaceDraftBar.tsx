import type { ChangeEvent } from 'react'
import { FileClock, FolderOpen, Save } from 'lucide-react'

interface WorkspaceDraftBarProps {
  inputId: string
  message?: string
  onImport: (event: ChangeEvent<HTMLInputElement>) => void | Promise<void>
  onExport: () => void
}

export function WorkspaceDraftBar({ inputId, message, onImport, onExport }: WorkspaceDraftBarProps) {
  return (
    <section className="workspace-draftbar" aria-label="Fichier de travail">
      <div className="workspace-draftbar__identity">
        <span><FileClock aria-hidden="true" /></span>
        <div>
          <p className="eyebrow">Fichier de travail</p>
          <strong>Reprendre ou partager ce brouillon</strong>
        </div>
      </div>

      <div className="workspace-draftbar__actions">
        <input id={inputId} type="file" accept=".json,application/json" onChange={(event) => void onImport(event)} />
        <label className="button button--secondary" htmlFor={inputId}><FolderOpen aria-hidden="true" />Ouvrir une sauvegarde</label>
        <button className="button button--secondary" type="button" onClick={onExport}><Save aria-hidden="true" />Télécharger la sauvegarde</button>
      </div>

      {message && <span className="workspace-draftbar__message inline-message" role="status">{message}</span>}
    </section>
  )
}
