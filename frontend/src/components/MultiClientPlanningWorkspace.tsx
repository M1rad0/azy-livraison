import { useEffect, useId, useRef, useState, type ChangeEvent, type ReactNode } from 'react'
import {
  Building2,
  ChevronDown,
  ClipboardList,
  Clock3,
  Eye,
  Layers3,
  LoaderCircle,
  MapPin,
  MapPinned,
  Pencil,
  PackagePlus,
  Plus,
  ShieldCheck,
  Sparkles,
  Trash2,
  UserPlus,
  Users,
  X,
} from 'lucide-react'
import type { Courier, DeliveryPackage, Location } from '../api/csvImports'
import {
  isDeliveryPlan,
  materializeVehicleDefaults,
  PlanValidationFailure,
  validatePlan,
  type DeliveryClient,
  type DeliveryGroup,
  type MultiClientDeliveryPlan,
  type PlanningValidationError,
} from '../api/plans'
import { optimizePlan, type OptimizationResult } from '../api/optimization'
import { LocationPicker } from './LocationPicker'
import { OptimizationResults } from './OptimizationResults'
import { PlanMapPreview } from './PlanMapPreview'
import { CsvImportButton } from './PlanningWorkspace'
import { WorkspaceDraftBar } from './WorkspaceDraftBar'
import { WorkspaceIntro } from './WorkspaceIntro'
import { WorkspaceTabActionBar } from './WorkspaceTabActionBar'
import { validationPathTab, WorkspaceTabs, type WorkspaceTab } from './WorkspaceTabs'

interface Props {
  accessToken: string
}

type EditorState =
  | { kind: 'package'; clientIndex: number; groupIndex: number; packageIndex: number }
  | { kind: 'summary' }

function antananarivoDateTime(date = new Date()) {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Indian/Antananarivo',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date).replace(' ', 'T')
}

function toOffsetDateTime(value: string) {
  return value ? `${value}:00+03:00` : undefined
}

function defaultWindowEnd(departureAt: string) {
  return `${antananarivoDateTime(new Date(new Date(departureAt).getTime() + 2 * 60 * 60 * 1000))}:00+03:00`
}

function nextId(prefix: string, existing: string[]) {
  let index = existing.length + 1
  let candidate = `${prefix}-${String(index).padStart(3, '0')}`
  while (existing.includes(candidate)) {
    index += 1
    candidate = `${prefix}-${String(index).padStart(3, '0')}`
  }
  return candidate
}

function defaultPlan(): MultiClientDeliveryPlan {
  const localDeparture = antananarivoDateTime()
  return {
    schemaVersion: '2.0',
    pattern: 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY',
    planId: `multi-${localDeparture.slice(0, 10)}`,
    name: `Livraisons multi-clients du ${localDeparture.slice(0, 10)}`,
    departureAt: `${localDeparture}:00+03:00`,
    timezone: 'Indian/Antananarivo',
    settings: { returnToDepot: false, routingPreset: 'MAIN_ROADS' },
    couriers: [],
    clients: [],
  }
}

function InputField({ label, children }: { label: string; children: ReactNode }) {
  return <label className="workspace-field"><span>{label}</span>{children}</label>
}

function locationLabel(location: Location) {
  if (location.label) return location.label
  if (location.destinationQuery) return location.destinationQuery
  if (Number.isFinite(location.latitude) && Number.isFinite(location.longitude)) {
    return `${location.latitude?.toFixed(5)}, ${location.longitude?.toFixed(5)}`
  }
  return 'Lieu à confirmer'
}

