import { useEffect, useId, useMemo, useRef, useState, type DragEvent } from 'react'
import { ArrowDown, ArrowRightLeft, ArrowUp, Check, Clock3, GripVertical, List, LoaderCircle, Map as MapIcon, Save, Trash2, Undo2, WandSparkles, X } from 'lucide-react'
import type { DispatchPlan } from '../../api/admin'
import { cancelManualDraft, finalizeManualDraft, getActiveManualDraft, saveManualDraft, type ManualDispatchDraft, type ManualDispatchStep } from '../../api/manualDispatch'
import { LocationPicker } from '../LocationPicker'
import { CourierCombobox } from './CourierCombobox'
import { ManualDispatchMap } from './ManualDispatchMap'
import { asConfirmedLocation, errorMessage, formatAriary, formatDate, vehicleLabel } from './adminHelpers'
import { dispatchPlace, dispatchPlaceText as locationLabel } from './manualDispatchPresentation'
import { assignmentIds, assignPoint, changeManualStep, manualTransfers, packageCollector } from './manualDispatchLayout'
import { groupByGeography, manualDispatchCourierColor } from './manualDispatchGeography'
import { automaticallyAssignPhase } from './manualDispatchAutomatic'
import './ManualDispatchWorkspace.css'

export function ManualDispatchReview({ draft }: { draft: ManualDispatchDraft }) {
  const transfers = manualTransfers(draft)
  const names = new Map(draft.couriers.map((c) => [c.courierId, c.name]))
  return <div className="manual-review">
    <section className="manual-transfers">
      <h3><ArrowRightLeft aria-hidden="true" />Échanges à prévoir</h3>
      {transfers.length ? <ul>{transfers.map((transfer) => <li key={transfer.package.packageId}>
        <strong>{names.get(transfer.from)} → {names.get(transfer.to)}</strong>
        <span>{transfer.package.recipientName} · {locationLabel(transfer.package.destination)}</span>
        <small>{transfer.package.serviceClientName}</small>
      </li>)}</ul> : <p className="admin-muted">Chaque livreur conserve les colis qu’il récupère.</p>}
    </section>
    <div className="manual-review__couriers">{draft.couriers.map((courier) => {
      const collections = assignmentIds(draft, 'COLLECTION', courier.courierId)
      const deliveries = assignmentIds(draft, 'DELIVERY', courier.courierId)
      return <article key={courier.courierId} style={{ borderTopColor: manualDispatchCourierColor(courier.courierId) }}>
        <h3>{courier.name}</h3><p className="admin-muted">Départ : {locationLabel(courier.departure)} · {courier.departureAt.slice(11, 16)}</p>
        <h4>Récupérations · {collections.length}</h4>
        {collections.length ? <ol>{collections.map((id) => {
          const pickup = draft.pickups.find((p) => p.id === id)!
          const clients = [...new Set(draft.packages.filter((p) => pickup.packageIds.includes(p.packageId)).map((p) => p.serviceClientName))]
          const place = dispatchPlace(pickup.location)
          return <li key={id}><strong>{place.title}</strong>{place.address && <span>{place.address}</span>}<span>{pickup.packageIds.length} colis · {clients.join(', ')}</span></li>
        })}</ol> : <p className="admin-muted">Aucune récupération</p>}
        <h4>Livraisons · {deliveries.length}</h4>
        {deliveries.length ? <ol>{deliveries.map((id) => {
          const p = draft.packages.find((item) => item.packageId === id)!
          return <li key={id}><strong>{p.recipientName}</strong><span>{locationLabel(p.destination)}</span><small>{p.serviceClientName}{p.contact ? ` · ${p.contact}` : ''}{p.desiredDeliveryTime ? ` · ${p.desiredDeliveryTime.slice(0, 5)}` : ''}</small></li>
        })}</ol> : <p className="admin-muted">Aucune livraison</p>}
      </article>
    })}</div>
  </div>
}

