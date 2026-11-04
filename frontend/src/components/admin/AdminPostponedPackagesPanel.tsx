import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { CalendarClock, CalendarDays, CheckCircle2, MapPin, RefreshCw, Search, X } from 'lucide-react'
import { carryForwardDeliveryPackage, listPostponedPackages, type PostponedPackage } from '../../api/admin'
import type { Location } from '../../api/csvImports'
import { LocationPicker } from '../LocationPicker'
import { antananarivoToday, errorMessage, formatAriary, formatDate, locationLabel } from './adminHelpers'

function firstTargetDate(sourceDate: string): string {
  const next = new Date(`${sourceDate}T12:00:00Z`)
  next.setUTCDate(next.getUTCDate() + 1)
  const afterSource = next.toISOString().slice(0, 10)
  return [afterSource, antananarivoToday()].sort().at(-1)!
}

function RescheduleDialog({
  item, accessToken, busy, error, onConfirm, onClose,
}: {
  item: PostponedPackage
  accessToken: string
  busy: boolean
  error?: string
  onConfirm: (date: string, pickup: Location) => void
  onClose: () => void
}) {
  const [targetDate, setTargetDate] = useState('')
  const [pickup, setPickup] = useState<Location>(() => ({
    ...item.package.pickup,
    destinationQuery: item.package.pickup.destinationQuery ?? item.package.pickup.query,
  }))
  const minimumDate = firstTargetDate(item.serviceDate)
  const canConfirm = targetDate >= minimumDate
    && Number.isFinite(pickup.latitude) && Number.isFinite(pickup.longitude)

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented && !busy) onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [busy, onClose])

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!busy && canConfirm) onConfirm(targetDate, pickup)
  }

  return (
    <div className="admin-modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <form className="admin-carry-dialog" role="dialog" aria-modal="true" aria-labelledby="reschedule-title" onSubmit={submit}>
        <header>
          <div><p className="admin-eyebrow">Nouvelle programmation</p><h2 id="reschedule-title">Reprogrammer {item.package.recipientName}</h2></div>
          <button type="button" aria-label="Fermer" disabled={busy} onClick={onClose}><X aria-hidden="true" /></button>
        </header>
        <div className="admin-carry-dialog__body">
          <p>Choisissez la date et le lieu où récupérer ce colis. Il conservera son tarif et sa priorité d’origine, et comptera dans la capacité de la date choisie.</p>
          {item.package.requestKind === 'EXPRESS' && <p>Cette livraison express rejoindra les livraisons normales à la date choisie. Son tarif sera conservé.</p>}
          <label className="admin-outcome-field"><span>Date de livraison</span><input autoFocus type="date" required min={minimumDate} value={targetDate} onChange={(event) => setTargetDate(event.target.value)} /></label>
          <label className="admin-outcome-field"><span>Libellé du retrait</span><input value={pickup.label ?? ''} maxLength={200} onChange={(event) => setPickup({ ...pickup, label: event.target.value })} /></label>
          <LocationPicker
            accessToken={accessToken}
            value={pickup}
            title="Nouveau point de retrait"
            description="Confirmez le lieu où le colis sera récupéré à la date choisie."
            onChange={(next) => setPickup({ ...next, label: pickup.label?.trim() || next.label || next.destinationQuery || 'Nouveau retrait' })}
          />
          {error && <p className="admin-form-error" role="alert">{error}</p>}
        </div>
        <footer>
          <button className="admin-button admin-button--ghost" type="button" disabled={busy} onClick={onClose}>Annuler</button>
          <button className="admin-button" type="submit" disabled={busy || !canConfirm}><CalendarClock aria-hidden="true" />{busy ? 'Programmation…' : 'Reprogrammer le colis'}</button>
        </footer>
      </form>
    </div>
  )
}