function deliveryWindowLabel(item: DeliveryPackage) {
  if (!item.deliveryWindow) return 'Sans créneau'
  const format = (value: string) => new Intl.DateTimeFormat('fr-MG', {
    timeZone: 'Indian/Antananarivo',
    day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
  return `${format(item.deliveryWindow.startAt)} – ${format(item.deliveryWindow.endAt)}`
}

export function MultiClientPlanningWorkspace({ accessToken }: Props) {
  const jsonInputId = useId()
  const [plan, setPlan] = useState<MultiClientDeliveryPlan>(defaultPlan)
  const [validationErrors, setValidationErrors] = useState<PlanningValidationError[]>([])
  const [message, setMessage] = useState<string>()
  const [fileMessage, setFileMessage] = useState<string>()
  const [validating, setValidating] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [mapPreviewOpen, setMapPreviewOpen] = useState(false)
  const [editor, setEditor] = useState<EditorState>()
  const [optimizationResult, setOptimizationResult] = useState<OptimizationResult>()
  const [activeTab, setActiveTab] = useState<WorkspaceTab>('plan')
  const [collapsedClients, setCollapsedClients] = useState<Set<number>>(() => new Set())
  const scrollPanelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (scrollPanelRef.current) scrollPanelRef.current.scrollTop = 0
  }, [activeTab])

  function toggleClient(clientIndex: number) {
    setCollapsedClients((current) => {
      const next = new Set(current)
      if (next.has(clientIndex)) next.delete(clientIndex)
      else next.add(clientIndex)
      return next
    })
  }

  function updatePlan(update: (current: MultiClientDeliveryPlan) => MultiClientDeliveryPlan) {
    setPlan((current) => update(current))
    setValidationErrors([])
    setMessage(undefined)
  }

  function addClient() {
    updatePlan((current) => {
      const id = nextId('client', current.clients.map((client) => client.id))
      return { ...current, clients: [...current.clients, { id, name: '', deliveryGroups: [] }] }
    })
  }

  function updateClient(clientIndex: number, update: Partial<DeliveryClient>) {
    updatePlan((current) => ({
      ...current,
      clients: current.clients.map((client, index) => index === clientIndex ? { ...client, ...update } : client),
    }))
  }

  function removeClient(clientIndex: number) {
    updatePlan((current) => ({ ...current, clients: current.clients.filter((_, index) => index !== clientIndex) }))
    setCollapsedClients(new Set())
  }

  function updateGroup(clientIndex: number, groupIndex: number, update: Partial<DeliveryGroup>) {
    updatePlan((current) => ({
      ...current,
      clients: current.clients.map((client, index) => index !== clientIndex ? client : {
        ...client,
        deliveryGroups: client.deliveryGroups.map((group, index) => index === groupIndex ? { ...group, ...update } : group),
      }),
    }))
  }

  function addGroup(clientIndex: number) {
    updatePlan((current) => {
      const groupIds = current.clients.flatMap((client) => client.deliveryGroups.map((group) => group.id))
      return {
        ...current,
        clients: current.clients.map((client, index) => index !== clientIndex ? client : {
          ...client,
          deliveryGroups: [...client.deliveryGroups, {
            id: nextId('course', groupIds),
            name: '',
            pickup: { destinationQuery: '' },
            packages: [],
          }],
        }),
      }
    })
  }

  function addPackage(clientIndex: number, groupIndex: number) {
    const packageIndex = plan.clients[clientIndex].deliveryGroups[groupIndex].packages.length
    updatePlan((current) => {
      const packageIds = current.clients.flatMap((client) => client.deliveryGroups.flatMap((group) => group.packages.map((item) => item.id)))
      return {
        ...current,
        clients: current.clients.map((client, index) => index !== clientIndex ? client : {
          ...client,
          deliveryGroups: client.deliveryGroups.map((group, index) => index !== groupIndex ? group : {
            ...group,
            packages: [...group.packages, {
              id: nextId('colis', packageIds),
              label: '',
              weightKg: 1,
              destination: { destinationQuery: '' },
            }],
          }),
        }),
      }
    })
    setEditor({ kind: 'package', clientIndex, groupIndex, packageIndex })
  }

  function updatePackage(clientIndex: number, groupIndex: number, packageIndex: number, update: Partial<DeliveryPackage>) {
    const group = plan.clients[clientIndex].deliveryGroups[groupIndex]
    updateGroup(clientIndex, groupIndex, {
      packages: group.packages.map((item, index) => index === packageIndex ? { ...item, ...update } : item),
    })
  }

  function updatePackageDestination(clientIndex: number, groupIndex: number, packageIndex: number, update: Partial<Location>) {
    const item = plan.clients[clientIndex].deliveryGroups[groupIndex].packages[packageIndex]
    updatePackage(clientIndex, groupIndex, packageIndex, { destination: { ...item.destination, ...update } })
  }

  function addCourier() {
    updatePlan((current) => ({
      ...current,
      couriers: [...current.couriers, {
        id: nextId('livreur', current.couriers.map((courier) => courier.id)),
        name: '', vehicleType: 'MOTO', maxWeightKg: 30, averageSpeedKmh: 25,
      }],
    }))
  }

  function updateCourier(index: number, update: Partial<Courier>) {
    updatePlan((current) => ({
      ...current,
      couriers: current.couriers.map((courier, courierIndex) => courierIndex === index ? { ...courier, ...update } : courier),
    }))
  }

  function exportJson() {
    const blob = new Blob([`${JSON.stringify(plan, null, 2)}\n`], { type: 'application/json;charset=utf-8' })
    const objectUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = `${plan.planId.trim() || 'plan-multi-azy'}.json`
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
    try {
      const parsed: unknown = JSON.parse(await file.text())
      if (!isDeliveryPlan(parsed) || parsed.pattern !== 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY') {
        throw new Error('Cette sauvegarde ne correspond pas au mode multi-clients.')
      }
      const loaded = materializeVehicleDefaults(parsed)
      setPlan(loaded)
      setCollapsedClients(new Set())
      setValidationErrors([])
      const packageCount = loaded.clients.reduce((sum, client) => sum
        + client.deliveryGroups.reduce((groupSum, group) => groupSum + group.packages.length, 0), 0)
      setFileMessage(`${file.name} chargé : ${loaded.clients.length} clients et ${packageCount} colis.`)
    } catch (error) {
      setFileMessage(error instanceof Error ? error.message : 'Lecture du fichier impossible.')
    }
  }

  async function handleValidation(generate: boolean) {
    generate ? setGenerating(true) : setValidating(true)
    setValidationErrors([])
    setMessage(undefined)
    try {
      const response = await validatePlan(plan, accessToken)
      if (!response.plan || response.plan.pattern !== 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY') return
      setPlan(response.plan)
      if (generate) {
        setOptimizationResult(await optimizePlan(response.plan, accessToken))
        window.scrollTo({ top: 0, behavior: 'smooth' })
      } else {
        setMessage('Le plan multi-clients est valide et prêt pour l’optimisation.')
      }
    } catch (error) {
      if (error instanceof PlanValidationFailure) {
        setValidationErrors(error.errors)
        if (error.errors[0]) setActiveTab(validationPathTab(error.errors[0].path))
        setMessage(error.message)
      } else {
        setMessage(error instanceof Error ? error.message : 'Opération impossible.')
      }
    } finally {
      setValidating(false)
      setGenerating(false)
    }
  }

  const groups = plan.clients.flatMap((client) => client.deliveryGroups)
  const packages = groups.flatMap((group) => group.packages)
  const totalWeight = packages.reduce((sum, item) => sum + (Number(item.weightKg) || 0), 0)
  const tabErrorCounts = validationErrors.reduce<Partial<Record<WorkspaceTab, number>>>((counts, error) => {
    const tab = validationPathTab(error.path)
    counts[tab] = (counts[tab] ?? 0) + 1
    return counts
  }, {})
  const editorClient = editor?.kind === 'package' ? plan.clients[editor.clientIndex] : undefined
  const editorGroup = editor?.kind === 'package'
    ? editorClient?.deliveryGroups[editor.groupIndex]
    : undefined
  const editorPackage = editor?.kind === 'package'
    ? editorGroup?.packages[editor.packageIndex]
    : undefined

  if (optimizationResult) {
    return <OptimizationResults result={optimizationResult} plan={plan} accessToken={accessToken} onBack={() => setOptimizationResult(undefined)} />
  }

  return (
    <div className="planning-workspace planning-workspace--multi">
      <WorkspaceIntro
        titleId="multi-workspace-title"
        eyebrow="Plan · Plusieurs clients et retraits"
        title="Rassemblez toutes les courses."
        description="Chaque client peut fournir plusieurs retraits. La même équipe de livreurs prend en charge l’ensemble."
      />

      <WorkspaceDraftBar inputId={jsonInputId} message={fileMessage} onImport={importJson} onExport={exportJson} />

      <WorkspaceTabs
        activeTab={activeTab}
        deliveryMeta={`${plan.clients.length} client${plan.clients.length > 1 ? 's' : ''} · ${packages.length} colis`}
        courierCount={plan.couriers.length}
        errorCounts={tabErrorCounts}
        onChange={setActiveTab}
      />

      {message && validationErrors.length > 0 && activeTab !== 'couriers' && (
        <div className="workspace-feedback" role="alert">
          <strong>{message}</strong>
          <ul className="validation-errors">
            {validationErrors.map((error, index) => <li key={`${error.path}-${error.code}-${index}`}><strong>{error.path}</strong>{error.message}</li>)}
          </ul>
        </div>
      )}

      <div className="planning-layout">
        <section className="workspace-panel">
          <WorkspaceTabActionBar
            title={activeTab === 'plan' ? 'Informations générales' : activeTab === 'deliveries' ? 'Clients et retraits' : 'Équipe partagée'}
            description={activeTab === 'plan' ? 'Identité et horaire du plan commun' : activeTab === 'deliveries' ? `${plan.clients.length} client${plan.clients.length > 1 ? 's' : ''} · ${groups.length} retrait${groups.length > 1 ? 's' : ''}` : `${plan.couriers.length} livreur${plan.couriers.length > 1 ? 's' : ''}`}
          >
            {activeTab === 'deliveries' ? (
              <>
                <button className="button button--secondary" type="button" onClick={() => setEditor({ kind: 'summary' })}><Eye />Résumé</button>
                <button className="button" type="button" onClick={addClient}><Plus />Ajouter un client</button>
              </>
            ) : activeTab === 'couriers' ? (
              <>
                <CsvImportButton<Courier> accessToken={accessToken} kind="couriers" onImported={(items, filename) => { updatePlan((current) => ({ ...current, couriers: items })); setFileMessage(`${filename} a remplacé la liste des livreurs.`) }} />
                <button className="button" type="button" onClick={addCourier}><UserPlus />Ajouter un livreur</button>
              </>
            ) : undefined}
          </WorkspaceTabActionBar>

          <div ref={scrollPanelRef} className="planning-form workspace-panel__scroll" role="tabpanel" aria-label={activeTab === 'plan' ? 'Plan' : activeTab === 'deliveries' ? 'Livraisons' : 'Livreurs'}>
          {activeTab === 'plan' && <section className="workspace-section">
            <div className="workspace-section__heading"><div><span><ClipboardList /></span><div><p className="eyebrow">Brouillon commun</p><h2>Informations générales</h2></div></div></div>
            <div className="workspace-fields">
              <InputField label="Référence"><input value={plan.planId} onChange={(event) => updatePlan((current) => ({ ...current, planId: event.target.value }))} /></InputField>
              <InputField label="Nom du plan"><input value={plan.name} onChange={(event) => updatePlan((current) => ({ ...current, name: event.target.value }))} /></InputField>
              <InputField label="Départ · heure d’Antananarivo"><input type="datetime-local" value={plan.departureAt.slice(0, 16)} onChange={(event) => updatePlan((current) => ({ ...current, departureAt: `${event.target.value}:00+03:00` }))} /></InputField>
            </div>
          </section>}

          {activeTab === 'deliveries' && <section className="workspace-section multi-clients-section">
            <div className="workspace-section__heading">
              <div><span><Building2 /></span><div><p className="eyebrow">Demandes à traiter</p><h2>Clients</h2></div></div>
              <span className="section-badge">{plan.clients.length} client{plan.clients.length > 1 ? 's' : ''}</span>
            </div>
            {plan.clients.length === 0 ? (
              <button className="workspace-empty" type="button" onClick={addClient}><span><Building2 /></span><strong>Ajouter votre premier client</strong><small>puis créez ses retraits et ses colis</small></button>
            ) : <div className="multi-client-list">{plan.clients.map((client, clientIndex) => (
              <article className="multi-client-card" key={`${client.id}-${clientIndex}`}>
                <header>
                  <span className="multi-client-card__number">{String(clientIndex + 1).padStart(2, '0')}</span>
                  <div className="multi-client-card__identity">
                    <InputField label="Identifiant"><input value={client.id} onChange={(event) => updateClient(clientIndex, { id: event.target.value })} /></InputField>
                    <InputField label="Nom du client"><input value={client.name} onChange={(event) => updateClient(clientIndex, { name: event.target.value })} /></InputField>
                  </div>
                  <div className="multi-client-card__actions">
                    <span>{client.deliveryGroups.length} retrait{client.deliveryGroups.length > 1 ? 's' : ''}</span>
                    <button className={`client-collapse${collapsedClients.has(clientIndex) ? ' is-collapsed' : ''}`} type="button" aria-expanded={!collapsedClients.has(clientIndex)} onClick={() => toggleClient(clientIndex)}><ChevronDown />{collapsedClients.has(clientIndex) ? 'Déployer' : 'Réduire'}</button>
                    <button className="text-button" type="button" onClick={() => removeClient(clientIndex)}><Trash2 />Supprimer</button>
                  </div>
                </header>
                {!collapsedClients.has(clientIndex) && <div className="delivery-groups">
                  {client.deliveryGroups.map((group, groupIndex) => (
                    <section className="delivery-group-card delivery-group-card--compact" key={`${group.id}-${groupIndex}`}>
                      <div className="delivery-group-card__heading">
                        <div><Layers3 /><span><small>Retrait {groupIndex + 1} · {group.id}</small><strong>{group.name || 'Nouvelle course'}</strong></span></div>
                        <div className="compact-actions">
                          <button type="button" onClick={() => updateClient(clientIndex, { deliveryGroups: client.deliveryGroups.filter((_, index) => index !== groupIndex) })}><Trash2 />Retirer</button>
                        </div>
                      </div>
                      <div className="delivery-group-inline-editor">
                        <div className="workspace-fields">
                          <InputField label="Identifiant"><input value={group.id} onChange={(event) => updateGroup(clientIndex, groupIndex, { id: event.target.value })} /></InputField>
                          <InputField label="Nom de la course"><input value={group.name} onChange={(event) => updateGroup(clientIndex, groupIndex, { name: event.target.value })} /></InputField>
                        </div>
                        <div className="delivery-group-pickup">
                          <span><MapPin />Point de récupération</span>
                          <LocationPicker accessToken={accessToken} value={group.pickup} title={`Retrait · ${group.name || client.name || `client ${clientIndex + 1}`}`} description="Recherchez et confirmez le lieu où récupérer les colis de cette course." onChange={(pickup) => updateGroup(clientIndex, groupIndex, { pickup })} />
                        </div>
                        <dl className="delivery-group-inline-stats">
                          <div><dt>Colis</dt><dd>{group.packages.length}</dd></div>
                          <div><dt>Poids</dt><dd>{group.packages.reduce((sum, item) => sum + (Number(item.weightKg) || 0), 0).toLocaleString('fr-MG', { maximumFractionDigits: 2 })} kg</dd></div>
                        </dl>
                      </div>
                      <div className="delivery-group-packages delivery-group-packages--compact">
                        <div className="delivery-group-packages__heading">
                          <div><strong>Liste des colis</strong><small>Une ligne par destination</small></div>
                          <div>
                            <CsvImportButton<DeliveryPackage> accessToken={accessToken} kind="packages" onImported={(items, filename) => { updateGroup(clientIndex, groupIndex, { packages: items }); setFileMessage(`${filename} a rempli la course ${group.name || group.id}.`) }} />
                            <button className="button button--secondary" type="button" onClick={() => addPackage(clientIndex, groupIndex)}><PackagePlus />Ajouter</button>
                          </div>
                        </div>
                        {group.packages.length > 0 ? <div className="compact-package-list">{group.packages.map((item, packageIndex) => (
                          <article className="compact-package-row" key={`${item.id}-${packageIndex}`}>
                            <span className="compact-package-row__number">{packageIndex + 1}</span>
                            <div className="compact-package-row__identity"><strong>{item.label || `Colis ${packageIndex + 1}`}</strong><small>{item.id}</small></div>
                            <div className="compact-package-row__destination"><MapPin /><span>{locationLabel(item.destination)}</span></div>
                            <strong className="compact-package-row__weight">{Number(item.weightKg || 0).toLocaleString('fr-MG')} kg</strong>
                            <small className="compact-package-row__window">{deliveryWindowLabel(item)}</small>
                            <div className="compact-package-row__actions">
                              <button type="button" aria-label={`Modifier ${item.label || `colis ${packageIndex + 1}`}`} onClick={() => setEditor({ kind: 'package', clientIndex, groupIndex, packageIndex })}><Pencil /></button>
                              <button type="button" aria-label={`Supprimer ${item.label || `colis ${packageIndex + 1}`}`} onClick={() => updateGroup(clientIndex, groupIndex, { packages: group.packages.filter((_, index) => index !== packageIndex) })}><Trash2 /></button>
                            </div>
                          </article>
                        ))}</div> : <button className="workspace-empty workspace-empty--compact" type="button" onClick={() => addPackage(clientIndex, groupIndex)}><PackagePlus /><strong>Ajouter les colis de ce retrait</strong></button>}
                      </div>
                    </section>
                  ))}
                  <button className="add-group-button" type="button" onClick={() => addGroup(clientIndex)}><Plus /><span><strong>Ajouter un retrait</strong><small>Une origine avec une ou plusieurs livraisons</small></span></button>
                </div>}
              </article>
            ))}</div>}
          </section>}

          {activeTab === 'couriers' && <section className="workspace-section">
            <div className="workspace-section__heading">
              <div><span><Users /></span><div><p className="eyebrow">Équipe partagée</p><h2>Livreurs</h2></div></div>
              <span className="section-badge">{plan.couriers.length} livreur{plan.couriers.length > 1 ? 's' : ''}</span>
            </div>
            {plan.couriers.length === 0 ? <button className="workspace-empty" type="button" onClick={addCourier}><span><UserPlus /></span><strong>Ajouter votre premier livreur</strong><small>l’équipe sera utilisée pour tous les clients</small></button> : <div className="editable-list">{plan.couriers.map((courier, index) => (
              <article className="editable-card" key={`${courier.id}-${index}`}>
                <div className="editable-card__topline"><strong>Livreur {String(index + 1).padStart(2, '0')}</strong><button type="button" onClick={() => updatePlan((current) => ({ ...current, couriers: current.couriers.filter((_, courierIndex) => courierIndex !== index) }))}><Trash2 />Supprimer</button></div>
                <div className="editable-card__grid">
                  <InputField label="Identifiant"><input value={courier.id} onChange={(event) => updateCourier(index, { id: event.target.value })} /></InputField>
                  <InputField label="Nom"><input value={courier.name} onChange={(event) => updateCourier(index, { name: event.target.value })} /></InputField>
                  <InputField label="Véhicule"><select value={courier.vehicleType} onChange={(event) => { const vehicleType = event.target.value as Courier['vehicleType']; updateCourier(index, vehicleType === 'MOTO' ? { vehicleType, maxWeightKg: 30, averageSpeedKmh: 25 } : { vehicleType, maxWeightKg: 10, averageSpeedKmh: 12 }) }}><option value="MOTO">Moto</option><option value="BICYCLE">Bicyclette</option></select></InputField>
                  <InputField label="Capacité (kg)"><input type="number" min="0.01" step="0.01" value={courier.maxWeightKg} onChange={(event) => updateCourier(index, { maxWeightKg: Number(event.target.value) })} /></InputField>
                  <InputField label="Vitesse (km/h)"><input type="number" min="0.01" step="0.01" value={courier.averageSpeedKmh} onChange={(event) => updateCourier(index, { averageSpeedKmh: Number(event.target.value) })} /></InputField>
                  <div className="editable-card__optional"><div className="editable-card__optional-heading"><div><MapPin /><span><strong>Départ personnalisé</strong><small>Facultatif · sinon la tournée commence au premier retrait attribué</small></span></div>{courier.departureAt || courier.departureLocation ? <button type="button" onClick={() => updateCourier(index, { departureAt: undefined, departureLocation: undefined })}><Trash2 />Retirer</button> : <button type="button" onClick={() => updateCourier(index, { departureAt: plan.departureAt, departureLocation: {} })}><Plus />Personnaliser</button>}</div>
                    {(courier.departureAt || courier.departureLocation) && <div className="courier-departure-fields"><InputField label="Heure de départ"><input type="datetime-local" value={courier.departureAt?.slice(0, 16) ?? ''} onChange={(event) => updateCourier(index, { departureAt: toOffsetDateTime(event.target.value) })} /></InputField><div className="editable-card__full-field"><span>Lieu de départ</span><LocationPicker accessToken={accessToken} value={courier.departureLocation ?? {}} title={`Départ de ${courier.name || `livreur ${index + 1}`}`} description="Ce trajet sera compté jusqu’au premier point de récupération attribué." onChange={(departureLocation) => updateCourier(index, { departureLocation })} /></div></div>}
                  </div>
                </div>
              </article>
            ))}</div>}
          </section>}
          </div>
        </section>
        <aside className="plan-sidebar plan-sidebar--scroll">
          {activeTab !== 'couriers' ? (
            <div className="plan-sidebar__card plan-overview"><p className="eyebrow">Plan en cours</p><h2>{plan.name || 'Sans titre'}</h2><dl className="plan-summary"><div><dt>Clients</dt><dd>{plan.clients.length}</dd></div><div><dt>Retraits</dt><dd>{groups.length}</dd></div><div><dt>Colis</dt><dd>{packages.length}</dd></div><div><dt>Livreurs</dt><dd>{plan.couriers.length}</dd></div><div><dt>Poids total</dt><dd>{totalWeight.toLocaleString('fr-MG', { maximumFractionDigits: 2 })} kg</dd></div></dl></div>
          ) : (
            <div className="plan-sidebar__card plan-validation"><p className="eyebrow">Finaliser le plan</p><h3>Prêt à répartir les courses ?</h3><p>Vérifiez les liaisons, puis lancez la répartition entre l’équipe commune.</p><button className="button button--secondary button--wide button--map-preview" type="button" onClick={() => setMapPreviewOpen(true)}><MapPinned />Visualiser les liaisons</button><button className="button button--wide" type="button" disabled={validating || generating} onClick={() => void handleValidation(false)}><ShieldCheck />{validating ? 'Validation…' : 'Valider le plan'}</button><button className="button button--wide button--generate" type="button" disabled={validating || generating} onClick={() => void handleValidation(true)}>{generating ? <LoaderCircle className="spin" /> : <Sparkles />}{generating ? 'Optimisation en cours…' : 'Générer les tournées'}</button>{message && <p className={`validation-result${validationErrors.length ? ' validation-result--error' : ' validation-result--success'}`} role={validationErrors.length ? 'alert' : 'status'}>{message}</p>}{validationErrors.length > 0 && <ul className="validation-errors">{validationErrors.map((error, index) => <li key={`${error.path}-${error.code}-${index}`}><strong>{error.path}</strong>{error.message}</li>)}</ul>}</div>
          )}
        </aside>
      </div>
      {mapPreviewOpen && <PlanMapPreview plan={plan} accessToken={accessToken} onClose={() => setMapPreviewOpen(false)} />}
      {editor && (
        <div className="planning-editor-overlay" role="presentation" onMouseDown={(event) => {
          if (event.target === event.currentTarget) setEditor(undefined)
        }}>
          <section className={`planning-editor-dialog${editor.kind === 'summary' ? ' planning-editor-dialog--summary' : ''}`} role="dialog" aria-modal="true" aria-labelledby="planning-editor-title">
            <header className="planning-editor-dialog__header">
              <div>
                <span>{editor.kind === 'package' ? <PackagePlus /> : <Eye />}</span>
                <div>
                  <p className="eyebrow">{editor.kind === 'summary' ? 'Vue d’ensemble' : 'Modification rapide'}</p>
                  <h2 id="planning-editor-title">{editor.kind === 'package'
                    ? editorPackage?.label || 'Nouveau colis'
                    : 'Résumé du plan'}</h2>
                  {editorClient && <small>{editorClient.name || editorClient.id}</small>}
                </div>
              </div>
              <button type="button" aria-label="Fermer" onClick={() => setEditor(undefined)}><X /></button>
            </header>

            <div className="planning-editor-dialog__body">
              {editor.kind === 'package' && editorPackage && (
                <div className="planning-editor-form">
                  <div className="workspace-fields planning-editor-form__package-fields">
                    <InputField label="Identifiant"><input value={editorPackage.id} onChange={(event) => updatePackage(editor.clientIndex, editor.groupIndex, editor.packageIndex, { id: event.target.value })} /></InputField>
                    <InputField label="Libellé"><input value={editorPackage.label} onChange={(event) => updatePackage(editor.clientIndex, editor.groupIndex, editor.packageIndex, { label: event.target.value })} /></InputField>
                    <InputField label="Poids (kg)"><input type="number" min="0.01" step="0.01" value={editorPackage.weightKg} onChange={(event) => updatePackage(editor.clientIndex, editor.groupIndex, editor.packageIndex, { weightKg: Number(event.target.value) })} /></InputField>
                    <InputField label="Téléphone"><input value={editorPackage.phone ?? ''} onChange={(event) => updatePackage(editor.clientIndex, editor.groupIndex, editor.packageIndex, { phone: event.target.value })} /></InputField>
                  </div>
                  <div className="planning-editor-form__location">
                    <span><MapPin />Destination</span>
                    <LocationPicker accessToken={accessToken} value={editorPackage.destination} title={`Destination · ${editorPackage.label || `colis ${editor.packageIndex + 1}`}`} description="Recherchez l’adresse du destinataire ou placez le point sur la carte." onChange={(location) => updatePackageDestination(editor.clientIndex, editor.groupIndex, editor.packageIndex, location)} />
                  </div>
                  <div className="editable-card__optional planning-editor-form__optional">
                    <div className="editable-card__optional-heading"><div><Clock3 /><span><strong>Créneau de livraison</strong><small>Facultatif</small></span></div>{editorPackage.deliveryWindow ? <button type="button" onClick={() => updatePackage(editor.clientIndex, editor.groupIndex, editor.packageIndex, { deliveryWindow: undefined })}><Trash2 />Retirer</button> : <button type="button" onClick={() => updatePackage(editor.clientIndex, editor.groupIndex, editor.packageIndex, { deliveryWindow: { startAt: plan.departureAt, endAt: defaultWindowEnd(plan.departureAt) } })}><Plus />Ajouter</button>}</div>
                    {editorPackage.deliveryWindow && <div className="optional-fields"><InputField label="Livrer à partir de"><input type="datetime-local" value={editorPackage.deliveryWindow.startAt.slice(0, 16)} onChange={(event) => updatePackage(editor.clientIndex, editor.groupIndex, editor.packageIndex, { deliveryWindow: { ...editorPackage.deliveryWindow!, startAt: toOffsetDateTime(event.target.value) ?? '' } })} /></InputField><InputField label="Livrer avant"><input type="datetime-local" value={editorPackage.deliveryWindow.endAt.slice(0, 16)} onChange={(event) => updatePackage(editor.clientIndex, editor.groupIndex, editor.packageIndex, { deliveryWindow: { ...editorPackage.deliveryWindow!, endAt: toOffsetDateTime(event.target.value) ?? '' } })} /></InputField></div>}
                  </div>
                </div>
              )}

              {editor.kind === 'summary' && (
                <div className="planning-summary">
                  <div className="planning-summary__totals">
                    <div><span>Clients</span><strong>{plan.clients.length}</strong></div>
                    <div><span>Retraits</span><strong>{groups.length}</strong></div>
                    <div><span>Colis</span><strong>{packages.length}</strong></div>
                    <div><span>Poids</span><strong>{totalWeight.toLocaleString('fr-MG', { maximumFractionDigits: 2 })} kg</strong></div>
                  </div>
                  {plan.clients.length > 0 ? <div className="planning-summary__clients">{plan.clients.map((client, clientIndex) => (
                    <article key={`${client.id}-summary-${clientIndex}`}>
                      <header><span>{String(clientIndex + 1).padStart(2, '0')}</span><div><small>{client.id}</small><h3>{client.name || `Client ${clientIndex + 1}`}</h3></div><strong>{client.deliveryGroups.length} retrait{client.deliveryGroups.length > 1 ? 's' : ''}</strong></header>
                      {client.deliveryGroups.length > 0 ? <div>{client.deliveryGroups.map((group, groupIndex) => (
                        <section key={`${group.id}-summary-${groupIndex}`}>
                          <div className="planning-summary__group"><Layers3 /><span><small>{locationLabel(group.pickup)}</small><strong>{group.name || `Retrait ${groupIndex + 1}`}</strong></span><b>{group.packages.length} colis</b></div>
                          {group.packages.length > 0 && <ul>{group.packages.map((item, packageIndex) => <li key={`${item.id}-summary-${packageIndex}`}><span>{packageIndex + 1}</span><strong>{item.label || item.id}</strong><small>{locationLabel(item.destination)}</small><b>{Number(item.weightKg || 0).toLocaleString('fr-MG')} kg</b></li>)}</ul>}
                        </section>
                      ))}</div> : <p>Aucun retrait pour ce client.</p>}
                    </article>
                  ))}</div> : <div className="planning-summary__empty"><Building2 /><strong>Aucun client pour le moment</strong><span>Ajoutez un client pour commencer le plan.</span></div>}
                </div>
              )}
            </div>

            <footer className="planning-editor-dialog__footer">
              <button className="button" type="button" onClick={() => setEditor(undefined)}>{editor.kind === 'summary' ? 'Fermer le résumé' : 'Terminer'}</button>
            </footer>
          </section>
        </div>
      )}
    </div>
  )
}
