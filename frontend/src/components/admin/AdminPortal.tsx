import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Bike, Boxes, LayoutDashboard, Settings2, Sparkles, TriangleAlert } from 'lucide-react'
import {
  getAdminQueue,
  getOperationalSettings,
  listCouriers,
  listDeliveryRequests,
  listDispatchPlans,
  listVehiclePresets,
  type AdminQueue,
  type Courier,
  type DeliveryRequest,
  type DispatchPlan,
  type DispatchPlanSummary,
  type OperationalSettings,
  type VehiclePreset,
} from '../../api/admin'
import { AdminCouriersPanel } from './AdminCouriersPanel'
import { AdminDispatchPanel } from './AdminDispatchPanel'
import { AdminQueuePanel, type PendingQueueFilters } from './AdminQueuePanel'
import { antananarivoToday, errorMessage } from './adminHelpers'
import { AdminSettingsPanel } from './AdminSettingsPanel'
import './AdminPortal.css'

type AdminTab = 'queue' | 'dispatch' | 'couriers' | 'settings'

const tabs = [
  { id: 'queue', label: 'File d’attente', detail: 'Demandes et colis', icon: Boxes },
  { id: 'dispatch', label: 'Dispatch', detail: 'Via point de ralliement', icon: Sparkles },
  { id: 'couriers', label: 'Livreurs', detail: 'Équipe et véhicules', icon: Bike },
  { id: 'settings', label: 'Paramètres', detail: 'Règles opérationnelles', icon: Settings2 },
] as const

