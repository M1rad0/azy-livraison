import { useEffect, useId, useRef, useState, type ChangeEvent, type ReactNode } from 'react'
import {
  Clock3,
  ClipboardList,
  Download,
  LoaderCircle,
  MapPin,
  MapPinned,
  PackagePlus,
  Plus,
  ShieldCheck,
  Sparkles,
  Trash2,
  Upload,
  UserPlus,
  Users,
} from 'lucide-react'
import {
  CsvImportFailure,
  downloadCsvTemplate,
  importCsv,
  type Courier,
  type DeliveryPackage,
  type ImportKind,
  type Location,
} from '../api/csvImports'
import {
  isDeliveryPlan,
  materializeVehicleDefaults,
  PlanValidationFailure,
  validatePlan,
  type DeliveryPlan,
  type PlanningValidationError,
} from '../api/plans'
import { optimizePlan, type OptimizationResult } from '../api/optimization'
import { PlanMapPreview } from './PlanMapPreview'
import { LocationPicker } from './LocationPicker'
import { OptimizationResults } from './OptimizationResults'
import { WorkspaceDraftBar } from './WorkspaceDraftBar'
import { WorkspaceIntro } from './WorkspaceIntro'
import { WorkspaceTabActionBar } from './WorkspaceTabActionBar'
import { validationPathTab, WorkspaceTabs, type WorkspaceTab } from './WorkspaceTabs'

interface PlanningWorkspaceProps {
  accessToken: string
}

function antananarivoDateTime(date = new Date()) {
  const parts = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Indian/Antananarivo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
  return parts.replace(' ', 'T')
}

function toOffsetDateTime(value: string) {
  return value ? `${value}:00+03:00` : undefined
}