export function ManualDispatchWorkspace({ accessToken, draft, onDraftChange, onFinalized, onCancelled, onClose }: {
  accessToken: string
  draft: ManualDispatchDraft
  onDraftChange: (draft: ManualDispatchDraft) => void
  onFinalized: (plan: DispatchPlan) => void
  onCancelled: () => void
  onClose?: () => void
}) {
  const headingId = useId()
  const [working, setWorking] = useState(draft)
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string>()
  const [view, setView] = useState<'list' | 'map'>('list')
  const [selected, setSelected] = useState<string>()
  const [dropTarget, setDropTarget] = useState<string>()
  const [conflict, setConflict] = useState(false)
  const [saveFailed, setSaveFailed] = useState(false)
  const [automating, setAutomating] = useState(false)
  const [automaticUndo, setAutomaticUndo] = useState<Pick<ManualDispatchDraft, 'collections' | 'deliveries'> & { phase: 'COLLECTION' | 'DELIVERY' }>()
  const automaticAbort = useRef<AbortController | null>(null)
  const busyRef = useRef(false)
  const dirtyRef = useRef(false)
  const workingRef = useRef(working)
  workingRef.current = working
  const phase = working.step === 'COLLECTION' ? 'COLLECTION' : 'DELIVERY'
  const editable = !busy && !automating && !conflict
  const missingPickups = working.pickups.filter((p) => !working.collections.some((c) => c.pickupId === p.id)).length
  const missingPackages = working.packages.filter((p) => !working.deliveries.some((d) => d.packageId === p.packageId)).length
  const courierOptions = working.couriers.map((c) => ({ id: c.courierId, name: c.name, contact: c.contact, active: true }))
  const groups = useMemo(() => groupByGeography(phase === 'COLLECTION'
    ? working.pickups.map((p) => ({ id: p.id, location: p.location }))
    : working.packages.map((p) => ({ id: p.packageId, location: p.destination })), working.hub), [working.pickups, working.packages, working.hub, phase])

  useEffect(() => {
    if (!dirty) return
    const listener = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', listener)
    return () => window.removeEventListener('beforeunload', listener)
  }, [dirty])

  useEffect(() => () => { automaticAbort.current?.abort() }, [])

  useEffect(() => () => {
    // Flush the debounce when switching to another portal section.
    if (!dirtyRef.current || busyRef.current) return
    const current = workingRef.current
    void saveManualDraft(current.id, current.version, { step: current.step, couriers: current.couriers,
      collections: current.collections, deliveries: current.deliveries }, accessToken).catch(() => {
      // A failed flush never overwrites a newer server version; the saved draft remains available.
    })
  }, [accessToken])

  function change(next: ManualDispatchDraft) {
    if (busyRef.current || automaticAbort.current || conflict) return
    workingRef.current = next; dirtyRef.current = true
    setWorking(next); setDirty(true); setMessage(undefined); setSaveFailed(false)
    setAutomaticUndo(undefined)
  }

  async function persist() {
    const current = workingRef.current
    const saved = await saveManualDraft(current.id, current.version, {
      step: current.step, couriers: current.couriers, collections: current.collections, deliveries: current.deliveries,
    }, accessToken)
    workingRef.current = saved
    dirtyRef.current = false
    setWorking(saved); setDirty(false); onDraftChange(saved)
    return saved
  }

  async function save(finalize = false) {
    if (busyRef.current || automaticAbort.current || conflict) return
    busyRef.current = true; setBusy(true); setMessage(undefined)
    try {
      const current = dirty ? await persist() : workingRef.current
      if (finalize) onFinalized(await finalizeManualDraft(current.id, current.version, accessToken))
    } catch (error) {
      setMessage(errorMessage(error))
      setSaveFailed(true)
      if (errorMessage(error).includes('Rechargez')) setConflict(true)
    } finally { busyRef.current = false; setBusy(false) }
  }

  // Keep navigation safe while also offering an explicit save action.
  useEffect(() => {
    if (!dirty || busy || automating || conflict || saveFailed) return
    const timer = window.setTimeout(() => { void save() }, 1200)
    return () => window.clearTimeout(timer)
  })

  async function reload() {
    if (!window.confirm('Recharger la version enregistrée et remplacer les modifications à l’écran ?')) return
    try {
      const saved = await getActiveManualDraft(working.serviceDate, accessToken)
      if (!saved) { onCancelled(); return }
      workingRef.current = saved; dirtyRef.current = false
      setWorking(saved); setDirty(false); setConflict(false); setMessage(undefined); onDraftChange(saved)
    } catch (error) { setMessage(errorMessage(error)) }
  }

  async function abandon() {
    if (busyRef.current || !window.confirm('Abandonner ce brouillon et rouvrir les demandes pour cette date ?')) return
    busyRef.current = true; setBusy(true)
    try { await cancelManualDraft(working.id, working.version, accessToken); dirtyRef.current = false; onCancelled() }
    catch (error) { setMessage(errorMessage(error)) }
    finally { busyRef.current = false; setBusy(false) }
  }

  async function close() {
    if (busyRef.current || conflict) return
    busyRef.current = true; setBusy(true)
    try { if (dirty) await persist(); onClose?.() }
    catch (error) { setMessage(errorMessage(error)); setSaveFailed(true) }
    finally { busyRef.current = false; setBusy(false) }
  }

  function go(step: ManualDispatchStep) {
    if ((step !== 'COLLECTION' && missingPickups) || (step === 'REVIEW' && missingPackages)) return
    change(changeManualStep(working, step)); setSelected(undefined)
  }

  function assign(id: string, courier: string, before?: string) { change(assignPoint(working, phase, id, courier, before)) }
  async function autoAssign() {
    if (!editable || busyRef.current || automaticAbort.current || working.step === 'REVIEW') return
    const current = workingRef.current
    const assignments = phase === 'COLLECTION' ? current.collections : current.deliveries
    if (assignments.length && !window.confirm(phase === 'COLLECTION'
      ? 'Remplacer les affectations de récupération par une proposition automatique ? Les livraisons resteront inchangées et vous pourrez annuler cette proposition.'
      : 'Remplacer les affectations de livraison par une proposition automatique ? Les récupérations resteront inchangées et vous pourrez annuler cette proposition.')) return
    const controller = new AbortController()
    automaticAbort.current = controller; setAutomating(true); setMessage(undefined)
    try {
      const next = await automaticallyAssignPhase(current, phase, accessToken, controller.signal)
      if (controller.signal.aborted || workingRef.current !== current) return
      automaticAbort.current = null
      change(next)
      setAutomaticUndo({ phase, collections: current.collections, deliveries: current.deliveries })
      setSelected(undefined)
    } catch (error) {
      if (!controller.signal.aborted) setMessage(errorMessage(error))
    } finally {
      if (automaticAbort.current === controller) automaticAbort.current = null
      if (!controller.signal.aborted) setAutomating(false)
    }
  }
  function undoAutomatic() {
    if (!editable || !automaticUndo) return
    change(automaticUndo.phase === 'COLLECTION'
      ? { ...workingRef.current, collections: automaticUndo.collections }
      : { ...workingRef.current, deliveries: automaticUndo.deliveries })
  }
  function drop(event: DragEvent, courier: string, before?: string) {
    event.preventDefault(); event.stopPropagation(); setDropTarget(undefined)
    const prefix = phase === 'COLLECTION' ? 'pickup:' : 'package:'
    const text = event.dataTransfer.getData('text/plain')
    if (text.startsWith(prefix)) assign(text.slice(prefix.length), courier, before)
  }

  function card(id: string, lane?: string, expanded = selected === id) {
    const pickup = phase === 'COLLECTION' ? working.pickups.find((p) => p.id === id) : undefined
    const pkg = phase === 'DELIVERY' ? working.packages.find((p) => p.packageId === id) : undefined
    if (!pickup && !pkg) return null
    const packages = pickup ? working.packages.filter((p) => pickup.packageIds.includes(p.packageId)) : [pkg!]
    const place = dispatchPlace(pickup?.location ?? pkg!.destination)
    const title = pickup ? place.title : pkg!.recipientName
    const clients = [...new Set(packages.map((p) => p.serviceClientName))]
    const mapPickupSummary = !!pickup && view === 'map' && !lane
    const showDetails = expanded && !mapPickupSummary
    const assigned = pickup ? working.collections.find((c) => c.pickupId === id)?.courierId : working.deliveries.find((d) => d.packageId === id)?.courierId
    const ids = lane ? assignmentIds(working, phase, lane) : []
    const index = ids.indexOf(id)
    const collector = pkg ? working.couriers.find((c) => c.courierId === packageCollector(working, id)) : undefined
    return <article key={id} className={`manual-card${selected === id ? ' is-selected' : ''}`} draggable={editable}
      title={pickup ? `${locationLabel(pickup.location)} · ${clients.join(', ')} · ${packages.length} colis`
        : `${pkg!.recipientName} · ${pkg!.serviceClientName} · ${locationLabel(pkg!.destination)}`}
      onDragStart={(event) => {
        event.dataTransfer.setData('text/plain', `${phase === 'COLLECTION' ? 'pickup:' : 'package:'}${id}`)
        event.dataTransfer.effectAllowed = 'move'; setSelected(id)
      }} onDrop={lane ? (event) => drop(event, lane, id) : undefined}
      onDragOver={lane ? (event) => { event.preventDefault(); event.stopPropagation() } : undefined}>
      <button type="button" className="manual-card__main" onClick={() => setSelected(mapPickupSummary ? id : expanded ? undefined : id)} aria-expanded={showDetails}>
        {lane && <span className="manual-order">{index + 1}</span>}
        <span><strong>{title}</strong>{!pickup && <small>{place.title}</small>}{place.address && <small>{place.address}</small>}
          <small>{pickup ? `${packages.length} colis · ${clients.join(', ')}` : pkg!.serviceClientName}</small>
          {collector && <small>Récupération : {collector.name}</small>}</span><GripVertical aria-hidden="true" />
      </button>
      {showDetails && <div className="manual-card__details">{packages.map((p) => <div key={p.packageId}>
        <strong>{p.recipientName}</strong><span>{locationLabel(p.destination)}</span>
        <small>{p.contact} · {p.effectiveWeightKg} kg{p.desiredDeliveryTime ? ` · ${p.desiredDeliveryTime.slice(0, 5)}` : ''}</small>
        <small>À récupérer : {formatAriary(p.amountToCollectAriary)}</small>
      </div>)}</div>}
      <div className="manual-card__actions">
        <CourierCombobox couriers={courierOptions} value={assigned ?? ''} onChange={(courier) => assign(id, courier)} ariaLabel={`Affecter ${title}`} />
        {lane && <div className="manual-order-actions">
          <button type="button" className="admin-icon-button" aria-label={`Monter ${title}`} disabled={!editable || index === 0}
            onClick={() => assign(id, lane, ids[index - 1])}><ArrowUp /></button>
          <button type="button" className="admin-icon-button" aria-label={`Descendre ${title}`} disabled={!editable || index === ids.length - 1}
            onClick={() => assign(id, lane, ids[index + 2])}><ArrowDown /></button>
        </div>}
      </div>
    </article>
  }

  return <section className="admin-panel manual-workspace" aria-labelledby={headingId} aria-busy={busy || automating}>
    <header className="manual-workspace__header"><div><p className="admin-eyebrow">Dispatch manuel · {formatDate(working.serviceDate)}</p>
      <h2 id={headingId}>Organiser la journée</h2><p className="admin-muted">{working.packages.length} colis · {working.pickups.length} points de récupération · {working.couriers.length} livreurs</p></div>
      <div className="admin-toolbar"><small role="status">{automating ? 'Recherche d’une répartition…' : busy ? 'Enregistrement…' : dirty ? 'Modifications en cours…' : 'Brouillon enregistré'}</small>
        <button className="admin-button admin-button--ghost" type="button" disabled={!editable || !dirty} onClick={() => void save()}><Save />Enregistrer</button>
        {onClose && <button className="admin-button admin-button--ghost" type="button" disabled={!editable} onClick={() => void close()}><X />Fermer</button>}</div>
    </header>
    <nav className="admin-segmented manual-steps" aria-label="Étapes du dispatch">{([
      ['COLLECTION', 'Récupérations'], ['DELIVERY', 'Livraisons'], ['REVIEW', 'Vérification'],
    ] as const).map(([step, label], i) => <button type="button" key={step} className={working.step === step ? 'is-active' : undefined}
      aria-current={working.step === step ? 'step' : undefined} disabled={!editable || (step !== 'COLLECTION' && missingPickups > 0) || (step === 'REVIEW' && missingPackages > 0)}
      onClick={() => go(step)}><span>{i + 1}</span>{label}</button>)}</nav>
    {message && <div className="admin-callout admin-callout--error" role="alert"><span>{message}</span>{conflict && <button type="button" className="admin-button admin-button--ghost" onClick={() => void reload()}>Recharger le brouillon</button>}</div>}
    <details className="manual-departures"><summary>Départs des livreurs <small>Ralliement de référence : {locationLabel(working.hub)}</small></summary>
      <fieldset disabled={!editable}>{working.couriers.map((courier) => <div key={courier.courierId}><h3>{courier.name}</h3>
        <label><span>Heure de départ</span><span className="manual-departure-time"><Clock3 aria-hidden="true" />
          <input type="time" step={60} aria-label={`Heure de départ de ${courier.name}`} value={courier.departureAt.slice(11, 16)} onChange={(event) => {
          if (event.target.value) change({ ...working, couriers: working.couriers.map((c) => c.courierId === courier.courierId
            ? { ...c, departureAt: `${working.serviceDate}T${event.target.value}:00+03:00` } : c) })
        }} /></span></label>
        <LocationPicker accessToken={accessToken} value={courier.departure} title={`Départ de ${courier.name}`} description="Point de départ pour cette journée."
          onChange={(location) => {
            const confirmed = asConfirmedLocation(location)
            if (confirmed) change({ ...working, couriers: working.couriers.map((c) => c.courierId === courier.courierId ? { ...c, departure: confirmed } : c) })
          }} />
      </div>)}</fieldset>
    </details>
    {working.step === 'DELIVERY' && <details className="manual-inventory" open><summary>Colis récupérés par livreur</summary>
      <div>{working.couriers.map((courier) => {
        const items = working.packages.filter((p) => packageCollector(working, p.packageId) === courier.courierId)
        return <section key={courier.courierId}><h3>{courier.name} <small>{items.length} colis</small></h3>
          {items.length ? <ul>{items.map((p) => <li key={p.packageId}><strong>{p.recipientName}</strong><span>{locationLabel(p.destination)}</span><small>{p.serviceClientName}</small></li>)}</ul> : <p className="admin-muted">Aucun colis récupéré</p>}</section>
      })}</div>
    </details>}
    {working.step === 'REVIEW' ? <ManualDispatchReview draft={working} /> : <fieldset disabled={!editable} className="manual-board">
      <section className="manual-pool"><header><div><h3>{phase === 'COLLECTION' ? 'Points de récupération' : 'Colis à livrer'}</h3>
        <small>{phase === 'COLLECTION' ? missingPickups : missingPackages} à affecter</small></div>
        <div className="admin-segmented" role="group" aria-label="Vue des points"><button type="button" className={view === 'list' ? 'is-active' : undefined} onClick={() => setView('list')}><List />Liste</button>
          <button type="button" className={view === 'map' ? 'is-active' : undefined} onClick={() => setView('map')}><MapIcon />Carte</button></div></header>
        <div className="manual-pool__body">{view === 'map' ? <><ManualDispatchMap draft={working} phase={phase} selectedId={selected} onSelect={setSelected} />
          {selected ? card(selected, undefined, true) : <p className="admin-muted">Sélectionnez un point pour l’affecter. Les fiches de la carte peuvent être glissées vers un livreur.</p>}</>
          : groups.map((group) => <section key={group.label} className="manual-geographic-group"><h4>{group.label} <small>{group.items.length}</small></h4>{group.items.map((p) => card(p.id))}</section>)}</div>
      </section>
      <section className="manual-lanes"><header><h3>Livreurs en service</h3><p className="admin-muted">Glissez une fiche vers un livreur. L’ordre se règle en déplaçant les fiches ou avec les flèches.</p></header>
        <div className="manual-lanes__body">{working.couriers.map((courier) => {
          const ids = assignmentIds(working, phase, courier.courierId)
          return <section key={courier.courierId} className={`manual-lane${dropTarget === courier.courierId ? ' is-drop-target' : ''}`}
            style={{ borderTopColor: manualDispatchCourierColor(courier.courierId) }}
            onDragOver={(event) => { event.preventDefault(); setDropTarget(courier.courierId) }} onDragLeave={() => setDropTarget(undefined)}
            onDrop={(event) => drop(event, courier.courierId)}><header title={`${courier.name} · ${vehicleLabel(courier.vehicleType)} · ${courier.contact ?? ''} · Départ ${courier.departureAt.slice(11, 16)} · ${locationLabel(courier.departure)}`}><div><h4>{courier.name}</h4><small>{locationLabel(courier.departure)}</small></div>
              <small>{ids.length} {phase === 'COLLECTION' ? 'retraits' : 'colis'}</small></header>
            <div>{ids.map((id) => card(id, courier.courierId))}{!ids.length && <p className="manual-drop-hint">Déposer {phase === 'COLLECTION' ? 'une récupération' : 'un colis'}</p>}</div>
          </section>
        })}</div>
      </section>
    </fieldset>}
    {automaticUndo && <div className="admin-callout manual-automatic-notice" role="status"><span>Répartition proposée. Vous pouvez la modifier librement.</span>
      <button type="button" className="admin-button admin-button--ghost" disabled={!editable} onClick={undoAutomatic}><Undo2 aria-hidden="true" />Annuler la proposition</button></div>}
    <footer className="manual-workspace__footer"><button type="button" className="admin-button admin-button--ghost" aria-label="Abandonner le brouillon" title="Abandonner le brouillon" disabled={!editable} onClick={() => void abandon()}><Trash2 /><span>Abandonner</span></button>
      {working.step !== 'REVIEW' && <button type="button" className="admin-button admin-button--ghost manual-workspace__automatic"
        aria-label={phase === 'COLLECTION' ? 'Répartition automatique des récupérations' : 'Répartition automatique des livraisons'}
        title="Proposer des trajets courts en regroupant les lieux proches, sans tenir compte du poids."
        disabled={!editable || !working.couriers.length || !(phase === 'COLLECTION' ? working.pickups.length : working.packages.length) || (phase === 'DELIVERY' && !!missingPickups)} onClick={() => void autoAssign()}>
        {automating ? <LoaderCircle className="admin-spin" aria-hidden="true" /> : <WandSparkles aria-hidden="true" />}<span>{automating ? 'Calcul en cours…' : 'Répartition automatique'}</span></button>}
      <div className="admin-toolbar">{working.step !== 'COLLECTION' && <button type="button" className="admin-button admin-button--ghost" disabled={!editable} onClick={() => go(working.step === 'REVIEW' ? 'DELIVERY' : 'COLLECTION')}>Retour</button>}
        {working.step === 'COLLECTION' && <button className="admin-button" type="button" disabled={!editable || missingPickups > 0} onClick={() => go('DELIVERY')}>Répartir les colis</button>}
        {working.step === 'DELIVERY' && <button className="admin-button" type="button" disabled={!editable || missingPackages > 0} onClick={() => go('REVIEW')}>Vérifier le dispatch</button>}
        {working.step === 'REVIEW' && <button className="admin-button" type="button" disabled={!editable || !!missingPickups || !!missingPackages} onClick={() => void save(true)}>{busy ? <LoaderCircle className="admin-spin" /> : <Check />}Valider le dispatch</button>}
      </div>
    </footer>
  </section>
}