/** Portail autonome destiné au shell authentifié lorsque `session.account.role === 'ADMIN'`. */
export function AdminPortal({ accessToken }: { accessToken: string }) {
  const [tab, setTab] = useState<AdminTab>('queue')
  const [pendingFilters, setPendingFilters] = useState<PendingQueueFilters>({
    scope: 'ALL',
    from: '',
    to: '',
    client: '',
  })
  const [pendingRequests, setPendingRequests] = useState<DeliveryRequest[]>([])
  const [dispatchDate, setDispatchDate] = useState(antananarivoToday)
  const [dispatchQueue, setDispatchQueue] = useState<AdminQueue>()
  const [couriers, setCouriers] = useState<Courier[]>([])
  const [settings, setSettings] = useState<OperationalSettings>()
  const [presets, setPresets] = useState<VehiclePreset[]>([])
  const [history, setHistory] = useState<DispatchPlanSummary[]>([])
  const [selectedPackageIds, setSelectedPackageIds] = useState<Set<string>>(new Set())
  const [pendingBusy, setPendingBusy] = useState(true)
  const [dispatchBusy, setDispatchBusy] = useState(true)
  const [message, setMessage] = useState<string>()
  const pendingLoadId = useRef(0)
  const dispatchLoadId = useRef(0)

  const loadPending = useCallback(async (signal?: AbortSignal) => {
    const loadId = ++pendingLoadId.current
    setPendingBusy(true)
    setMessage(undefined)
    try {
      const requests = await listDeliveryRequests(accessToken, {
        ...(pendingFilters.scope === 'PERIOD' && pendingFilters.from
          ? { from: pendingFilters.from }
          : {}),
        ...(pendingFilters.scope === 'PERIOD' && pendingFilters.to
          ? { to: pendingFilters.to }
          : {}),
        ...(pendingFilters.client.trim() ? { client: pendingFilters.client.trim() } : {}),
        pendingOnly: true,
      }, signal)
      if (signal?.aborted || loadId !== pendingLoadId.current) return
      setPendingRequests(requests)
      if (pendingFilters.scope === 'ALL' && !pendingFilters.client.trim()) {
        const pendingIds = new Set(requests.flatMap((request) => request.packages)
          .map((deliveryPackage) => deliveryPackage.id))
        setSelectedPackageIds((current) => new Set([...current].filter((id) => pendingIds.has(id))))
      }
    } catch (error) {
      if ((error as { name?: string }).name !== 'AbortError'
        && loadId === pendingLoadId.current) setMessage(errorMessage(error))
    } finally {
      if (!signal?.aborted && loadId === pendingLoadId.current) setPendingBusy(false)
    }
  }, [accessToken, pendingFilters])

  useEffect(() => {
    const controller = new AbortController()
    void loadPending(controller.signal)
    return () => controller.abort()
  }, [loadPending])

  const loadDispatchDay = useCallback(async (signal?: AbortSignal) => {
    const loadId = ++dispatchLoadId.current
    setDispatchBusy(true)
    setMessage(undefined)
    try {
      const [nextQueue, nextHistory] = await Promise.all([
        getAdminQueue(dispatchDate, accessToken, signal),
        listDispatchPlans(dispatchDate, accessToken, signal),
      ])
      if (signal?.aborted || loadId !== dispatchLoadId.current) return
      setDispatchQueue(nextQueue)
      setHistory(nextHistory)
    } catch (error) {
      if ((error as { name?: string }).name !== 'AbortError'
        && loadId === dispatchLoadId.current) setMessage(errorMessage(error))
    } finally {
      if (!signal?.aborted && loadId === dispatchLoadId.current) setDispatchBusy(false)
    }
  }, [accessToken, dispatchDate])

  useEffect(() => {
    const controller = new AbortController()
    void loadDispatchDay(controller.signal)
    return () => controller.abort()
  }, [loadDispatchDay])

  useEffect(() => {
    const controller = new AbortController()
    Promise.all([
      listCouriers(accessToken, controller.signal),
      getOperationalSettings(accessToken, controller.signal),
      listVehiclePresets(accessToken, controller.signal),
    ]).then(([nextCouriers, nextSettings, nextPresets]) => {
      setCouriers(nextCouriers)
      setSettings(nextSettings)
      setPresets(nextPresets)
    }).catch((error: unknown) => {
      if ((error as { name?: string }).name !== 'AbortError') setMessage(errorMessage(error))
    })
    return () => controller.abort()
  }, [accessToken])

  function updateSelection(packageId: string, selected: boolean) {
    setSelectedPackageIds((current) => {
      const next = new Set(current)
      if (selected) next.add(packageId)
      else next.delete(packageId)
      return next
    })
  }

  function selectAllPending() {
    setSelectedPackageIds((current) => new Set([
      ...current,
      ...pendingRequests.flatMap((request) => request.packages)
        .map((deliveryPackage) => deliveryPackage.id),
    ]))
  }

  function upsertCourier(saved: Courier) {
    setCouriers((current) => {
      const exists = current.some((courier) => courier.id === saved.id)
      return exists
        ? current.map((courier) => courier.id === saved.id ? saved : courier)
        : [...current, saved].sort((left, right) => left.name.localeCompare(right.name, 'fr'))
    })
  }

  function dispatchCreated(created: DispatchPlan) {
    setHistory((current) => [created.plan, ...current.filter((plan) => plan.id !== created.plan.id)])
    setSelectedPackageIds(new Set())
    void loadPending()
    void loadDispatchDay()
  }

  const activeCourierCount = couriers.filter((courier) => courier.active).length
  const pendingPackageCount = useMemo(() => pendingRequests.reduce(
    (count, request) => count + request.packages.length,
    0,
  ), [pendingRequests])
  const currentDispatchQueue = dispatchQueue?.date === dispatchDate ? dispatchQueue : undefined
  const currentHistory = history.filter((plan) => plan.serviceDate === dispatchDate)

  return (
    <div className="admin-portal">
      <section className="admin-portal__intro" aria-labelledby="admin-portal-title">
        <div className="admin-portal__intro-icon"><LayoutDashboard aria-hidden="true" /></div>
        <div><p className="admin-eyebrow">Centre des opérations</p><h1 id="admin-portal-title">Piloter les livraisons</h1><p>Suivez la file, mobilisez les livreurs et enregistrez chaque journée depuis un seul espace.</p></div>
        <div className="admin-portal__today"><small>File globale</small><strong>{pendingBusy && pendingRequests.length === 0 ? '—' : pendingPackageCount} colis</strong><span>{activeCourierCount} livreur{activeCourierCount > 1 ? 's' : ''} actif{activeCourierCount > 1 ? 's' : ''}</span></div>
      </section>

      <nav className="admin-tabs" aria-label="Sections d’administration">
        {tabs.map(({ id, label, detail, icon: Icon }) => (
          <button key={id} type="button" className={tab === id ? 'is-active' : undefined} aria-current={tab === id ? 'page' : undefined} onClick={() => setTab(id)}>
            <span><Icon aria-hidden="true" /></span>
            <span><strong>{label}</strong><small>{detail}</small></span>
            {id === 'queue' && pendingPackageCount > 0 && <b>{pendingPackageCount}</b>}
          </button>
        ))}
      </nav>

      {message && <div className="admin-global-error" role="alert"><TriangleAlert aria-hidden="true" /><span>{message}</span><button type="button" onClick={() => setMessage(undefined)}>Fermer</button></div>}

      {tab === 'queue' && (
        <AdminQueuePanel
          requests={pendingRequests}
          filters={pendingFilters}
          busy={pendingBusy}
          selectedPackageIds={selectedPackageIds}
          onFiltersChange={setPendingFilters}
          onReload={() => void loadPending()}
          onPackageSelectionChange={updateSelection}
          onSelectAllPending={selectAllPending}
          onOpenDispatch={() => setTab('dispatch')}
        />
      )}
      {tab === 'dispatch' && (
        <AdminDispatchPanel
          accessToken={accessToken}
          date={dispatchDate}
          queue={currentDispatchQueue}
          busy={dispatchBusy}
          couriers={couriers}
          settings={settings}
          history={currentHistory}
          selectedPackageIds={selectedPackageIds}
          onDateChange={(date) => {
            if (date) setDispatchDate(date)
          }}
          onPackageSelectionChange={updateSelection}
          onCreated={dispatchCreated}
        />
      )}
      {tab === 'couriers' && <AdminCouriersPanel accessToken={accessToken} couriers={couriers} presets={presets} onSaved={upsertCourier} />}
      {tab === 'settings' && (
        <AdminSettingsPanel
          accessToken={accessToken}
          settings={settings}
          presets={presets}
          onSettingsSaved={setSettings}
          onPresetsSaved={setPresets}
        />
      )}
    </div>
  )
}
