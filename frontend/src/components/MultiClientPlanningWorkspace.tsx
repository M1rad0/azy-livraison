import { useId, useState, type ChangeEvent, type ReactNode } from 'react'
import {
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
  migrateMultiClientDeliveryPlan,
  PlanValidationFailure,
  validatePlan,
  type DeliveryClient,
  type DeliveryGroup,
  type MultiClientDeliveryPlan,
  type PlanningValidationError,
} from '../api/plans'
import { compareOptimizations, type OptimizationComparisonResult } from '../api/optimization'
import { LocationPicker } from './LocationPicker'
import { PhoneInput } from './PhoneInput'
import { isValidPhoneNumber, normalizePhoneNumber, PHONE_NUMBER_ERROR } from '../phone'
import { OptimizationComparisonResults } from './OptimizationComparisonResults'
import { PlanMapPreview } from './PlanMapPreview'
import { CsvImportButton } from './PlanningWorkspace'
import { WorkspaceDraftBar } from './WorkspaceDraftBar'
import { validationFieldLabel, validationPathTab, WorkspaceTabs, type WorkspaceTab } from './WorkspaceTabs'

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
    schemaVersion: '2.1',
    pattern: 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY',
    planId: `multi-${localDeparture.slice(0, 10)}`,
    name: `Plan de retraits du ${localDeparture.slice(0, 10)}`,
    departureAt: `${localDeparture}:00+03:00`,
    timezone: 'Indian/Antananarivo',
    settings: { returnToDepot: false, routingPreset: 'MAIN_ROADS' },
    couriers: [],
    clients: [],
    redistributionHub: { destinationQuery: '' },
  }
}

