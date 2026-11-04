import { useEffect, useId, useRef, useState } from 'react'
import { Download, FileUp, LoaderCircle, X } from 'lucide-react'
import {
  CLIENT_PACKAGE_CSV_TEMPLATE,
  ClientPackageCsvFailure,
  MAX_CLIENT_PACKAGE_CSV_BYTES,
  MAX_CLIENT_PACKAGES,
  parseClientPackageCsv,
  prepareClientPackageCsv,
  type ClientPackageCsvError,
  type ClientPackageCsvImportOptions,
} from '../clientPackageCsv'
import type { ClientPackageDraft } from './ClientPackageDialog'

export function ClientPackageCsvImport({
  accessToken, requestKind, clientAccountId, quotePrice, packageCount, onImported, onBusyChange,
}: Omit<ClientPackageCsvImportOptions, 'signal' | 'onProgress'> & {
  packageCount: number
  onImported: (packages: ClientPackageDraft[]) => void
  onBusyChange: (busy: boolean) => void
}) {
  const inputId = useId()
  const [open, setOpen] = useState(false)
  const [file, setFile] = useState<File>()
  const [errors, setErrors] = useState<ClientPackageCsvError[]>([])
  const [progress, setProgress] = useState<{ completed: number; total: number }>()
  const [busy, setBusy] = useState(false)
  const controller = useRef<AbortController | undefined>(undefined)
  useEffect(() => () => controller.current?.abort(), [])

  function downloadTemplate() {
    const url = URL.createObjectURL(new Blob(['\uFEFF', CLIENT_PACKAGE_CSV_TEMPLATE], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'azy-colis-modele.csv'
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  }

  async function importFile() {
    if (!file) return
    setErrors([])
    setProgress(undefined)
    const abort = new AbortController()
    controller.current = abort
    setBusy(true)
    onBusyChange(true)
    try {
      if (!file.name.toLowerCase().endsWith('.csv')) throw new Error('Choisissez un fichier avec l’extension .csv.')
      if (file.size > MAX_CLIENT_PACKAGE_CSV_BYTES) throw new Error('Le fichier ne doit pas dépasser 10 Mo.')
      const bytes = await file.arrayBuffer()
      abort.signal.throwIfAborted()
      const content = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
      const count = parseClientPackageCsv(content, requestKind).length
      if (packageCount + count > MAX_CLIENT_PACKAGES) throw new Error(`Une demande est limitée à ${MAX_CLIENT_PACKAGES} colis. Retirez ${packageCount + count - MAX_CLIENT_PACKAGES} colis avant cet import.`)
      setProgress({ completed: 0, total: count })
      const packages = await prepareClientPackageCsv(content, {
        accessToken, requestKind, clientAccountId, quotePrice, signal: abort.signal,
        onProgress: (completed, total) => setProgress({ completed, total }),
      })
      if (abort.signal.aborted) return
      onImported(packages)
      setFile(undefined)
      setOpen(false)
    } catch (error) {
      if (abort.signal.aborted) return
      setErrors(error instanceof ClientPackageCsvFailure ? error.errors : [{
        line: 0,
        message: error instanceof TypeError ? 'Enregistrez ce fichier au format CSV UTF-8 avant de l’importer.'
          : error instanceof Error ? error.message : 'Le fichier n’a pas pu être lu.',
      }])
    } finally {
      if (controller.current === abort) {
        controller.current = undefined
        setBusy(false)
        onBusyChange(false)
      }
    }
  }

  return (
    <div className="client-package-import">
      <button className="button button--secondary" type="button" aria-expanded={open} aria-controls={`${inputId}-panel`} onClick={() => setOpen(!open)} disabled={busy}>
        <FileUp aria-hidden="true" />Importer un fichier CSV
      </button>
      {open && <section className="client-package-import__panel" id={`${inputId}-panel`} aria-label="Import de colis">
        <div className="client-package-import__heading">
          <div><strong>Importer des colis</strong><p>Les lieux sont recherchés automatiquement. Vérifiez les premiers résultats retenus avant d’envoyer la demande.</p></div>
          <button className="button button--secondary" type="button" onClick={downloadTemplate}><Download aria-hidden="true" />Télécharger le modèle</button>
        </div>
        <label className="client-field" htmlFor={inputId}>
          <span>Fichier de colis · CSV UTF-8</span>
          <input id={inputId} type="file" accept=".csv,text/csv,application/vnd.ms-excel" disabled={busy} onChange={(event) => {
            setFile(event.target.files?.[0])
            setErrors([])
          }} />
        </label>
        <p className="client-package-import__help">Nom, contact, destination, libellé, montant à récupérer, Faritra, Mobile Money, poids et créneau. Le retrait et la date restent ceux de cette demande.</p>
        {errors.length > 0 && <div className="client-alert client-alert--error" role="alert"><div><strong>Import impossible</strong><ul>{errors.map((error, index) => <li key={index}>{error.line > 0 ? `Ligne ${error.line} · ` : ''}{error.field ? `${error.field} : ` : ''}{error.message}</li>)}</ul></div></div>}
        <div className="client-package-import__actions">
          {busy ? <>
            <p role="status"><LoaderCircle aria-hidden="true" />Recherche des lieux et tarifs{progress ? ` · ${progress.completed}/${progress.total}` : '…'}</p>
            <button className="button button--secondary" type="button" onClick={() => controller.current?.abort()}><X aria-hidden="true" />Annuler</button>
          </> : <button className="button" type="button" disabled={!file} onClick={() => void importFile()}><FileUp aria-hidden="true" />Ajouter les colis au formulaire</button>}
        </div>
      </section>}
    </div>
  )
}