function defaultWindowEnd(departureAt: string) {
  return `${antananarivoDateTime(new Date(new Date(departureAt).getTime() + 2 * 60 * 60 * 1000))}:00+03:00`
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

function validationFieldLabel(path: string) {
  const packageMatch = path.match(/^packages\[(\d+)](?:\.(.+))?/)
  if (packageMatch) {
    const field = packageMatch[2]?.replace('destination.', '')
    const labels: Record<string, string> = {
      id: 'référence', label: 'libellé', weightKg: 'poids', phone: 'téléphone',
      destination: 'destination', destinationQuery: 'destination', latitude: 'latitude', longitude: 'longitude',
      deliveryWindow: 'créneau de livraison', 'deliveryWindow.startAt': 'début du créneau', 'deliveryWindow.endAt': 'fin du créneau',
    }
    return `Colis ${Number(packageMatch[1]) + 1}${field ? ` · ${labels[field] ?? field}` : ''}`
  }
  const courierMatch = path.match(/^couriers\[(\d+)](?:\.(.+))?/)
  if (courierMatch) {
    const labels: Record<string, string> = {
      id: 'référence', name: 'nom', vehicleType: 'véhicule', maxWeightKg: 'capacité', averageSpeedKmh: 'vitesse',
      departureAt: 'heure de départ', departureLocation: 'lieu de départ',
    }
    const field = courierMatch[2]
    return `Livreur ${Number(courierMatch[1]) + 1}${field ? ` · ${labels[field] ?? field}` : ''}`
  }
  if (path.startsWith('depot')) return 'Point de récupération'
  const labels: Record<string, string> = {
    planId: 'Référence', name: 'Nom de la préparation', departureAt: 'Date et heure de départ',
    couriers: 'Livreurs', packages: 'Colis', request: 'Fichier de sauvegarde',
    schemaVersion: 'Fichier de sauvegarde', pattern: 'Mode de livraison', timezone: 'Heure de départ',
  }
  return labels[path] ?? 'Préparation'
}

function InputField({ label, children, wide = false }: {
  label: string
  children: ReactNode
  wide?: boolean
}) {
  return <label className={`workspace-field${wide ? ' workspace-field--wide' : ''}`}><span>{label}</span>{children}</label>
}

export function CsvImportButton<T>({ accessToken, kind, onImported }: {
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
        aria-label={kind === 'packages' ? 'Liste de colis à importer' : 'Liste de livreurs à importer'}
        accept=".csv,text/csv"
        onChange={(event) => void handleFile(event)}
      />
      <label className="button button--secondary" htmlFor={inputId}><Upload aria-hidden="true" />{busy ? 'Import en cours…' : 'Importer une liste'}</label>
      <button className="text-button" type="button" onClick={() => void handleTemplate()}><Download aria-hidden="true" />Télécharger le modèle</button>
      {message && <span role="alert">{message}</span>}
    </div>
  )
}

export function PlanningWorkspace({ accessToken }: PlanningWorkspaceProps) {
  const jsonInputId = useId()
  const [plan, setPlan] = useState<DeliveryPlan>(defaultPlan)
  const [validationErrors, setValidationErrors] = useState<PlanningValidationError[]>([])
  const [validationMessage, setValidationMessage] = useState<string>()
  const [validating, setValidating] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [optimizationResult, setOptimizationResult] = useState<OptimizationResult>()
  const [fileMessage, setFileMessage] = useState<string>()
  const [mapPreviewOpen, setMapPreviewOpen] = useState(false)
  const [activeTab, setActiveTab] = useState<WorkspaceTab>('plan')
  const scrollPanelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (scrollPanelRef.current) scrollPanelRef.current.scrollTop = 0
  }, [activeTab])

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
    setFileMessage('Sauvegarde téléchargée.')
  }

  async function importJson(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setFileMessage(undefined)
    try {
      const parsed: unknown = JSON.parse(await file.text())
      if (!isDeliveryPlan(parsed) || parsed.pattern !== 'SINGLE_PICKUP_MULTI_DELIVERY') {
        throw new Error('Cette sauvegarde ne correspond pas au mode un retrait, plusieurs livraisons.')
      }
      replacePlan(materializeVehicleDefaults(parsed))
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
      if (response.plan?.pattern === 'SINGLE_PICKUP_MULTI_DELIVERY') replacePlan(response.plan)
      setValidationMessage('Le plan est valide et prêt pour la génération du résultat.')
    } catch (error) {
      if (error instanceof PlanValidationFailure) {
        setValidationErrors(error.errors)
        if (error.errors[0]) setActiveTab(validationPathTab(error.errors[0].path))
        setValidationMessage(error.message)
      } else {
        setValidationMessage(error instanceof Error ? error.message : 'Validation impossible.')
      }
    } finally {
      setValidating(false)
    }
  }

  async function handleGeneration() {
    setGenerating(true)
    setValidationErrors([])
    setValidationMessage(undefined)
    try {
      const validation = await validatePlan(plan, accessToken)
      const normalizedPlan = validation.plan?.pattern === 'SINGLE_PICKUP_MULTI_DELIVERY' ? validation.plan : plan
      setPlan(normalizedPlan)
      setOptimizationResult(await optimizePlan(normalizedPlan, accessToken))
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } catch (error) {
      if (error instanceof PlanValidationFailure) {
        setValidationErrors(error.errors)
        if (error.errors[0]) setActiveTab(validationPathTab(error.errors[0].path))
        setValidationMessage(error.message)
      } else {
        setValidationMessage(error instanceof Error ? error.message : 'Génération impossible.')
      }
    } finally {
      setGenerating(false)
    }
  }

  const totalWeight = plan.packages.reduce((sum, item) => sum + (Number(item.weightKg) || 0), 0)
  const tabErrorCounts = validationErrors.reduce<Partial<Record<WorkspaceTab, number>>>((counts, error) => {
    const tab = validationPathTab(error.path)
    counts[tab] = (counts[tab] ?? 0) + 1
    return counts
  }, {})

  if (optimizationResult) {
    return (
      <OptimizationResults
        result={optimizationResult}
        plan={plan}
        accessToken={accessToken}
        onBack={() => {
          setOptimizationResult(undefined)
          window.scrollTo({ top: 0, behavior: 'smooth' })
        }}
      />
    )
  }

  return (
    <div className="planning-workspace">
      <WorkspaceIntro
        titleId="workspace-title"
        eyebrow="Plan · Un retrait vers plusieurs livraisons"
        title="Construisez votre tournée."
        description="Chaque saisie et chaque import enrichissent le même brouillon partageable."
      />

      <WorkspaceDraftBar inputId={jsonInputId} message={fileMessage} onImport={importJson} onExport={exportJson} />

      <WorkspaceTabs
        activeTab={activeTab}
        deliveryMeta={`${plan.packages.length} colis`}
        courierCount={plan.couriers.length}
        errorCounts={tabErrorCounts}
        onChange={setActiveTab}
      />

      {validationMessage && validationErrors.length > 0 && activeTab !== 'couriers' && (
        <div className="workspace-feedback" role="alert">
          <strong>{validationMessage}</strong>
          <ul className="validation-errors">
            {validationErrors.map((error, index) => <li key={`${error.path}-${error.code}-${index}`}><strong>{validationFieldLabel(error.path)}</strong>{error.message}</li>)}
          </ul>
        </div>
      )}

      <div className="planning-layout">
        <section className="workspace-panel">
          <WorkspaceTabActionBar
            title={activeTab === 'plan' ? 'Informations générales' : activeTab === 'deliveries' ? 'Point de récupération et colis' : 'Équipe de livraison'}
            description={activeTab === 'plan' ? 'Identité et horaire du plan' : activeTab === 'deliveries' ? `${plan.packages.length} colis dans le brouillon` : `${plan.couriers.length} livreur${plan.couriers.length > 1 ? 's' : ''}`}
          >
            {activeTab === 'deliveries' ? (
              <>
                <CsvImportButton<DeliveryPackage> accessToken={accessToken} kind="packages" onImported={(items, filename) => {
                  updatePlan((current) => ({ ...current, packages: items }))
                  setFileMessage(`${filename} a remplacé la liste des colis.`)
                }} />
                <button className="button" type="button" onClick={addPackage}><Plus aria-hidden="true" />Ajouter un colis</button>
              </>
            ) : activeTab === 'couriers' ? (
              <>
                <CsvImportButton<Courier> accessToken={accessToken} kind="couriers" onImported={(items, filename) => {
                  updatePlan((current) => ({ ...current, couriers: items }))
                  setFileMessage(`${filename} a remplacé la liste des livreurs.`)
                }} />
                <button className="button" type="button" onClick={addCourier}><UserPlus aria-hidden="true" />Ajouter un livreur</button>
              </>
            ) : undefined}
          </WorkspaceTabActionBar>

          <div ref={scrollPanelRef} className="planning-form workspace-panel__scroll" role="tabpanel" aria-label={activeTab === 'plan' ? 'Plan' : activeTab === 'deliveries' ? 'Livraisons' : 'Livreurs'}>
          {activeTab === 'plan' && <section className="workspace-section" aria-labelledby="plan-general-title">
            <div className="workspace-section__heading">
              <div><span><ClipboardList aria-hidden="true" /></span><div><p className="eyebrow">Brouillon</p><h2 id="plan-general-title">Informations générales</h2></div></div>
            </div>
            <div className="workspace-fields">
              <InputField label="Référence">
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
          </section>}

          {activeTab === 'deliveries' && <section className="workspace-section" aria-labelledby="depot-title">
            <div className="workspace-section__heading">
              <div><span><MapPin aria-hidden="true" /></span><div><p className="eyebrow">Origine commune</p><h2 id="depot-title">Point de récupération</h2></div></div>
              <span className="section-badge">1 point</span>
            </div>
            <div className="workspace-fields">
              <InputField label="Libellé">
                <input value={plan.depot.label ?? ''} onChange={(event) => updateDepot({ label: event.target.value })} />
              </InputField>
            </div>
            <LocationPicker
              accessToken={accessToken}
              value={plan.depot}
              title="Point de récupération"
              description="Recherchez le lieu commun de récupération, puis confirmez-le sur la carte."
              onChange={(location) => updateDepot(location)}
            />
          </section>}

          {activeTab === 'deliveries' && <section className="workspace-section" aria-labelledby="packages-title">
            <div className="workspace-section__heading">
              <div><span><PackagePlus aria-hidden="true" /></span><div><p className="eyebrow">Destinations</p><h2 id="packages-title">Colis</h2></div></div>
              <span className="section-badge">{plan.packages.length} colis</span>
            </div>
            {plan.packages.length === 0 ? (
              <button className="workspace-empty" type="button" onClick={addPackage}>
                <span><PackagePlus aria-hidden="true" /></span><strong>Ajouter votre premier colis</strong><small>ou importez une liste déjà préparée</small>
              </button>
            ) : (
              <div className="editable-list">
                {plan.packages.map((item, index) => (
                  <article className="editable-card" key={`${item.id}-${index}`}>
                    <div className="editable-card__topline">
                      <strong>Colis {String(index + 1).padStart(2, '0')}</strong>
                      <button type="button" onClick={() => updatePlan((current) => ({ ...current, packages: current.packages.filter((_, itemIndex) => itemIndex !== index) }))}><Trash2 aria-hidden="true" />Supprimer</button>
                    </div>
                    <div className="editable-card__grid editable-card__grid--package">
                      <InputField label="Identifiant"><input value={item.id} onChange={(event) => updatePackage(index, { id: event.target.value })} /></InputField>
                      <InputField label="Libellé"><input value={item.label} onChange={(event) => updatePackage(index, { label: event.target.value })} /></InputField>
                      <InputField label="Poids (kg)"><input type="number" min="0.01" step="0.01" value={item.weightKg} onChange={(event) => updatePackage(index, { weightKg: Number(event.target.value) })} /></InputField>
                      <InputField label="Téléphone"><input value={item.phone ?? ''} onChange={(event) => updatePackage(index, { phone: event.target.value })} /></InputField>
                      <div className="editable-card__full-field">
                        <span>Destination</span>
                        <LocationPicker
                          accessToken={accessToken}
                          value={item.destination}
                          title={`Destination du colis ${index + 1}`}
                          description="Recherchez l’adresse du destinataire ou placez approximativement le point sur la carte."
                          onChange={(location) => updatePackageDestination(index, location)}
                        />
                      </div>
                      <div className="editable-card__optional">
                        <div className="editable-card__optional-heading">
                          <div><Clock3 aria-hidden="true" /><span><strong>Créneau de livraison</strong><small>Facultatif · utilisé pour optimiser l’ordre des arrêts</small></span></div>
                          {item.deliveryWindow ? (
                            <button type="button" onClick={() => updatePackage(index, { deliveryWindow: undefined })}><Trash2 aria-hidden="true" />Retirer</button>
                          ) : (
                            <button type="button" onClick={() => updatePackage(index, {
                              deliveryWindow: { startAt: plan.departureAt, endAt: defaultWindowEnd(plan.departureAt) },
                            })}><Plus aria-hidden="true" />Ajouter un créneau</button>
                          )}
                        </div>
                        {item.deliveryWindow && (
                          <div className="optional-fields">
                            <InputField label="Livrer à partir de"><input type="datetime-local" value={item.deliveryWindow.startAt.slice(0, 16)} onChange={(event) => updatePackage(index, { deliveryWindow: { ...item.deliveryWindow!, startAt: toOffsetDateTime(event.target.value) ?? '' } })} /></InputField>
                            <InputField label="Livrer avant"><input type="datetime-local" value={item.deliveryWindow.endAt.slice(0, 16)} onChange={(event) => updatePackage(index, { deliveryWindow: { ...item.deliveryWindow!, endAt: toOffsetDateTime(event.target.value) ?? '' } })} /></InputField>
                          </div>
                        )}
                      </div>
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>}

          {activeTab === 'couriers' && <section className="workspace-section" aria-labelledby="couriers-title">
            <div className="workspace-section__heading">
              <div><span><Users aria-hidden="true" /></span><div><p className="eyebrow">Équipe</p><h2 id="couriers-title">Livreurs</h2></div></div>
              <span className="section-badge">{plan.couriers.length} livreur{plan.couriers.length > 1 ? 's' : ''}</span>
            </div>
            {plan.couriers.length === 0 ? (
              <button className="workspace-empty" type="button" onClick={addCourier}>
                <span><UserPlus aria-hidden="true" /></span><strong>Ajouter votre premier livreur</strong><small>les valeurs recommandées restent modifiables</small>
              </button>
            ) : (
              <div className="editable-list">
                {plan.couriers.map((item, index) => (
                  <article className="editable-card" key={`${item.id}-${index}`}>
                    <div className="editable-card__topline">
                      <strong>Livreur {String(index + 1).padStart(2, '0')}</strong>
                      <button type="button" onClick={() => updatePlan((current) => ({ ...current, couriers: current.couriers.filter((_, itemIndex) => itemIndex !== index) }))}><Trash2 aria-hidden="true" />Supprimer</button>
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
                      <div className="editable-card__optional">
                        <div className="editable-card__optional-heading">
                          <div><MapPin aria-hidden="true" /><span><strong>Départ personnalisé</strong><small>Facultatif · sinon le livreur part du retrait à l’heure du plan</small></span></div>
                          {item.departureAt || item.departureLocation ? (
                            <button type="button" onClick={() => updateCourier(index, { departureAt: undefined, departureLocation: undefined })}><Trash2 aria-hidden="true" />Retirer</button>
                          ) : (
                            <button type="button" onClick={() => updateCourier(index, { departureAt: plan.departureAt, departureLocation: { ...plan.depot } })}><Plus aria-hidden="true" />Personnaliser</button>
                          )}
                        </div>
                        {(item.departureAt || item.departureLocation) && (
                          <div className="courier-departure-fields">
                            <InputField label="Heure de départ"><input type="datetime-local" value={item.departureAt?.slice(0, 16) ?? ''} onChange={(event) => updateCourier(index, { departureAt: toOffsetDateTime(event.target.value) })} /></InputField>
                            <div className="editable-card__full-field">
                              <span>Lieu de départ</span>
                              <LocationPicker
                                accessToken={accessToken}
                                value={item.departureLocation ?? {}}
                                title={`Départ de ${item.name || `livreur ${index + 1}`}`}
                                description="Choisissez son point de départ réel. Sans personnalisation, le point de récupération commun sera utilisé."
                                onChange={(departureLocation) => updateCourier(index, { departureLocation })}
                              />
                            </div>
                          </div>
                        )}
                      </div>
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>}
          </div>
        </section>
        <aside className="plan-sidebar plan-sidebar--scroll">
          {activeTab !== 'couriers' ? (
            <div className="plan-sidebar__card plan-overview"><p className="eyebrow">Plan en cours</p><h2>{plan.name || 'Sans titre'}</h2><dl className="plan-summary"><div><dt>Colis</dt><dd>{plan.packages.length}</dd></div><div><dt>Livreurs</dt><dd>{plan.couriers.length}</dd></div><div><dt>Poids total</dt><dd>{totalWeight.toLocaleString('fr-MG', { maximumFractionDigits: 2 })} kg</dd></div><div><dt>Retour dépôt</dt><dd>{plan.settings.returnToDepot ? 'Oui' : 'Non'}</dd></div></dl></div>
          ) : (
            <div className="plan-sidebar__card plan-validation"><p className="eyebrow">Finaliser le plan</p><h3>Prêt à préparer les tournées ?</h3><p>Vérifiez les trajets, puis lancez la répartition entre les livreurs.</p><button className="button button--secondary button--wide button--map-preview" type="button" onClick={() => setMapPreviewOpen(true)}><MapPinned aria-hidden="true" />Visualiser les trajets</button><button className="button button--wide" type="button" disabled={validating} onClick={() => void handleValidation()}><ShieldCheck aria-hidden="true" />{validating ? 'Validation…' : 'Valider le plan'}</button><button className="button button--wide button--generate" type="button" disabled={generating || validating} onClick={() => void handleGeneration()}>{generating ? <LoaderCircle className="spin" aria-hidden="true" /> : <Sparkles aria-hidden="true" />}{generating ? 'Optimisation en cours…' : 'Générer les tournées'}</button>{validationMessage && <p className={`validation-result${validationErrors.length ? ' validation-result--error' : ' validation-result--success'}`} role={validationErrors.length ? 'alert' : 'status'}>{validationMessage}</p>}{validationErrors.length > 0 && <ul className="validation-errors">{validationErrors.map((error, index) => <li key={`${error.path}-${error.code}-${index}`}><strong>{validationFieldLabel(error.path)}</strong>{error.message}</li>)}</ul>}</div>
          )}
        </aside>
      </div>
      {mapPreviewOpen && <PlanMapPreview plan={plan} accessToken={accessToken} onClose={() => setMapPreviewOpen(false)} />}
    </div>
  )
}