export function AdminPostponedPackagesPanel({ accessToken, onChanged }: { accessToken: string; onChanged: () => void }) {
  const [items, setItems] = useState<PostponedPackage[]>([])
  const [query, setQuery] = useState('')
  const [appliedQuery, setAppliedQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string>()
  const [success, setSuccess] = useState<string>()
  const [selected, setSelected] = useState<PostponedPackage>()
  const [saving, setSaving] = useState(false)
  const [dialogError, setDialogError] = useState<string>()
  const loadId = useRef(0)

  const load = useCallback(async (signal?: AbortSignal) => {
    const id = ++loadId.current
    setLoading(true)
    setError(undefined)
    try {
      const loaded = await listPostponedPackages(accessToken, appliedQuery, signal)
      if (!signal?.aborted && id === loadId.current) setItems(loaded)
    } catch (loadError) {
      if (!signal?.aborted && id === loadId.current) setError(errorMessage(loadError))
    } finally {
      if (!signal?.aborted && id === loadId.current) setLoading(false)
    }
  }, [accessToken, appliedQuery])

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  async function reschedule(targetDate: string, pickup: Location) {
    if (!selected || !Number.isFinite(pickup.latitude) || !Number.isFinite(pickup.longitude)) return
    setSaving(true)
    setDialogError(undefined)
    try {
      const result = await carryForwardDeliveryPackage(selected.serviceDate, selected.package.packageId, targetDate, {
        label: pickup.label,
        destinationQuery: pickup.destinationQuery,
        latitude: pickup.latitude as number,
        longitude: pickup.longitude as number,
      }, selected.package.version, accessToken)
      setSelected(undefined)
      setSuccess(`${selected.package.recipientName} est reprogrammé pour le ${formatDate(result.requestedDate)}.`)
      setItems((current) => current.filter((item) => item.package.packageId !== selected.package.packageId))
      onChanged()
      await load()
    } catch (saveError) {
      setDialogError(errorMessage(saveError))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="admin-panel" aria-labelledby="postponed-title">
      <header className="admin-panel__heading">
        <div><p className="admin-eyebrow">À reprogrammer</p><h2 id="postponed-title">Colis reportés</h2><p>Après clôture du client ou de la journée, choisissez librement quand reprendre chaque colis.</p></div>
        <button className="admin-icon-button" type="button" disabled={loading} aria-label="Actualiser les colis reportés" onClick={() => void load()}><RefreshCw className={loading ? 'admin-spin' : undefined} aria-hidden="true" /></button>
      </header>

      {success && <div className="admin-callout" role="status"><CheckCircle2 aria-hidden="true" /><div><strong>Colis reprogrammé</strong><span>{success}</span></div></div>}
      {error && <p className="admin-form-error" role="alert">{error}</p>}

      <section className="admin-day-workspace">
        <form className="admin-day-workspace__toolbar admin-postponed-toolbar" onSubmit={(event) => { event.preventDefault(); setAppliedQuery(query.trim()) }}>
          <label><Search aria-hidden="true" /><span className="sr-only">Rechercher un colis reporté</span><input type="search" value={query} maxLength={100} placeholder="Client, destinataire, contact ou lieu" onChange={(event) => setQuery(event.target.value)} /></label>
          <button className="admin-button admin-button--small" type="submit" disabled={loading}><Search aria-hidden="true" />Rechercher</button>
          <strong>{items.length} colis</strong>
        </form>
        <div className="admin-day-package-list" aria-busy={loading}>
          {loading && items.length === 0 ? <div className="admin-empty"><RefreshCw className="admin-spin" aria-hidden="true" /><strong>Chargement des colis reportés…</strong></div> : items.length === 0 ? <div className="admin-empty"><CalendarClock aria-hidden="true" /><strong>{appliedQuery ? 'Aucun colis pour cette recherche' : 'Aucun colis à reprogrammer'}</strong><span>Les colis reportés apparaîtront ici après clôture.</span></div> : items.map((item) => (
            <article className="admin-day-package admin-postponed-package" key={item.package.packageId}>
              <div className="admin-day-package__identity"><span>{item.package.sequenceNumber}</span><div><small>{item.package.ownerDisplayName}{item.package.requestKind === 'EXPRESS' ? ' · Express' : ''}</small><strong>{item.package.recipientName}</strong><em>{item.package.contact}</em></div></div>
              <div><small><CalendarDays aria-hidden="true" />Journée d’origine</small><strong>{formatDate(item.serviceDate)}</strong><em>À récupérer : {locationLabel(item.package.pickup)}</em></div>
              <div><small>Destination</small><strong><MapPin aria-hidden="true" />{locationLabel(item.package.destination)}</strong><em>À encaisser : {formatAriary(item.package.amountToCollectAriary)}</em></div>
              <div className="admin-day-package__actions"><button className="admin-button admin-button--small" type="button" aria-label={`Reprogrammer ${item.package.recipientName}`} onClick={() => { setDialogError(undefined); setSelected(item) }}><CalendarClock aria-hidden="true" />Reprogrammer</button></div>
              {item.package.outcomeNote && <p className="admin-day-package__note">{item.package.outcomeNote}</p>}
            </article>
          ))}
        </div>
      </section>
      {selected && <RescheduleDialog item={selected} accessToken={accessToken} busy={saving} error={dialogError} onClose={() => setSelected(undefined)} onConfirm={(date, pickup) => void reschedule(date, pickup)} />}
    </section>
  )
}
