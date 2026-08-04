import { useEffect, useId, useState, type ChangeEvent, type ReactNode } from 'react'
import {
  CsvImportFailure,
  downloadCsvTemplate,
  importCsv,
  type Courier,
  type DeliveryPackage,
  type ImportKind,
  type Location,
} from '../api/csvImports'
import { searchAddress, type GeocodingCandidate } from '../api/geocoding'
import {
  isDeliveryPlan,
  PlanValidationFailure,
  validatePlan,
  type DeliveryPlan,
  type PlanningValidationError,
} from '../api/plans'

interface PlanningWorkspaceProps {
  accessToken: string
  onBack: () => void
}

function antananarivoDateTime() {
  const parts = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Indian/Antananarivo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date())
  return parts.replace(' ', 'T')
}

function defaultPlan(): DeliveryPlan {
  const localDeparture = antananarivoDateTime()
  return {
    schemaVersion: '1.0',
    pattern: 'SINGLE_PICKUP_MULTI_DELIVERY',
    planId: `plan-${localDeparture.slice(0, 10)}`,
    name: `Livraisons du ${localDeparture.slice(0, 10)}`,
    departureAt: `${localDeparture}:00+03:00`,
    timezone: 'Indian/Antananarivo',
    settings: { returnToDepot: false, routingPreset: 'MAIN_ROADS' },
    depot: {
      label: 'Point de récupération',
      destinationQuery: 'Analakely, Antananarivo',
    },
    couriers: [],
    packages: [],
  }
}

function nextId(prefix: string, items: { id: string }[]) {
  let index = items.length + 1
  let candidate = `${prefix}-${String(index).padStart(3, '0')}`
  while (items.some((item) => item.id === candidate)) {
    index += 1
    candidate = `${prefix}-${String(index).padStart(3, '0')}`
  }
  return candidate
}

function numberOrUndefined(value: string) {
  if (value.trim() === '') return undefined
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : undefined
}

function InputField({ label, children, wide = false }: {
  label: string
  children: ReactNode
  wide?: boolean
}) {
  return <label className={`workspace-field${wide ? ' workspace-field--wide' : ''}`}><span>{label}</span>{children}</label>
}

