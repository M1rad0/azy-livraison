import { useCallback, useEffect, useRef, useState } from 'react'
import { BadgeDollarSign, Bike, Boxes, ClipboardCheck, Settings2, Sparkles, TriangleAlert, UsersRound, WalletCards, Zap } from 'lucide-react'
import {
  cancelAdminDeliveryPackage,
  getAdminQueue,
  getOperationalSettings,
  listCouriers,
  listDeliveryRequests,
  listDispatchPlans,
  listVehiclePresets,
  type AdminQueue,
  type Courier,
  type DeliveryPackage,
  type DeliveryRequest,
  type DispatchPlan,
  type DispatchPlanSummary,
  type OperationalSettings,
  type VehiclePreset,
} from '../../api/admin'
import { AdminCouriersPanel } from './AdminCouriersPanel'
import { AdminDispatchPanel } from './AdminDispatchPanel'
import { AdminDailyOperationsPanel } from './AdminDailyOperationsPanel'
import { AdminFinancePanel } from './AdminFinancePanel'
import { AdminQueuePanel, type PendingQueueFilters } from './AdminQueuePanel'
import { AdminPricingZonesPanel } from './AdminPricingZonesPanel'
import { antananarivoToday, errorMessage } from './adminHelpers'
import { AdminSettingsPanel } from './AdminSettingsPanel'
import { AdminClientsPanel } from './AdminClientsPanel'
import { AdminExpressPanel } from './AdminExpressPanel'
import { usePortalNavigation } from '../PortalShell'
import './AdminPortal.css'

type AdminTab = 'queue' | 'express' | 'dispatch' | 'day' | 'finance' | 'clients' | 'couriers' | 'pricing' | 'settings'

const tabs = [
  { id: 'queue', label: 'File d’attente', detail: 'Demandes et colis', icon: Boxes },
  { id: 'express', label: 'Express', detail: 'Traitement immédiat', icon: Zap },
  { id: 'dispatch', label: 'Dispatch', detail: 'Via point de ralliement', icon: Sparkles },
  { id: 'day', label: 'Compte rendu', detail: 'Clôture et rapports', icon: ClipboardCheck },
  { id: 'finance', label: 'Finances', detail: 'Versements et soldes', icon: WalletCards },
  { id: 'clients', label: 'Clients', detail: 'Comptes et demandes', icon: UsersRound },
  { id: 'couriers', label: 'Livreurs', detail: 'Équipe et véhicules', icon: Bike },
  { id: 'pricing', label: 'Tarification', detail: 'Zones et prix', icon: BadgeDollarSign },
  { id: 'settings', label: 'Paramètres', detail: 'Règles opérationnelles', icon: Settings2 },
] as const

/** Portail autonome destiné au shell authentifié lorsque `session.account.role === 'ADMIN'`. */
export function AdminPortal({ accessToken }: { accessToken: string }) {
  const portalNavigation = usePortalNavigation()
  const [localTab, setLocalTab] = useState<AdminTab>('queue')
  const tab = portalNavigation?.role === 'ADMIN'
    && tabs.some((item) => item.id === portalNavigation.section)
    ? portalNavigation.section as AdminTab
    : localTab
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
  const [pendingBusy, setPendingBusy] = useState(true)
  const [dispatchBusy, setDispatchBusy] = useState(true)
  const [message, setMessage] = useState<string>()
  const [expressRefreshKey, setExpressRefreshKey] = useState(0)
  const pendingLoadId = useRef(0)
  const dispatchLoadId = useRef(0)

  function openTab(nextTab: AdminTab) {
    setLocalTab(nextTab)
    if (portalNavigation?.role === 'ADMIN') portalNavigation.navigate(nextTab)
  }

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
    void loadPending()
    void loadDispatchDay()
  }

  async function cancelPackage(deliveryPackage: DeliveryPackage) {
    await cancelAdminDeliveryPackage(deliveryPackage.id, deliveryPackage.version, accessToken)
    await Promise.all([loadPending(), loadDispatchDay()])
  }

  const currentDispatchQueue = dispatchQueue?.date === dispatchDate ? dispatchQueue : undefined
  const currentHistory = history.filter((plan) => plan.serviceDate === dispatchDate)

  return (
    <div className="admin-portal">
      {message && <div className="admin-global-error" role="alert"><TriangleAlert aria-hidden="true" /><span>{message}</span><button type="button" onClick={() => setMessage(undefined)}>Fermer</button></div>}

      {!portalNavigation && (
        <nav className="admin-tabs" aria-label="Sections de l’administration">
          {tabs.map(({ id, label, detail, icon: Icon }) => (
            <button
              className={tab === id ? 'is-active' : undefined}
              type="button"
              key={id}
              aria-current={tab === id ? 'page' : undefined}
              onClick={() => openTab(id)}
            >
              <span><Icon aria-hidden="true" /></span>
              <span><strong>{label}</strong><small>{detail}</small></span>
            </button>
          ))}
        </nav>
      )}

      {tab === 'queue' && (
        <AdminQueuePanel
          requests={pendingRequests}
          filters={pendingFilters}
          busy={pendingBusy}
          onFiltersChange={setPendingFilters}
          onReload={() => void loadPending()}
          onCancelPackage={cancelPackage}
          onOpenDispatch={() => openTab('dispatch')}
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
          onDateChange={(date) => {
            if (date) setDispatchDate(date)
          }}
          onCreated={dispatchCreated}
        />
      )}
      {tab === 'express' && <AdminExpressPanel accessToken={accessToken} couriers={couriers} refreshKey={expressRefreshKey} onOpenClients={() => openTab('clients')} />}
      {tab === 'day' && (
        <AdminDailyOperationsPanel
          accessToken={accessToken}
          date={dispatchDate}
          couriers={couriers}
          onDateChange={(date) => setDispatchDate(date)}
          onOpenDispatch={() => openTab('dispatch')}
          onChanged={() => {
            void loadPending()
            void loadDispatchDay()
          }}
        />
      )}
      {tab === 'finance' && <AdminFinancePanel accessToken={accessToken} couriers={couriers} initialDate={dispatchDate} />}
      {tab === 'clients' && <AdminClientsPanel accessToken={accessToken} onExpressCreated={() => { setExpressRefreshKey((value) => value + 1); openTab('express') }} />}
      {tab === 'couriers' && <AdminCouriersPanel accessToken={accessToken} couriers={couriers} presets={presets} onSaved={upsertCourier} />}
      {tab === 'pricing' && <AdminPricingZonesPanel accessToken={accessToken} />}
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
