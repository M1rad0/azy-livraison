import { useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { ArrowRight, ArrowRightLeft, Banknote, Bike, CalendarDays, Check, ChevronLeft, ChevronRight, Copy, List, LoaderCircle, MapPin, MessageSquareText, PackageCheck, Phone, RefreshCw, TriangleAlert, Warehouse, X } from 'lucide-react'
import { getDispatchPlan, listAdminClients, type DispatchPlanSummary } from '../../api/admin'
import { getManualDraftForPlan, type ManualDispatchDraft, type ManualPackage } from '../../api/manualDispatch'
import { listCashBriefings, type CashBriefingBatch, type CashBriefingTransfer } from '../../api/cashBriefing'
import { formatOneHourWindow } from '../../deliveryWindows'
import { Button } from '../Button'
import { CourierCombobox } from './CourierCombobox'
import { buildDispatchBriefing, generateCourierBriefing, type BriefingLanguage, type BriefingPickup, type CourierBriefing } from './dispatchBriefing'
import { manualDispatchCourierColor } from './manualDispatchGeography'
import { dispatchPlace, dispatchPlaceText } from './manualDispatchPresentation'
import { errorMessage, formatAriary, formatDate, formatTime } from './adminHelpers'
import '../ClientPortal.css'
import './AdminBriefingPanel.css'

interface AdminBriefingPanelProps {
  accessToken: string
  date: string
  history: DispatchPlanSummary[]
  busy?: boolean
  onDateChange: (date: string) => void
  onOpenDispatch: () => void
  onOpenPayouts?: () => void
  onRefresh?: () => void
}

function BriefingSection({ number, title, icon, children }: { number: number; title: string; icon: ReactNode; children: ReactNode }) {
  return <section className="briefing-section" aria-label={title}>
    <header><span className="briefing-step">{number}</span><h3>{icon}{title}</h3></header>
    {children}
  </section>
}

function PackageList({ packages, financial = false }: { packages: ManualPackage[]; financial?: boolean }) {
  return <ol className={`briefing-packages${financial ? ' briefing-packages--delivery' : ''}`}>
    {packages.map((item, index) => {
      const place = dispatchPlace(item.destination)
      const window = formatOneHourWindow(item.desiredDeliveryTime, item.desiredDeliveryEndTime, undefined, item.deliveryTimePreference)
      return <li key={item.packageId}>
        <span className="briefing-order">{index + 1}</span>
        <div className="briefing-package-info"><strong>{item.recipientName}</strong><span>{place.title}</span>{place.address && <small>{place.address}</small>}<small>{item.serviceClientName}{item.faritra ? ' · Faritra' : ''}</small>
          {financial && <div className="briefing-package-contact">{item.contact && <span><Phone aria-hidden="true" />{item.contact}</span>}{window && <span>{window}</span>}</div>}
        </div>
        {financial && <div className="briefing-package-amount"><strong>{formatAriary(item.amountToCollectAriary)}</strong><small>À encaisser</small>{item.mobileMoneyPaid && <small>Mobile Money signalé</small>}</div>}
      </li>
    })}
  </ol>
}

function PickupList({ pickups }: { pickups: BriefingPickup[] }) {
  return <ol className="briefing-pickups">{pickups.map(({ pickup, packages, clients }, index) => {
    const place = dispatchPlace(pickup.location)
    return <li key={pickup.id}><span className="briefing-order">{index + 1}</span><div>
      <strong>{place.title}</strong>{place.address && <span>{place.address}</span>}
      <small>{clients.join(' · ')} · {packages.length} colis</small>
      <details><summary>Voir les colis à récupérer</summary><ul>{packages.map((item) => <li key={item.packageId}>{item.recipientName} — {dispatchPlaceText(item.destination)}</li>)}</ul></details>
    </div></li>
  })}</ol>
}

function CourierHeading({ briefing }: { briefing: CourierBriefing }) {
  return <header className="briefing-courier-heading"><div><span style={{ background: manualDispatchCourierColor(briefing.courier.courierId) }} aria-hidden="true"><Bike /></span><strong>{briefing.courier.name}</strong></div>
  </header>
}

function PayoutList({ transfers, stale = false }: { transfers: CashBriefingTransfer[]; stale?: boolean }) {
  if (stale) return <p className="briefing-empty">Les versements sont à revalider dans l’étape Versements.</p>
  if (!transfers.length) return <p className="briefing-empty">Aucun versement à faire.</p>
  return <ul className="briefing-payouts">{transfers.map((transfer) => <li key={transfer.id}>
    <div><strong>{transfer.sellerName}</strong><span>{transfer.sellerPayoutOperator?.trim() || 'MVola'} : {transfer.sellerPayoutNumber || 'Numéro non renseigné'}</span><span>{transfer.sellerPayoutAccountHolder?.trim() ? `Titulaire : ${transfer.sellerPayoutAccountHolder.trim()}` : 'Titulaire non renseigné'}</span></div>
    <strong>{formatAriary(transfer.amountAriary)}</strong>
  </li>)}</ul>
}

function cashSignature(batch?: CashBriefingBatch) {
  return batch ? JSON.stringify([batch.id, batch.status, batch.ledgerRevision, batch.transfers.map((transfer) => [
    transfer.id, transfer.status, transfer.amountAriary, transfer.sellerPayoutNumber,
    transfer.sellerPayoutAccountHolder, transfer.sellerPayoutOperator, transfer.sellerPayoutDestinationId,
  ])]) : ''
}

function BriefingTextDialog({ courierName, text, language, onLanguageChange, onChange, onClose, beforeCopy }: {
  courierName: string
  text: string
  language: BriefingLanguage
  onLanguageChange: (language: BriefingLanguage) => void
  onChange: (value: string) => void
  onClose: () => void
  beforeCopy?: () => Promise<boolean>
}) {
  const titleId = useId()
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const dialogRef = useRef<HTMLElement>(null)
  const [copying, setCopying] = useState(false)
  const [copyMessage, setCopyMessage] = useState<string>()
  const currentLanguage = useRef(language)
  currentLanguage.current = language

  useEffect(() => { setCopyMessage(undefined) }, [language])

  useEffect(() => {
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : undefined
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    textareaRef.current?.focus()
    const keyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); onClose() }
      if (event.key !== 'Tab') return
      const controls = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>('button:not(:disabled), textarea') ?? [])
        .filter((control) => control.tabIndex >= 0)
        .sort((a, b) => a === b ? 0 : a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1)
      if (!controls.length) return
      const first = controls[0], last = controls[controls.length - 1]
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
    }
    window.addEventListener('keydown', keyDown)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', keyDown)
      if (previousFocus?.isConnected) previousFocus.focus()
    }
  }, [onClose])

  async function copy() {
    if (copying || !text.trim()) return
    setCopying(true); setCopyMessage(undefined)
    if (beforeCopy) {
      let valid = false
      try { valid = await beforeCopy() } catch { /* Never fall back to copying an unverified cash instruction. */ }
      if (!valid) { setCopyMessage('Les caisses ou la proposition ont changé. Fermez puis actualisez le briefing.'); setCopying(false); return }
    }
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable')
      await navigator.clipboard.writeText(text)
      if (textareaRef.current && currentLanguage.current === language) setCopyMessage(textareaRef.current.value === text ? 'Texte copié.' : 'La version précédente du texte a été copiée.')
    } catch {
      const textarea = textareaRef.current
      if (!textarea || currentLanguage.current !== language) return
      textarea.focus(); textarea.select()
      let copied = false
      try { copied = typeof document.execCommand === 'function' && document.execCommand('copy') } catch { /* Manual selection remains available. */ }
      setCopyMessage(copied ? 'Texte copié.' : 'Copie automatique indisponible. Le texte est sélectionné : copiez-le manuellement.')
    } finally { if (textareaRef.current) setCopying(false) }
  }

  return createPortal(<div className="client-modal-overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose() }}>
    <section className="client-package-dialog briefing-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={dialogRef}>
      <header className="client-modal-header"><div className="client-modal-title"><span><MessageSquareText aria-hidden="true" /></span><div><p className="eyebrow">Message au livreur</p><h2 id={titleId}>Briefing de {courierName}</h2></div></div><button type="button" className="admin-icon-button briefing-close" aria-label="Fermer le briefing texte" onClick={onClose}><X aria-hidden="true" /></button></header>
      <div className="briefing-dialog__body"><p>Modifiez puis copiez la version de votre choix. Les textes français et gasy restent indépendants, sans modifier le dispatch.</p>
        <div className="admin-segmented briefing-language-tabs" role="tablist" aria-label="Langue du briefing">
          {(['fr', 'mg'] as const).map((code) => <button key={code} id={`${titleId}-${code}`} type="button" role="tab" aria-label={code === 'fr' ? 'Français' : 'Malagasy'} aria-selected={language === code} aria-controls={`${titleId}-panel`} tabIndex={language === code ? 0 : -1} className={language === code ? 'is-active' : undefined} onClick={() => onLanguageChange(code)} onKeyDown={(event) => {
            if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
            event.preventDefault()
            const next = event.key === 'Home' ? 'fr' : event.key === 'End' ? 'mg' : code === 'fr' ? 'mg' : 'fr'
            onLanguageChange(next)
            document.getElementById(`${titleId}-${next}`)?.focus()
          }}>{code.toUpperCase()}</button>)}
        </div>
        <div id={`${titleId}-panel`} className="briefing-language-panel" role="tabpanel" aria-labelledby={`${titleId}-${language}`}>
          <label htmlFor={`${titleId}-text`}>Texte du briefing</label><textarea id={`${titleId}-text`} ref={textareaRef} lang={language} value={text} onChange={(event) => { setCopyMessage(undefined); onChange(event.target.value) }} />
        </div>
        <div className="briefing-dialog__status"><small>{Array.from(text).length} caractères</small>{copyMessage && <span role="status">{copyMessage}</span>}</div>
      </div>
      <footer className="client-modal-footer"><Button variant="secondary" onClick={onClose}>Fermer</Button><Button disabled={copying || !text.trim()} onClick={() => void copy()}>{copying ? <LoaderCircle className="spin" aria-hidden="true" /> : copyMessage === 'Texte copié.' ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}{copying ? 'Copie…' : 'Copier le texte'}</Button></footer>
    </section>
  </div>, document.body)
}