function normalizeWithdrawalPlan(plan: MultiClientDeliveryPlan): MultiClientDeliveryPlan {
  const usedClientIds: string[] = []
  const usedGroupIds = plan.clients.flatMap((client) => client.deliveryGroups.map((group) => group.id))
  const clients: DeliveryClient[] = []

  plan.clients.forEach((client) => {
    const deliveryGroups = client.deliveryGroups.length > 0
      ? client.deliveryGroups
      : [{ id: nextId('retrait', usedGroupIds), name: '', pickup: { destinationQuery: '' }, packages: [] }]

    deliveryGroups.forEach((group, groupIndex) => {
      if (!usedGroupIds.includes(group.id)) usedGroupIds.push(group.id)
      const preservedId = groupIndex === 0 && client.id.trim() && !usedClientIds.includes(client.id)
        ? client.id
        : undefined
      const clientId = preservedId ?? nextId('client', usedClientIds)
      usedClientIds.push(clientId)
      clients.push({ id: clientId, name: client.name, deliveryGroups: [{ ...group }] })
    })
  })

  return { ...plan, clients }
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

function hasCoordinates(location: Location) {
  return Number.isFinite(location.latitude) && Number.isFinite(location.longitude)
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
  const [optimizationComparison, setOptimizationComparison] = useState<OptimizationComparisonResult>()
  const [activeTab, setActiveTab] = useState<WorkspaceTab>('plan')

  function updatePlan(update: (current: MultiClientDeliveryPlan) => MultiClientDeliveryPlan) {
    setPlan((current) => update(current))
    setValidationErrors([])
    setMessage(undefined)
  }

  function addWithdrawal() {
    updatePlan((current) => {
      const clientId = nextId('client', current.clients.map((client) => client.id))
      const groupIds = current.clients.flatMap((client) => client.deliveryGroups.map((group) => group.id))
      const group: DeliveryGroup = {
        id: nextId('retrait', groupIds),
        name: '',
        pickup: { destinationQuery: '' },
        packages: [],
      }
      return { ...current, clients: [...current.clients, { id: clientId, name: '', deliveryGroups: [group] }] }
    })
  }

  function updateClient(clientIndex: number, update: Partial<DeliveryClient>) {
    updatePlan((current) => ({
      ...current,
      clients: current.clients.map((client, index) => index === clientIndex ? { ...client, ...update } : client),
    }))
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

  function removeWithdrawal(clientIndex: number) {
    setEditor(undefined)
    updatePlan((current) => ({ ...current, clients: current.clients.filter((_, index) => index !== clientIndex) }))
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
        departureAt: current.departureAt,
        departureLocation: { destinationQuery: '' },
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
        throw new Error('Cette sauvegarde ne correspond pas au mode plusieurs retraits.')
      }
      const loaded = normalizeWithdrawalPlan(materializeVehicleDefaults(migrateMultiClientDeliveryPlan(parsed)))
      setPlan(loaded)
      setValidationErrors([])
      const withdrawals = loaded.clients.flatMap((client) => client.deliveryGroups)
      const packageCount = withdrawals.reduce((sum, group) => sum + group.packages.length, 0)
      setFileMessage(`${file.name} chargé : ${withdrawals.length} retraits et ${packageCount} colis.`)
    } catch (error) {
      setFileMessage(error instanceof Error ? error.message : 'Lecture du fichier impossible.')
    }
  }

  async function handleValidation(generate: boolean) {
    if (plan.clients.some((client) => client.deliveryGroups.some((group) => group.packages.some((item) => item.phone?.trim() && !isValidPhoneNumber(item.phone))))) {
      setMessage(PHONE_NUMBER_ERROR)
      setActiveTab('deliveries')
      return
    }
    generate ? setGenerating(true) : setValidating(true)
    setValidationErrors([])
    setMessage(undefined)
    try {
      const response = await validatePlan({ ...plan, clients: plan.clients.map((client) => ({ ...client, deliveryGroups: client.deliveryGroups.map((group) => ({ ...group, packages: group.packages.map((item) => ({ ...item, phone: item.phone ? normalizePhoneNumber(item.phone) : item.phone })) })) })) }, accessToken)
      if (!response.plan || response.plan.pattern !== 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY') return
      const normalizedPlan = normalizeWithdrawalPlan(response.plan)
      setPlan(normalizedPlan)
      if (generate) {
        setOptimizationComparison(await compareOptimizations(normalizedPlan, accessToken))
        window.scrollTo({ top: 0, behavior: 'smooth' })
      } else {
        setMessage('Le plan est valide et prêt pour l’optimisation.')
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

  const withdrawals = plan.clients.flatMap((client, clientIndex) => client.deliveryGroups.map((group, groupIndex) => ({
    client,
    clientIndex,
    group,
    groupIndex,
  })))
  const packages = withdrawals.flatMap((withdrawal) => withdrawal.group.packages)
  const totalWeight = packages.reduce((sum, item) => sum + (Number(item.weightKg) || 0), 0)
  const hubConfirmed = hasCoordinates(plan.redistributionHub)
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

  if (optimizationComparison) {
    return <OptimizationComparisonResults comparison={optimizationComparison} plan={plan} accessToken={accessToken} onBack={() => setOptimizationComparison(undefined)} />
  }

  return (
    <div className="planning-workspace planning-workspace--multi">
      <section className="workspace-hero" aria-labelledby="multi-workspace-title">
        <div>
          <p className="eyebrow">Plan · Plusieurs retraits</p>
          <h1 id="multi-workspace-title">Préparez tous les retraits.</h1>
          <p>Ajoutez chaque point de récupération avec le nom du client et ses colis. Un même client peut apparaître sur plusieurs retraits.</p>
        </div>
        <div className="workspace-progress"><span className="is-complete">1 <small>Mode</small></span><span className="is-active">2 <small>Préparation</small></span><span>3 <small>Résultat</small></span></div>
      </section>

      <WorkspaceDraftBar inputId={jsonInputId} message={fileMessage} onImport={importJson} onExport={exportJson} />

      <WorkspaceTabs
        activeTab={activeTab}
        deliveryLabel="Retraits"
        deliveryMeta={`${withdrawals.length} retrait${withdrawals.length > 1 ? 's' : ''} · ${packages.length} colis`}
        courierCount={plan.couriers.length}
        errorCounts={tabErrorCounts}
        onChange={setActiveTab}
      />

      {message && validationErrors.length > 0 && activeTab !== 'couriers' && (
        <div className="workspace-feedback" role="alert">
          <strong>{message}</strong>
          <ul className="validation-errors">
            {validationErrors.map((error, index) => <li key={`${error.path}-${error.code}-${index}`}><strong>{validationFieldLabel(error.path)}</strong>{error.message}</li>)}
          </ul>
        </div>
      )}

      <div className="planning-layout">
        <div className="planning-form" role="tabpanel" aria-label={activeTab === 'plan' ? 'Plan' : activeTab === 'deliveries' ? 'Retraits' : 'Livreurs'}>
          {activeTab === 'plan' && <section className="workspace-section">
            <div className="workspace-section__heading"><div><span><ClipboardList /></span><div><p className="eyebrow">Brouillon commun</p><h2>Informations générales</h2></div></div></div>
            <div className="workspace-fields">
              <InputField label="Référence"><input value={plan.planId} onChange={(event) => updatePlan((current) => ({ ...current, planId: event.target.value }))} /></InputField>
              <InputField label="Nom du plan"><input value={plan.name} onChange={(event) => updatePlan((current) => ({ ...current, name: event.target.value }))} /></InputField>
              <InputField label="Départ · heure d’Antananarivo"><input type="datetime-local" value={plan.departureAt.slice(0, 16)} onChange={(event) => updatePlan((current) => ({ ...current, departureAt: `${event.target.value}:00+03:00` }))} /></InputField>
            </div>
            <div className={`hub-location-card${hubConfirmed ? ' is-confirmed' : ''}`}>
              <div className="hub-location-card__heading">
                <span><MapPinned /></span>
                <div><p className="eyebrow">Redistribution</p><h3>Siège / point de rencontre</h3><small>Tous les colis y passent après les retraits pour être redistribués entre les livreurs.</small></div>
                <strong>{hubConfirmed ? 'Siège confirmé' : 'Siège à confirmer'}</strong>
              </div>
              <LocationPicker accessToken={accessToken} value={plan.redistributionHub} title="Siège / point de rencontre" description="Recherchez et confirmez le lieu où les livreurs se retrouvent pour redistribuer les colis." onChange={(redistributionHub) => updatePlan((current) => ({ ...current, redistributionHub }))} />
            </div>
          </section>}

          {activeTab === 'deliveries' && <section className="workspace-section withdrawals-section" aria-labelledby="withdrawals-title">
            <div className="workspace-section__heading workspace-section__heading--actions">
              <div><span><Layers3 /></span><div><p className="eyebrow">Points à desservir</p><h2 id="withdrawals-title">Retraits</h2></div></div>
              <div className="workspace-section__actions">
                <button className="button button--secondary" type="button" onClick={() => setEditor({ kind: 'summary' })}><Eye />Résumé</button>
                <button className="button" type="button" onClick={addWithdrawal}><Plus />Ajouter un retrait</button>
              </div>
            </div>
            {withdrawals.length === 0 ? (
              <button className="workspace-empty" type="button" onClick={addWithdrawal}><span><Layers3 /></span><strong>Ajouter votre premier retrait</strong><small>un client, un point de récupération et ses colis</small></button>
            ) : <div className="withdrawal-list">{withdrawals.map(({ client, clientIndex, group, groupIndex }, withdrawalIndex) => (
              <article className="delivery-group-card delivery-group-card--compact withdrawal-card" aria-label={`Retrait ${withdrawalIndex + 1}`} key={`${client.id}-${group.id}`}>
                <div className="delivery-group-card__heading">
                  <div><Layers3 /><span><small>Retrait {String(withdrawalIndex + 1).padStart(2, '0')}</small><strong>{group.name || client.name || 'Nouveau retrait'}</strong></span></div>
                  <div className="compact-actions">
                    {client.name && <span className="withdrawal-client-label">{client.name}</span>}
                    <button type="button" aria-label={`Supprimer le retrait ${withdrawalIndex + 1}`} onClick={() => removeWithdrawal(clientIndex)}><Trash2 />Supprimer</button>
                  </div>
                </div>
                <div className="delivery-group-inline-editor">
                  <div className="workspace-fields">
                    <InputField label="Client"><input placeholder="Nom ou libellé du client" value={client.name} onChange={(event) => updateClient(clientIndex, { name: event.target.value })} /></InputField>
                    <InputField label="Libellé"><input placeholder="Ex. Boutique Analakely" value={group.name} onChange={(event) => updateGroup(clientIndex, groupIndex, { name: event.target.value })} /></InputField>
                  </div>
                  <div className="delivery-group-pickup">
                    <div className="delivery-group-pickup__heading">
                      <span><MapPin />Point de récupération</span>
                      <small>Lieu où les colis de ce retrait seront récupérés</small>
                    </div>
                    <LocationPicker accessToken={accessToken} value={group.pickup} title={`Retrait · ${group.name || client.name || withdrawalIndex + 1}`} description="Recherchez et confirmez le lieu où récupérer les colis." onChange={(pickup) => updateGroup(clientIndex, groupIndex, { pickup })} />
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
                      <CsvImportButton<DeliveryPackage> accessToken={accessToken} kind="packages" onImported={(items, filename) => { updateGroup(clientIndex, groupIndex, { packages: items }); setFileMessage(`${filename} a rempli le retrait ${group.name || group.id}.`) }} />
                      <button className="button button--secondary" type="button" onClick={() => addPackage(clientIndex, groupIndex)}><PackagePlus />Ajouter un colis</button>
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
              </article>
            ))}</div>}
          </section>}

          {activeTab === 'couriers' && <section className="workspace-section">
            <div className="workspace-section__heading workspace-section__heading--actions">
              <div><span><Users /></span><div><p className="eyebrow">Équipe partagée</p><h2>Livreurs</h2></div></div>
              <div className="workspace-section__actions"><CsvImportButton<Courier> accessToken={accessToken} kind="couriers" onImported={(items, filename) => { updatePlan((current) => ({ ...current, couriers: items.map((courier) => ({ ...courier, departureAt: courier.departureAt ?? current.departureAt, departureLocation: courier.departureLocation ?? { destinationQuery: '' } })) })); setFileMessage(`${filename} a remplacé la liste des livreurs.`) }} /><button className="button" type="button" onClick={addCourier}><UserPlus />Ajouter</button></div>
            </div>
            {plan.couriers.length === 0 ? <button className="workspace-empty" type="button" onClick={addCourier}><span><UserPlus /></span><strong>Ajouter votre premier livreur</strong><small>l’équipe sera utilisée pour tous les retraits</small></button> : <div className="editable-list">{plan.couriers.map((courier, index) => (
              <article className="editable-card" key={`${courier.id}-${index}`}>
                <div className="editable-card__topline"><strong>Livreur {String(index + 1).padStart(2, '0')}</strong><button type="button" onClick={() => updatePlan((current) => ({ ...current, couriers: current.couriers.filter((_, courierIndex) => courierIndex !== index) }))}><Trash2 />Supprimer</button></div>
                <div className="editable-card__grid">
                  <InputField label="Identifiant"><input value={courier.id} onChange={(event) => updateCourier(index, { id: event.target.value })} /></InputField>
                  <InputField label="Nom"><input value={courier.name} onChange={(event) => updateCourier(index, { name: event.target.value })} /></InputField>
                  <InputField label="Véhicule"><select value={courier.vehicleType} onChange={(event) => { const vehicleType = event.target.value as Courier['vehicleType']; updateCourier(index, vehicleType === 'MOTO' ? { vehicleType, maxWeightKg: 30, averageSpeedKmh: 25 } : { vehicleType, maxWeightKg: 10, averageSpeedKmh: 12 }) }}><option value="MOTO">Moto</option><option value="BICYCLE">Bicyclette</option></select></InputField>
                  <InputField label="Capacité (kg)"><input type="number" min="0.01" step="0.01" value={courier.maxWeightKg} onChange={(event) => updateCourier(index, { maxWeightKg: Number(event.target.value) })} /></InputField>
                  <InputField label="Vitesse (km/h)"><input type="number" min="0.01" step="0.01" value={courier.averageSpeedKmh} onChange={(event) => updateCourier(index, { averageSpeedKmh: Number(event.target.value) })} /></InputField>
                  <div className="editable-card__optional editable-card__departure"><div className="editable-card__optional-heading"><div><MapPin /><span><strong>Départ du livreur</strong><small>Requis · le trajet jusqu’au premier retrait ou au siège fait partie du calcul.</small></span></div></div>
                    <div className="courier-departure-fields"><InputField label="Heure de départ"><input required type="datetime-local" value={courier.departureAt.slice(0, 16)} onChange={(event) => updateCourier(index, { departureAt: toOffsetDateTime(event.target.value) ?? '' })} /></InputField><div className="editable-card__full-field"><span>Lieu de départ · requis</span><LocationPicker accessToken={accessToken} value={courier.departureLocation} title={`Départ de ${courier.name || `livreur ${index + 1}`}`} description="Confirmez le point depuis lequel ce livreur commence sa journée." onChange={(departureLocation) => updateCourier(index, { departureLocation })} /></div></div>
                  </div>
                </div>
              </article>
            ))}</div>}
          </section>}
        </div>
        <aside className="plan-sidebar">
          {activeTab !== 'couriers' ? (
            <div className="plan-sidebar__card plan-overview"><p className="eyebrow">Plan en cours</p><h2>{plan.name || 'Sans titre'}</h2><dl className="plan-summary"><div><dt>Retraits</dt><dd>{withdrawals.length}</dd></div><div><dt>Colis</dt><dd>{packages.length}</dd></div><div><dt>Livreurs</dt><dd>{plan.couriers.length}</dd></div><div><dt>Poids total</dt><dd>{totalWeight.toLocaleString('fr-MG', { maximumFractionDigits: 2 })} kg</dd></div><div className={`plan-summary__hub${hubConfirmed ? ' is-confirmed' : ''}`}><dt>Siège</dt><dd>{hubConfirmed ? 'Confirmé' : 'À confirmer'}</dd></div></dl></div>
          ) : (
            <div className="plan-sidebar__card plan-validation"><p className="eyebrow">Finaliser le plan</p><h3>Prêt à répartir les courses ?</h3><p>Vérifiez les liaisons, puis lancez la répartition entre l’équipe commune.</p><button className="button button--secondary button--wide button--map-preview" type="button" onClick={() => setMapPreviewOpen(true)}><MapPinned />Visualiser les liaisons</button><button className="button button--wide" type="button" disabled={validating || generating} onClick={() => void handleValidation(false)}><ShieldCheck />{validating ? 'Validation…' : 'Valider le plan'}</button><button className="button button--wide button--generate" type="button" disabled={validating || generating} onClick={() => void handleValidation(true)}>{generating ? <LoaderCircle className="spin" /> : <Sparkles />}{generating ? 'Optimisation en cours…' : 'Générer les tournées'}</button>{message && <p className={`validation-result${validationErrors.length ? ' validation-result--error' : ' validation-result--success'}`} role={validationErrors.length ? 'alert' : 'status'}>{message}</p>}{validationErrors.length > 0 && <ul className="validation-errors">{validationErrors.map((error, index) => <li key={`${error.path}-${error.code}-${index}`}><strong>{validationFieldLabel(error.path)}</strong>{error.message}</li>)}</ul>}</div>
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
                    <InputField label="Téléphone"><PhoneInput value={editorPackage.phone ?? ''} onChange={(event) => updatePackage(editor.clientIndex, editor.groupIndex, editor.packageIndex, { phone: event.target.value })} /></InputField>
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
                    <div><span>Retraits</span><strong>{withdrawals.length}</strong></div>
                    <div><span>Colis</span><strong>{packages.length}</strong></div>
                    <div><span>Livreurs</span><strong>{plan.couriers.length}</strong></div>
                    <div><span>Poids</span><strong>{totalWeight.toLocaleString('fr-MG', { maximumFractionDigits: 2 })} kg</strong></div>
                  </div>
                  <div className={`planning-summary__hub${hubConfirmed ? ' is-confirmed' : ''}`}>
                    <MapPinned />
                    <div><small>Siège / point de rencontre</small><strong>{hubConfirmed ? locationLabel(plan.redistributionHub) : 'Lieu à confirmer'}</strong></div>
                    <span>{hubConfirmed ? 'Confirmé' : 'À confirmer'}</span>
                  </div>
                  {withdrawals.length > 0 ? <div className="planning-summary__clients">{withdrawals.map(({ client, group }, withdrawalIndex) => (
                    <article key={`${client.id}-${group.id}-summary`}>
                      <header><span>{String(withdrawalIndex + 1).padStart(2, '0')}</span><div><small>Client</small><h3>{client.name || 'Client non renseigné'}</h3></div><strong>{group.name || `Retrait ${withdrawalIndex + 1}`}</strong></header>
                      <div>
                        <section>
                          <div className="planning-summary__group"><Layers3 /><span><small>{locationLabel(group.pickup)}</small><strong>{group.name || `Retrait ${withdrawalIndex + 1}`}</strong></span><b>{group.packages.length} colis</b></div>
                          {group.packages.length > 0 && <ul>{group.packages.map((item, packageIndex) => <li key={`${item.id}-summary-${packageIndex}`}><span>{packageIndex + 1}</span><strong>{item.label || item.id}</strong><small>{locationLabel(item.destination)}</small><b>{Number(item.weightKg || 0).toLocaleString('fr-MG')} kg</b></li>)}</ul>}
                        </section>
                      </div>
                    </article>
                  ))}</div> : <div className="planning-summary__empty"><Layers3 /><strong>Aucun retrait pour le moment</strong><span>Ajoutez un retrait pour commencer le plan.</span></div>}
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
