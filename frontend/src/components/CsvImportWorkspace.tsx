import { useId, useState, type ReactNode } from 'react'
import {
  CsvImportFailure,
  downloadCsvTemplate,
  importCsv,
  type Courier,
  type CsvImportResponse,
  type CsvRowError,
  type DeliveryPackage,
  type ImportKind,
} from '../api/csvImports'

interface ImportCardProps<T> {
  accessToken: string
  kind: ImportKind
  title: string
  description: string
  inputLabel: string
  columns: string[]
  renderCells: (item: T) => ReactNode
}

function ImportCard<T>({
  accessToken,
  kind,
  title,
  description,
  inputLabel,
  columns,
  renderCells,
}: ImportCardProps<T>) {
  const inputId = useId()
  const [file, setFile] = useState<File>()
  const [result, setResult] = useState<CsvImportResponse<T>>()
  const [errors, setErrors] = useState<CsvRowError[]>([])
  const [message, setMessage] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function handleImport() {
    if (!file) return
    setBusy(true)
    setResult(undefined)
    setErrors([])
    setMessage(undefined)

    try {
      setResult(await importCsv<T>(kind, file, accessToken))
    } catch (error) {
      if (error instanceof CsvImportFailure) {
        setErrors(error.errors)
        setMessage(error.message)
      } else {
        setMessage('Le fichier n’a pas pu être importé.')
      }
    } finally {
      setBusy(false)
    }
  }

  async function handleTemplateDownload() {
    setMessage(undefined)
    try {
      await downloadCsvTemplate(kind, accessToken)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Téléchargement impossible.')
    }
  }

  return (
    <article className="import-card">
      <div className="import-card__heading">
        <div>
          <h3>{title}</h3>
          <p>{description}</p>
        </div>
        <button className="text-button" type="button" onClick={() => void handleTemplateDownload()}>
          Télécharger le modèle
        </button>
      </div>

      <label className="file-picker" htmlFor={inputId}>
        <span>{inputLabel}</span>
        <input
          id={inputId}
          type="file"
          aria-label={inputLabel}
          accept=".csv,text/csv,application/vnd.ms-excel"
          onChange={(event) => {
            setFile(event.target.files?.[0])
            setResult(undefined)
            setErrors([])
            setMessage(undefined)
          }}
        />
        <strong>{file?.name ?? 'Choisir un fichier CSV'}</strong>
      </label>

      <button className="button button--wide" type="button" disabled={!file || busy} onClick={() => void handleImport()}>
        {busy ? 'Analyse…' : `Importer ${kind === 'packages' ? 'les colis' : 'les livreurs'}`}
      </button>

      {message && errors.length === 0 && <p className="form-error" role="alert">{message}</p>}

      {errors.length > 0 && (
        <div className="import-errors" role="alert">
          <strong>{errors.length} erreur{errors.length > 1 ? 's' : ''} à corriger</strong>
          <ul>
            {errors.map((error, index) => (
              <li key={`${error.line ?? 'file'}-${error.field}-${error.code}-${index}`}>
                {error.line ? `Ligne ${error.line} · ` : ''}{error.field} — {error.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      {result && (
        <div className="import-preview">
          <p className="import-success" role="status">
            <strong>{result.importedCount}</strong> ligne{result.importedCount > 1 ? 's' : ''} prête{result.importedCount > 1 ? 's' : ''}
            <span>{result.filename} · séparateur « {result.delimiter} »</span>
          </p>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>{columns.map((column) => <th key={column}>{column}</th>)}</tr>
              </thead>
              <tbody>
                {result.items.map((item, index) => <tr key={index}>{renderCells(item)}</tr>)}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </article>
  )
}

function packageDestination(item: DeliveryPackage) {
  if (item.destination.latitude != null && item.destination.longitude != null) {
    return `${item.destination.latitude}, ${item.destination.longitude}`
  }
  return item.destination.destinationQuery ?? '—'
}

export function CsvImportWorkspace({ accessToken }: { accessToken: string }) {
  return (
    <section className="imports" aria-labelledby="imports-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Préparer les données</p>
          <h2 id="imports-title">Imports CSV</h2>
        </div>
        <p>UTF-8 · point-virgule ou virgule · aucune écriture en base</p>
      </div>

      <div className="import-grid">
        <ImportCard<DeliveryPackage>
          accessToken={accessToken}
          kind="packages"
          title="Colis"
          description="Adresse textuelle ou coordonnées complètes pour chaque destination."
          inputLabel="Fichier colis"
          columns={['Identifiant', 'Libellé', 'Poids', 'Destination']}
          renderCells={(item) => (
            <>
              <td>{item.id}</td>
              <td>{item.label}</td>
              <td>{item.weightKg} kg</td>
              <td>{packageDestination(item)}</td>
            </>
          )}
        />

        <ImportCard<Courier>
          accessToken={accessToken}
          kind="couriers"
          title="Livreurs"
          description="Les capacités et vitesses vides utilisent le préréglage du véhicule."
          inputLabel="Fichier livreurs"
          columns={['Identifiant', 'Nom', 'Véhicule', 'Capacité', 'Vitesse']}
          renderCells={(item) => (
            <>
              <td>{item.id}</td>
              <td>{item.name}</td>
              <td>{item.vehicleType}</td>
              <td>{item.maxWeightKg} kg</td>
              <td>{item.averageSpeedKmh} km/h</td>
            </>
          )}
        />
      </div>
    </section>
  )
}