function DepotSearch({ accessToken, depot, onSelect }: {
  accessToken: string
  depot: Location
  onSelect: (candidate: GeocodingCandidate) => void
}) {
  const [query, setQuery] = useState(depot.destinationQuery ?? '')
  const [candidates, setCandidates] = useState<GeocodingCandidate[]>([])
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string>()

  useEffect(() => {
    setQuery(depot.destinationQuery ?? '')
  }, [depot.destinationQuery])

  async function search() {
    if (query.trim().length < 3) return
    setBusy(true)
    setMessage(undefined)
    setCandidates([])
    try {
      const result = await searchAddress(query, accessToken)
      setCandidates(result.candidates)
      if (result.candidates.length === 0) setMessage('Aucun lieu trouvé à Madagascar.')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Recherche impossible.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="depot-search">
      <div className="depot-search__bar">
        <input
          aria-label="Rechercher le point de récupération"
          value={query}
          minLength={3}
          placeholder="Rechercher une adresse ou un point de repère"
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              void search()
            }
          }}
        />
        <button className="button" type="button" disabled={busy || query.trim().length < 3} onClick={() => void search()}>
          {busy ? 'Recherche…' : 'Rechercher'}
        </button>
      </div>
      {message && <p className="inline-message inline-message--error" role="alert">{message}</p>}
      {candidates.length > 0 && (
        <ul className="workspace-candidates" aria-label="Résultats pour le point de récupération">
          {candidates.map((candidate) => (
            <li key={candidate.id}>
              <button type="button" onClick={() => {
                setQuery(candidate.displayName)
                setCandidates([])
                onSelect(candidate)
              }}>
                <strong>{candidate.displayName}</strong>
                <small>{candidate.latitude.toFixed(6)}, {candidate.longitude.toFixed(6)}</small>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function CsvImportButton<T>({ accessToken, kind, onImported }: {
  accessToken: string
  kind: ImportKind
  onImported: (items: T[], filename: string) => void
}) {
  const inputId = useId()
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string>()

  async function handleFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setBusy(true)
    setMessage(undefined)
    try {
      const result = await importCsv<T>(kind, file, accessToken)
      onImported(result.items, result.filename)
    } catch (error) {
      setMessage(error instanceof CsvImportFailure ? error.message : 'Import impossible.')
    } finally {
      setBusy(false)
    }
  }

  async function handleTemplate() {
    setMessage(undefined)
    try {
      await downloadCsvTemplate(kind, accessToken)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Téléchargement impossible.')
    }
  }

  return (
    <div className="workspace-import-action">
      <input
        id={inputId}
        type="file"
        aria-label={kind === 'packages' ? 'Fichier CSV colis' : 'Fichier CSV livreurs'}
        accept=".csv,text/csv"
        onChange={(event) => void handleFile(event)}
      />
      <label className="button button--secondary" htmlFor={inputId}>{busy ? 'Import…' : 'Importer CSV'}</label>
      <button className="text-button" type="button" onClick={() => void handleTemplate()}>Modèle</button>
      {message && <span role="alert">{message}</span>}
    </div>
  )
}

export function PlanningWorkspace({ accessToken, onBack }: PlanningWorkspaceProps) {
  const jsonInputId = useId()
  const [plan, setPlan] = useState<DeliveryPlan>(defaultPlan)
  const [validationErrors, setValidationErrors] = useState<PlanningValidationError[]>([])
  const [validationMessage, setValidationMessage] = useState<string>()
  const [validating, setValidating] = useState(false)
  const [fileMessage, setFileMessage] = useState<string>()

  function replacePlan(nextPlan: DeliveryPlan) {
    setPlan(nextPlan)
    setValidationErrors([])
    setValidationMessage(undefined)
  }

  function updatePlan(update: (current: DeliveryPlan) => DeliveryPlan) {
    setPlan((current) => update(current))
    setValidationErrors([])
    setValidationMessage(undefined)
  }

  function updateDepot(update: Partial<Location>) {
    updatePlan((current) => ({ ...current, depot: { ...current.depot, ...update } }))
  }

  function addPackage() {
    updatePlan((current) => ({
      ...current,
      packages: [...current.packages, {
        id: nextId('colis', current.packages),
        label: '',
        weightKg: 1,
        destination: { destinationQuery: '' },
      }],
    }))
  }

  function updatePackage(index: number, update: Partial<DeliveryPackage>) {
    updatePlan((current) => ({
      ...current,
      packages: current.packages.map((item, itemIndex) => itemIndex === index ? { ...item, ...update } : item),
    }))
  }

  function updatePackageDestination(index: number, update: Partial<Location>) {
    updatePlan((current) => ({
      ...current,
      packages: current.packages.map((item, itemIndex) => itemIndex === index
        ? { ...item, destination: { ...item.destination, ...update } }
        : item),
    }))
  }

  function addCourier() {
    updatePlan((current) => ({
      ...current,
      couriers: [...current.couriers, {
        id: nextId('livreur', current.couriers),
        name: '',
        vehicleType: 'MOTO',
        maxWeightKg: 30,
        averageSpeedKmh: 25,
      }],
    }))
  }

  function updateCourier(index: number, update: Partial<Courier>) {
    updatePlan((current) => ({
      ...current,
      couriers: current.couriers.map((item, itemIndex) => itemIndex === index ? { ...item, ...update } : item),
    }))
  }

  function exportJson() {
    const blob = new Blob([`${JSON.stringify(plan, null, 2)}\n`], { type: 'application/json;charset=utf-8' })
    const objectUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = `${plan.planId.trim() || 'plan-azy'}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(objectUrl)
    setFileMessage('Brouillon JSON exporté.')
  }

  async function importJson(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setFileMessage(undefined)
    try {
      const parsed: unknown = JSON.parse(await file.text())
      if (!isDeliveryPlan(parsed)) throw new Error('Ce fichier ne respecte pas le format de plan AZY 1.0.')
      replacePlan(parsed)
      setFileMessage(`${file.name} chargé : ${parsed.packages.length} colis et ${parsed.couriers.length} livreurs.`)
    } catch (error) {
      setFileMessage(error instanceof Error ? error.message : 'Lecture du fichier impossible.')
    }
  }

  async function handleValidation() {
    setValidating(true)
    setValidationErrors([])
    setValidationMessage(undefined)
    try {
      const response = await validatePlan(plan, accessToken)
      if (response.plan) replacePlan(response.plan)
      setValidationMessage('Le plan est valide et prêt pour la génération du résultat.')
    } catch (error) {
      if (error instanceof PlanValidationFailure) {
        setValidationErrors(error.errors)
        setValidationMessage(error.message)
      } else {
        setValidationMessage(error instanceof Error ? error.message : 'Validation impossible.')
      }
    } finally {
      setValidating(false)
    }
  }

  const totalWeight = plan.packages.reduce((sum, item) => sum + (Number(item.weightKg) || 0), 0)

  return (
    <div className="planning-workspace">
      <section className="workspace-hero" aria-labelledby="workspace-title">
        <button className="workspace-back" type="button" onClick={onBack}>← Changer de pattern</button>
        <div>
          <p className="eyebrow">Plan · Un retrait vers plusieurs livraisons</p>
          <h1 id="workspace-title">Construisez votre tournée.</h1>
          <p>Chaque saisie et chaque import enrichissent le même brouillon partageable.</p>
        </div>
        <div className="workspace-progress" aria-label="Étapes du plan">
          <span className="is-complete">1 <small>Pattern</small></span>
          <span className="is-active">2 <small>Préparation</small></span>
          <span>3 <small>Résultat</small></span>
        </div>
      </section>

      <div className="planning-layout">
        <div className="planning-form">
          <section className="workspace-section" aria-labelledby="plan-general-title">
            <div className="workspace-section__heading">
              <div><span>01</span><div><p className="eyebrow">Brouillon</p><h2 id="plan-general-title">Informations générales</h2></div></div>
            </div>
            <div className="workspace-fields">
              <InputField label="Identifiant du plan">
                <input value={plan.planId} onChange={(event) => updatePlan((current) => ({ ...current, planId: event.target.value }))} />
              </InputField>
              <InputField label="Nom du plan">
                <input value={plan.name} onChange={(event) => updatePlan((current) => ({ ...current, name: event.target.value }))} />
              </InputField>
              <InputField label="Départ · heure d’Antananarivo">
                <input type="datetime-local" value={plan.departureAt.slice(0, 16)} onChange={(event) => updatePlan((current) => ({ ...current, departureAt: `${event.target.value}:00+03:00` }))} />
              </InputField>
              <label className="workspace-toggle">
                <input type="checkbox" checked={plan.settings.returnToDepot} onChange={(event) => updatePlan((current) => ({ ...current, settings: { ...current.settings, returnToDepot: event.target.checked } }))} />
                <span><strong>Retour au point de récupération</strong><small>Ajouter le trajet retour à chaque tournée.</small></span>
              </label>
            </div>
          </section>

          <section className="workspace-section" aria-labelledby="depot-title">
            <div className="workspace-section__heading">
              <div><span>02</span><div><p className="eyebrow">Origine commune</p><h2 id="depot-title">Point de récupération</h2></div></div>
              <span className="section-badge">1 point</span>
            </div>
            <DepotSearch accessToken={accessToken} depot={plan.depot} onSelect={(candidate) => updateDepot({
              label: candidate.displayName,
              destinationQuery: candidate.displayName,
              latitude: candidate.latitude,
              longitude: candidate.longitude,
            })} />
            <div className="workspace-fields workspace-fields--location">
              <InputField label="Libellé">
                <input value={plan.depot.label ?? ''} onChange={(event) => updateDepot({ label: event.target.value })} />
              </InputField>
              <InputField label="Adresse ou description">
                <input value={plan.depot.destinationQuery ?? ''} onChange={(event) => updateDepot({ destinationQuery: event.target.value })} />
              </InputField>
              <InputField label="Latitude">
                <input type="number" step="any" value={plan.depot.latitude ?? ''} onChange={(event) => updateDepot({ latitude: numberOrUndefined(event.target.value) })} />
              </InputField>
              <InputField label="Longitude">
                <input type="number" step="any" value={plan.depot.longitude ?? ''} onChange={(event) => updateDepot({ longitude: numberOrUndefined(event.target.value) })} />
              </InputField>
            </div>
          </section>

          <section className="workspace-section" aria-labelledby="packages-title">
            <div className="workspace-section__heading workspace-section__heading--actions">
              <div><span>03</span><div><p className="eyebrow">Destinations</p><h2 id="packages-title">Colis</h2></div></div>
              <div className="workspace-section__actions">
                <CsvImportButton<DeliveryPackage> accessToken={accessToken} kind="packages" onImported={(items, filename) => {
                  updatePlan((current) => ({ ...current, packages: items }))
                  setFileMessage(`${filename} a remplacé la liste des colis.`)
                }} />
                <button className="button" type="button" onClick={addPackage}>+ Ajouter</button>
              </div>
            </div>
            {plan.packages.length === 0 ? (
              <button className="workspace-empty" type="button" onClick={addPackage}>
                <span>＋</span><strong>Ajouter votre premier colis</strong><small>ou utilisez l’import CSV pour remplir cette section</small>
              </button>
            ) : (
              <div className="editable-list">
                {plan.packages.map((item, index) => (
                  <article className="editable-card" key={`${item.id}-${index}`}>
                    <div className="editable-card__topline">
                      <strong>Colis {String(index + 1).padStart(2, '0')}</strong>
                      <button type="button" onClick={() => updatePlan((current) => ({ ...current, packages: current.packages.filter((_, itemIndex) => itemIndex !== index) }))}>Supprimer</button>
                    </div>
                    <div className="editable-card__grid editable-card__grid--package">
                      <InputField label="Identifiant"><input value={item.id} onChange={(event) => updatePackage(index, { id: event.target.value })} /></InputField>
                      <InputField label="Libellé"><input value={item.label} onChange={(event) => updatePackage(index, { label: event.target.value })} /></InputField>
                      <InputField label="Poids (kg)"><input type="number" min="0.01" step="0.01" value={item.weightKg} onChange={(event) => updatePackage(index, { weightKg: Number(event.target.value) })} /></InputField>
                      <InputField label="Téléphone"><input value={item.phone ?? ''} onChange={(event) => updatePackage(index, { phone: event.target.value })} /></InputField>
                      <InputField label="Destination" wide><input value={item.destination.destinationQuery ?? ''} placeholder="Adresse, quartier ou point de repère" onChange={(event) => updatePackageDestination(index, { destinationQuery: event.target.value })} /></InputField>
                      <InputField label="Latitude"><input type="number" step="any" value={item.destination.latitude ?? ''} onChange={(event) => updatePackageDestination(index, { latitude: numberOrUndefined(event.target.value) })} /></InputField>
                      <InputField label="Longitude"><input type="number" step="any" value={item.destination.longitude ?? ''} onChange={(event) => updatePackageDestination(index, { longitude: numberOrUndefined(event.target.value) })} /></InputField>
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>

          <section className="workspace-section" aria-labelledby="couriers-title">
            <div className="workspace-section__heading workspace-section__heading--actions">
              <div><span>04</span><div><p className="eyebrow">Équipe</p><h2 id="couriers-title">Livreurs</h2></div></div>
              <div className="workspace-section__actions">
                <CsvImportButton<Courier> accessToken={accessToken} kind="couriers" onImported={(items, filename) => {
                  updatePlan((current) => ({ ...current, couriers: items }))
                  setFileMessage(`${filename} a remplacé la liste des livreurs.`)
                }} />
                <button className="button" type="button" onClick={addCourier}>+ Ajouter</button>
              </div>
            </div>
            {plan.couriers.length === 0 ? (
              <button className="workspace-empty" type="button" onClick={addCourier}>
                <span>＋</span><strong>Ajouter votre premier livreur</strong><small>les préréglages véhicule restent modifiables</small>
              </button>
            ) : (
              <div className="editable-list">
                {plan.couriers.map((item, index) => (
                  <article className="editable-card" key={`${item.id}-${index}`}>
                    <div className="editable-card__topline">
                      <strong>Livreur {String(index + 1).padStart(2, '0')}</strong>
                      <button type="button" onClick={() => updatePlan((current) => ({ ...current, couriers: current.couriers.filter((_, itemIndex) => itemIndex !== index) }))}>Supprimer</button>
                    </div>
                    <div className="editable-card__grid">
                      <InputField label="Identifiant"><input value={item.id} onChange={(event) => updateCourier(index, { id: event.target.value })} /></InputField>
                      <InputField label="Nom"><input value={item.name} onChange={(event) => updateCourier(index, { name: event.target.value })} /></InputField>
                      <InputField label="Véhicule">
                        <select value={item.vehicleType} onChange={(event) => {
                          const vehicleType = event.target.value as Courier['vehicleType']
                          updateCourier(index, vehicleType === 'MOTO'
                            ? { vehicleType, maxWeightKg: 30, averageSpeedKmh: 25 }
                            : { vehicleType, maxWeightKg: 10, averageSpeedKmh: 12 })
                        }}><option value="MOTO">Moto</option><option value="BICYCLE">Bicyclette</option></select>
                      </InputField>
                      <InputField label="Capacité (kg)"><input type="number" min="0.01" step="0.01" value={item.maxWeightKg} onChange={(event) => updateCourier(index, { maxWeightKg: Number(event.target.value) })} /></InputField>
                      <InputField label="Vitesse (km/h)"><input type="number" min="0.01" step="0.01" value={item.averageSpeedKmh} onChange={(event) => updateCourier(index, { averageSpeedKmh: Number(event.target.value) })} /></InputField>
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>
        </div>

        <aside className="plan-sidebar">
          <div className="plan-sidebar__card">
            <p className="eyebrow">Plan en cours</p>
            <h2>{plan.name || 'Sans titre'}</h2>
            <dl className="plan-summary">
              <div><dt>Colis</dt><dd>{plan.packages.length}</dd></div>
              <div><dt>Livreurs</dt><dd>{plan.couriers.length}</dd></div>
              <div><dt>Poids total</dt><dd>{totalWeight.toLocaleString('fr-MG', { maximumFractionDigits: 2 })} kg</dd></div>
              <div><dt>Retour dépôt</dt><dd>{plan.settings.returnToDepot ? 'Oui' : 'Non'}</dd></div>
            </dl>
          </div>

          <div className="plan-sidebar__card plan-files">
            <div><p className="eyebrow">Sauvegarde portable</p><h3>Brouillon JSON</h3></div>
            <p>Exportez maintenant et reprenez ce plan plus tard, sur une autre installation si nécessaire.</p>
            <input id={jsonInputId} type="file" accept=".json,application/json" onChange={(event) => void importJson(event)} />
            <label className="button button--secondary" htmlFor={jsonInputId}>Ouvrir un brouillon</label>
            <button className="button button--secondary" type="button" onClick={exportJson}>Exporter le brouillon</button>
            {fileMessage && <span className="inline-message" role="status">{fileMessage}</span>}
          </div>

          <div className="plan-sidebar__card plan-validation">
            <p className="eyebrow">Contrôle métier</p>
            <h3>Prêt à continuer ?</h3>
            <p>Le backend vérifie le format, les identifiants, les coordonnées et applique les préréglages.</p>
            <button className="button button--wide" type="button" disabled={validating} onClick={() => void handleValidation()}>
              {validating ? 'Validation…' : 'Valider le plan'}
            </button>
            {validationMessage && (
              <p className={`validation-result${validationErrors.length ? ' validation-result--error' : ' validation-result--success'}`} role={validationErrors.length ? 'alert' : 'status'}>
                {validationMessage}
              </p>
            )}
            {validationErrors.length > 0 && (
              <ul className="validation-errors">
                {validationErrors.map((error, index) => <li key={`${error.path}-${error.code}-${index}`}><strong>{error.path}</strong>{error.message}</li>)}
              </ul>
            )}
          </div>
        </aside>
      </div>
    </div>
  )
}