/** Also reused for the read-only review immediately before validating a manual dispatch. */
export function DispatchBriefingView({ draft, allowMessages = false, sellerContacts, cashBatch, validateCash }: {
  draft: ManualDispatchDraft
  allowMessages?: boolean
  sellerContacts?: Readonly<Record<string, string | null | undefined>>
  cashBatch?: CashBriefingBatch
  validateCash?: () => Promise<boolean>
}) {
  const model = useMemo(() => buildDispatchBriefing(draft), [draft])
  const couriers = useMemo(() => [...model.couriers].sort((a, b) => a.courier.name.localeCompare(b.courier.name, 'fr') || a.courier.courierId.localeCompare(b.courier.courierId)), [model.couriers])
  const [view, setView] = useState<'overview' | 'courier'>('overview')
  const [courierId, setCourierId] = useState(couriers[0]?.courier.courierId ?? '')
  const [editing, setEditing] = useState<string>()
  const [language, setLanguage] = useState<BriefingLanguage>('fr')
  const [texts, setTexts] = useState<Record<string, Record<BriefingLanguage, string>>>({})
  const cashVersion = cashSignature(cashBatch)
  useEffect(() => { setTexts({}); setEditing(undefined) }, [cashVersion])
  const closeEditor = useCallback(() => setEditing(undefined), [])
  const selected = couriers.find((item) => item.courier.courierId === courierId) ?? couriers[0]
  const selectedIndex = selected ? couriers.indexOf(selected) : -1
  const edited = model.couriers.find((item) => item.courier.courierId === editing)
  const canGenerate = allowMessages && draft.status === 'FINALIZED' && model.warnings.length === 0 && cashBatch?.status !== 'STALE'
  function openText(briefing: CourierBriefing) {
    if (validateCash) { void validateCash().then((valid) => { if (valid) openValidatedText(briefing) }); return }
    openValidatedText(briefing)
  }
  function openValidatedText(briefing: CourierBriefing) {
    const id = briefing.courier.courierId
    setTexts((current) => current[id] === undefined ? { ...current, [id]: {
      fr: generateCourierBriefing(briefing, draft.serviceDate, sellerContacts, 'fr', cashBatch?.status === 'ACTIVE' ? cashBatch.transfers : []),
      mg: generateCourierBriefing(briefing, draft.serviceDate, sellerContacts, 'mg', cashBatch?.status === 'ACTIVE' ? cashBatch.transfers : []),
    } } : current)
    setEditing(id)
  }
  const collectionCouriers = model.couriers.filter((item) => item.pickups.length > 0)
  const deliveryCouriers = model.couriers.filter((item) => item.deliveries.length > 0)
  const payouts = cashBatch?.status === 'ACTIVE' ? cashBatch.transfers.filter((transfer) => transfer.status === 'PENDING') : []
  const payoutCouriers = couriers.filter((item) => payouts.some((transfer) => transfer.courierAccountId === item.courier.courierId))

  return <div className="briefing-content">
    {model.warnings.length > 0 && <div className="admin-callout admin-callout--warning" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Répartition à vérifier</strong><ul>{model.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul></div></div>}
    <div className="briefing-viewbar"><div className="admin-segmented" role="group" aria-label="Vue du briefing"><button type="button" className={view === 'overview' ? 'is-active' : undefined} aria-pressed={view === 'overview'} onClick={() => setView('overview')}><List aria-hidden="true" />Vue d’ensemble</button><button type="button" className={view === 'courier' ? 'is-active' : undefined} aria-pressed={view === 'courier'} onClick={() => setView('courier')}><Bike aria-hidden="true" />Par livreur</button></div><p>{draft.pickups.length} récupérations · {draft.packages.length} colis · {model.couriers.length} livreurs</p></div>
    {view === 'courier' && <div className="briefing-courier-toolbar"><div className="briefing-courier-picker"><label htmlFor="briefing-courier">Livreur</label><CourierCombobox inputId="briefing-courier" ariaLabel="Livreur du briefing" value={selected?.courier.courierId ?? ''} couriers={couriers.map(({ courier }) => ({ id: courier.courierId, name: courier.name, contact: courier.contact, active: true }))} onChange={setCourierId} /></div>
      <nav className="briefing-courier-navigation" aria-label="Parcourir les livreurs"><span aria-live="polite" aria-atomic="true">{selectedIndex + 1} / {couriers.length}</span><Button tone="admin" variant="secondary" aria-label="Livreur précédent" title="Livreur précédent" disabled={selectedIndex <= 0} onClick={() => setCourierId(couriers[selectedIndex - 1].courier.courierId)}><ChevronLeft aria-hidden="true" /></Button><Button tone="admin" variant="secondary" aria-label="Livreur suivant" title="Livreur suivant" disabled={selectedIndex < 0 || selectedIndex >= couriers.length - 1} onClick={() => setCourierId(couriers[selectedIndex + 1].courier.courierId)}><ChevronRight aria-hidden="true" /></Button></nav>
    </div>}
    {view === 'courier' && selected ? <div className="briefing-personal">
      <div className="briefing-personal__heading"><div><p className="admin-eyebrow">Tournée du {formatDate(draft.serviceDate)}</p><h3>{selected.courier.name}</h3><p><MapPin aria-hidden="true" /><span>Départ : {dispatchPlaceText(selected.courier.departure)} · {formatTime(selected.courier.departureAt)}{selected.courier.contact ? ` · ${selected.courier.contact}` : ''}</span></p></div>{canGenerate && <Button tone="admin" onClick={() => openText(selected)}><MessageSquareText aria-hidden="true" />Générer le briefing</Button>}</div>
      <BriefingSection number={1} title="Récupérations" icon={<Warehouse aria-hidden="true" />}>{selected.pickups.length ? <PickupList pickups={selected.pickups} /> : <p className="briefing-empty">Aucune récupération pour ce livreur.</p>}</BriefingSection>
      <BriefingSection number={2} title="Échanges" icon={<ArrowRightLeft aria-hidden="true" />}>
        {selected.give.length + selected.take.length > 0 ? <div className="briefing-exchange-grid">{(['give', 'take'] as const).flatMap((direction) => selected[direction].map((exchange) => <article key={`${direction}:${exchange.courierId}`} className="briefing-exchange"><header><span>{direction === 'give' ? 'Donner à' : 'Prendre chez'}</span><strong>{exchange.courierName}</strong></header><PackageList packages={exchange.packages} /></article>))}</div> : <p className="briefing-empty">Aucun échange : ce livreur conserve ses colis.</p>}
      </BriefingSection>
      <BriefingSection number={3} title="Colis à livrer" icon={<PackageCheck aria-hidden="true" />}>{selected.deliveries.length ? <PackageList packages={selected.deliveries} financial /> : <p className="briefing-empty">Aucun colis à livrer pour ce livreur.</p>}</BriefingSection>
    </div> : <>
      <BriefingSection number={1} title="Récupérations" icon={<Warehouse aria-hidden="true" />}><div className="briefing-courier-grid">{collectionCouriers.map((briefing) => <article key={briefing.courier.courierId} className="briefing-courier-card"><CourierHeading briefing={briefing} /><PickupList pickups={briefing.pickups} /></article>)}</div>{collectionCouriers.length === 0 && <p className="briefing-empty">Aucune récupération affectée.</p>}</BriefingSection>
      <BriefingSection number={2} title="Échanges" icon={<ArrowRightLeft aria-hidden="true" />}>{model.transfers.length ? <div className="briefing-exchange-grid">{model.transfers.map((transfer) => <article key={`${transfer.fromCourierId}:${transfer.toCourierId}`} className="briefing-exchange"><header><strong>{transfer.fromCourierName} → {transfer.toCourierName}</strong><small>{transfer.packages.length} colis à transmettre</small></header><PackageList packages={transfer.packages} /></article>)}</div> : <p className="briefing-empty">Aucun échange : chaque livreur conserve les colis qu’il récupère.</p>}</BriefingSection>
      <BriefingSection number={3} title="Colis à livrer" icon={<PackageCheck aria-hidden="true" />}><div className="briefing-courier-grid">{deliveryCouriers.map((briefing) => <article key={briefing.courier.courierId} className="briefing-courier-card"><CourierHeading briefing={briefing} /><PackageList packages={briefing.deliveries} financial /></article>)}</div>{deliveryCouriers.length === 0 && <p className="briefing-empty">Aucun colis affecté à la livraison.</p>}</BriefingSection>
    </>}
    {allowMessages && <BriefingSection number={4} title="Versements à faire" icon={<Banknote aria-hidden="true" />}>
      {view === 'courier' && selected ? <PayoutList transfers={payouts.filter((transfer) => transfer.courierAccountId === selected.courier.courierId)} stale={cashBatch?.status === 'STALE'} />
        : cashBatch?.status === 'STALE' ? <PayoutList transfers={[]} stale />
          : payoutCouriers.length ? <div className="briefing-courier-grid">{payoutCouriers.map((briefing) => <article key={briefing.courier.courierId} className="briefing-courier-card"><CourierHeading briefing={briefing} /><PayoutList transfers={payouts.filter((transfer) => transfer.courierAccountId === briefing.courier.courierId)} /></article>)}</div>
            : <PayoutList transfers={[]} />}
    </BriefingSection>}
    {canGenerate && edited && <BriefingTextDialog courierName={edited.courier.name} language={language} onLanguageChange={setLanguage} text={texts[edited.courier.courierId]?.[language] ?? ''} onChange={(value) => setTexts((current) => ({ ...current, [edited.courier.courierId]: { ...current[edited.courier.courierId], [language]: value } }))} onClose={closeEditor} beforeCopy={validateCash} />}
  </div>
}

export function AdminBriefingPanel({ accessToken, date, history, busy = false, onDateChange, onOpenDispatch, onOpenPayouts, onRefresh }: AdminBriefingPanelProps) {
  const [cashBatch, setCashBatch] = useState<CashBriefingBatch>()
  const [cashMessage, setCashMessage] = useState<string>()
  const [cashReady, setCashReady] = useState(false)
  const [snapshot, setSnapshot] = useState<{ planId: string; draft: ManualDispatchDraft; sellerContacts?: Readonly<Record<string, string | null>> }>()
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState<string>()
  const [reload, setReload] = useState(0)
  const active = [...history].filter((plan) => plan.serviceDate === date && !plan.archivedAt && (plan.status === 'READY' || plan.status === 'READY_WITH_WARNINGS'))
    .sort((a, b) => (b.revision ?? 0) - (a.revision ?? 0) || b.createdAt.localeCompare(a.createdAt))[0]
  const planId = active?.id
  const version = active?.version
  async function validateCash() {
    if (!planId) return false
    try {
      const batches = await listCashBriefings(accessToken, planId)
      const current = batches.find((b) => ['ACTIVE', 'STALE', 'COMPLETED'].includes(b.status))
      if (current?.status === 'STALE' || cashSignature(current) !== cashSignature(cashBatch)) {
        setCashBatch(current); setCashMessage('Les caisses ou les versements ont changé. Actualisez puis rouvrez le texte ; les versions FR et MG seront régénérées.'); return false
      }
      return true
    } catch (error) { setCashMessage(errorMessage(error)); return false }
  }

  useEffect(() => {
    const controller = new AbortController()
    setSnapshot(undefined); setMessage(undefined)
    setCashBatch(undefined); setCashMessage(undefined); setCashReady(false)
    if (!planId) { setLoading(false); return () => controller.abort() }
    setLoading(true)
    void (async () => {
      const plan = await getDispatchPlan(planId, accessToken, controller.signal)
      if (controller.signal.aborted) return
      if (plan.plan.serviceDate !== date || plan.plan.archivedAt || !['READY', 'READY_WITH_WARNINGS'].includes(plan.plan.status)) throw new Error('Ce dispatch n’est plus actif. Actualisez pour consulter la répartition en cours.')
      if (plan.plan.strategy !== 'MANUAL') throw new Error('Ce plan ancien ne possède pas de répartition manuelle. Consultez ses détails dans Dispatch.')
      const [draft, clients, batches] = await Promise.all([
        getManualDraftForPlan(planId, accessToken, controller.signal),
        // Contacts serve the text generator only; unavailable profiles must not hide the dispatch.
        listAdminClients(accessToken, controller.signal).catch(() => undefined),
        listCashBriefings(accessToken, planId, controller.signal).catch((error: unknown) => {
          if (!controller.signal.aborted) setCashMessage(errorMessage(error))
          return undefined
        }),
      ])
      if (controller.signal.aborted) return
      if (!draft || draft.serviceDate !== date || draft.status !== 'FINALIZED' || (draft.finalPlanId && draft.finalPlanId !== planId)) throw new Error('Le briefing de ce plan n’est pas disponible. Consultez Dispatch ou actualisez la journée.')
      setSnapshot({ planId, draft, sellerContacts: clients ? Object.fromEntries(clients.map((client) => [client.accountId, client.phone ?? null])) : undefined })
      setCashBatch(batches?.find((batch) => ['ACTIVE', 'STALE', 'COMPLETED'].includes(batch.status)))
      setCashReady(batches !== undefined)
    })().catch((error: unknown) => { if (!controller.signal.aborted) setMessage(errorMessage(error)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [accessToken, date, planId, version, reload])

  const ready = !busy && !loading && snapshot?.planId === planId ? snapshot?.draft : undefined
  return <section className="admin-panel admin-briefing" aria-labelledby="briefing-title">
    <header className="admin-panel__heading"><div><p className="admin-eyebrow">Organisation de la journée</p><h2 id="briefing-title">Briefing</h2><p>Les récupérations, les échanges, les colis et les versements à faire par livreur.</p></div><div className="admin-toolbar"><label className="admin-date-field"><CalendarDays aria-hidden="true" /><span>Date d’activité</span><input type="date" required value={date} onChange={(event) => { if (event.target.value) onDateChange(event.target.value) }} /></label>{onOpenPayouts && <Button tone="admin" variant="secondary" onClick={onOpenPayouts}><Banknote aria-hidden="true" />Modifier les versements</Button>}<Button tone="admin" variant="secondary" disabled={busy || loading} onClick={() => { setReload((value) => value + 1); onRefresh?.() }}><RefreshCw aria-hidden="true" />Actualiser</Button></div></header>
    {busy || loading ? <div className="admin-empty" role="status"><LoaderCircle className="admin-spin" aria-hidden="true" /><strong>Chargement du briefing…</strong></div> : message ? <div className="admin-callout admin-callout--warning" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Briefing indisponible</strong><span>{message}</span><Button tone="admin" variant="secondary" size="small" onClick={onOpenDispatch}>Ouvrir Dispatch</Button></div></div> : ready ? <>{cashMessage && <p role="alert" className="admin-callout admin-callout--warning">{cashMessage}</p>}<DispatchBriefingView key={`${planId}:${ready.id}:${ready.version}`} draft={ready} allowMessages={cashReady} sellerContacts={snapshot?.sellerContacts} cashBatch={cashBatch} validateCash={validateCash} /></> : <div className="admin-empty"><MessageSquareText aria-hidden="true" /><strong>Aucun dispatch validé pour le {formatDate(date)}</strong><span>Validez d’abord les affectations de cette journée pour préparer les briefings.</span><Button tone="admin" onClick={onOpenDispatch}>Ouvrir Dispatch<ArrowRight aria-hidden="true" /></Button></div>}
  </section>
}
