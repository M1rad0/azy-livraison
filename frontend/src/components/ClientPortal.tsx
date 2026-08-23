import { useEffect, useMemo, useState } from 'react'
import {
  ArrowRight,
  CalendarClock,
  CheckCircle2,
  Clock3,
  History,
  LoaderCircle,
  MapPin,
  PackageCheck,
  PackagePlus,
  PencilLine,
  RefreshCw,
  Save,
  Settings2,
  UserRound,
} from 'lucide-react'
import {
  getClientProfile,
  listDeliveryRequests,
  updateClientProfile,
  type ClientDefaultPickup,
  type ClientProfile,
  type DeliveryPackageStatus,
  type DeliveryRequestRecord,
  type DeliveryRequestStatus,
} from '../api/client'
import type { Location } from '../api/csvImports'
import { ClientDeliveryRequestForm } from './ClientDeliveryRequestForm'
import { LocationPicker } from './LocationPicker'
import './ClientPortal.css'

type ClientView = 'history' | 'new-request' | 'profile'

const REQUEST_STATUS: Record<DeliveryRequestStatus, string> = {
  PENDING: 'En attente',
  PARTIALLY_DISPATCHED: 'Partiellement dispatchée',
  DISPATCHED: 'Dispatchée',
}

const PACKAGE_STATUS: Record<DeliveryPackageStatus, string> = {
  PENDING: 'En attente',
  DISPATCHED: 'Dispatché',
  UNASSIGNED: 'À réorganiser',
}

function formatDate(date: string): string {
  return new Date(`${date}T12:00:00`).toLocaleDateString('fr-MG', {
    weekday: 'short',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  })
}

function formatAriary(value: number): string {
  return `${value.toLocaleString('fr-MG')} Ar`
}

function asPickerLocation(pickup: ClientDefaultPickup | null): Location {
  if (!pickup) return {}
  return {
    label: pickup.label,
    destinationQuery: pickup.destinationQuery,
    latitude: pickup.latitude,
    longitude: pickup.longitude,
  }
}

function RequestHistory({ requests }: { requests: DeliveryRequestRecord[] }) {
  if (requests.length === 0) {
    return (
      <div className="client-history-empty">
        <span aria-hidden="true"><PackagePlus /></span>
        <h2>Votre première livraison commence ici</h2>
        <p>Créez une demande avec un point de retrait et la liste des colis à confier à AZY.</p>
      </div>
    )
  }

  return (
    <div className="client-history-list">
      {requests.map((request) => {
        const pendingPackages = request.packages.filter((item) => item.status === 'PENDING').length
        const cashToCollect = request.packages.reduce(
          (sum, item) => sum + (item.mobileMoneyPaid ? 0 : item.amountToCollectAriary),
          0,
        )
        return (
          <details className="client-history-card" key={request.id}>
            <summary>
              <span className={`client-status client-status--${request.status.toLowerCase()}`}>
                {request.status === 'DISPATCHED' ? <CheckCircle2 aria-hidden="true" /> : <Clock3 aria-hidden="true" />}
                {REQUEST_STATUS[request.status]}
              </span>
              <div className="client-history-card__identity">
                <strong>{request.pickup.label}</strong>
                <small><CalendarClock aria-hidden="true" />{formatDate(request.requestedDate)}</small>
              </div>
              <div className="client-history-card__metric"><small>Colis</small><strong>{request.packages.length}</strong></div>
              <div className="client-history-card__metric"><small>En attente</small><strong>{pendingPackages}</strong></div>
              <div className="client-history-card__metric client-history-card__metric--amount"><small>À encaisser</small><strong>{formatAriary(cashToCollect)}</strong></div>
              <ArrowRight className="client-history-card__chevron" aria-hidden="true" />
            </summary>
            <div className="client-history-card__details">
              <div className="client-history-pickup">
                <MapPin aria-hidden="true" />
                <div><small>Point de retrait</small><strong>{request.pickup.label}</strong><span>{request.pickup.query}</span></div>
              </div>
              {request.capacity.warning && (
                <p className="client-history-warning">Cette demande a été confirmée malgré le dépassement de la capacité habituelle de la journée.</p>
              )}
              <div className="client-history-packages" role="list">
                {request.packages.map((item) => (
                  <article role="listitem" key={item.id}>
                    <span className="client-history-packages__number">{item.sequenceNumber}</span>
                    <div><strong>{item.recipientName}</strong><small>{item.contact}</small></div>
                    <div><strong>{item.destination.label}</strong><small>{item.pricingZoneName ? `Zone ${item.pricingZoneName}` : 'Tarif non disponible'}{item.faritra ? ' · Faritra' : ''}</small></div>
                    <div><strong>{formatAriary(item.amountToCollectAriary)}</strong><small>{item.deliveryFeeAriary != null ? `dont ${formatAriary(item.deliveryFeeAriary)} de livraison · ${item.mobileMoneyPaid ? 'Mobile Money' : 'à encaisser'}` : item.mobileMoneyPaid ? 'Mobile Money' : 'À encaisser'}</small></div>
                    <span className={`client-package-status client-package-status--${item.status.toLowerCase()}`}>{PACKAGE_STATUS[item.status]}</span>
                  </article>
                ))}
              </div>
            </div>
          </details>
        )
      })}
    </div>
  )
}

function ClientProfilePanel({
  accessToken,
  profile,
  onSaved,
  onCancel,
}: {
  accessToken: string
  profile: ClientProfile
  onSaved: (profile: ClientProfile) => void
  onCancel: () => void
}) {
  const [displayName, setDisplayName] = useState(profile.displayName)
  const [phone, setPhone] = useState(profile.phone)
  const [defaultPickup, setDefaultPickup] = useState<Location>(() => asPickerLocation(profile.defaultPickup))
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string>()
  const [error, setError] = useState<string>()

  async function save() {
    setError(undefined)
    setMessage(undefined)
    if (!displayName.trim() || !phone.trim()) {
      setError('Le nom affiché et le téléphone sont obligatoires.')
      return
    }
    const hasDefaultPickup = Number.isFinite(defaultPickup.latitude) && Number.isFinite(defaultPickup.longitude)
    setSaving(true)
    try {
      const saved = await updateClientProfile({
        displayName: displayName.trim(),
        phone: phone.trim(),
        defaultPickup: hasDefaultPickup ? {
          label: defaultPickup.label?.trim() || defaultPickup.destinationQuery?.trim() || 'Point de retrait',
          destinationQuery: defaultPickup.destinationQuery?.trim() || undefined,
          latitude: defaultPickup.latitude as number,
          longitude: defaultPickup.longitude as number,
        } : null,
      }, accessToken)
      onSaved(saved)
      setMessage('Votre profil a bien été mis à jour.')
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Le profil n’a pas pu être enregistré.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="client-profile-page" aria-labelledby="client-profile-title">
      <header className="client-page-heading">
        <div><p className="eyebrow">Mon compte</p><h1 id="client-profile-title">Profil et retrait habituel</h1><p>Gardez vos coordonnées à jour et gagnez du temps avec un point de retrait par défaut.</p></div>
        <button className="button button--secondary" type="button" onClick={onCancel}>Retour à l’historique</button>
      </header>
      <div className="client-profile-layout">
        <section className="client-surface client-profile-card">
          <div className="client-section-heading">
            <span aria-hidden="true"><UserRound /></span>
            <div><p className="eyebrow">Coordonnées</p><h2>Informations du compte</h2></div>
          </div>
          <div className="client-form-grid">
            <label className="client-field"><span>Nom affiché</span><input value={displayName} maxLength={150} onChange={(event) => setDisplayName(event.target.value)} /></label>
            <label className="client-field"><span>Téléphone</span><input value={phone} maxLength={50} inputMode="tel" onChange={(event) => setPhone(event.target.value)} /></label>
          </div>
          <label className="client-field client-field--readonly"><span>Identifiant</span><input value={profile.username} readOnly /></label>
        </section>
        <section className="client-surface client-profile-card">
          <div className="client-section-heading">
            <span aria-hidden="true"><MapPin /></span>
            <div><p className="eyebrow">Adresse favorite</p><h2>Retrait par défaut</h2></div>
          </div>
          <label className="client-field"><span>Libellé</span><input value={defaultPickup.label ?? ''} maxLength={200} placeholder="Ex. Ma boutique" onChange={(event) => setDefaultPickup({ ...defaultPickup, label: event.target.value })} /></label>
          <LocationPicker
            accessToken={accessToken}
            value={defaultPickup}
            title="Retrait par défaut"
            description="Choisissez le point que nous utiliserons automatiquement dans vos nouvelles demandes."
            onChange={(location) => setDefaultPickup({ ...location, label: defaultPickup.label?.trim() || location.label || location.destinationQuery || 'Point de retrait' })}
          />
          {Number.isFinite(defaultPickup.latitude) && (
            <button className="client-text-action" type="button" onClick={() => setDefaultPickup({})}>Retirer le point par défaut</button>
          )}
        </section>
      </div>
      {message && <p className="client-alert client-alert--success" role="status">{message}</p>}
      {error && <p className="client-alert client-alert--error" role="alert">{error}</p>}
      <div className="client-profile-actions">
        <button className="button" type="button" disabled={saving} onClick={() => void save()}><Save aria-hidden="true" />{saving ? 'Enregistrement…' : 'Enregistrer les modifications'}</button>
      </div>
    </section>
  )
}

export interface ClientPortalProps {
  accessToken: string
}

export function ClientPortal({ accessToken }: ClientPortalProps) {
  const [view, setView] = useState<ClientView>('history')
  const [profile, setProfile] = useState<ClientProfile>()
  const [requests, setRequests] = useState<DeliveryRequestRecord[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string>()
  const [successMessage, setSuccessMessage] = useState<string>()

  async function load() {
    setLoading(true)
    setError(undefined)
    try {
      const [loadedProfile, loadedRequests] = await Promise.all([
        getClientProfile(accessToken),
        listDeliveryRequests(accessToken),
      ])
      setProfile(loadedProfile)
      setRequests(loadedRequests)
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Votre espace client n’a pas pu être chargé.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  // accessToken is the only value that identifies the remote session.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accessToken])

  const stats = useMemo(() => {
    const packages = requests.flatMap((request) => request.packages)
    return {
      requestCount: requests.length,
      pendingCount: packages.filter((item) => item.status === 'PENDING').length,
      dispatchedCount: packages.filter((item) => item.status === 'DISPATCHED').length,
    }
  }, [requests])

  async function handleCreated(request: DeliveryRequestRecord, savedDefaultPickup: boolean) {
    setRequests((current) => [request, ...current])
    setSuccessMessage(`Votre demande de ${request.packages.length} colis a bien été enregistrée.`)
    setView('history')
    if (savedDefaultPickup) {
      try {
        setProfile(await getClientProfile(accessToken))
      } catch {
        // The request itself is safely persisted; the next page load will refresh the profile.
      }
    }
  }

  if (loading) {
    return <section className="client-portal-state" aria-live="polite"><LoaderCircle className="spin" aria-hidden="true" /><strong>Ouverture de votre espace…</strong><span>Nous récupérons vos demandes de livraison.</span></section>
  }

  if (error || !profile) {
    return (
      <section className="client-portal-state client-portal-state--error">
        <RefreshCw aria-hidden="true" /><strong>Impossible d’ouvrir votre espace</strong><span>{error}</span>
        <button className="button" type="button" onClick={() => void load()}>Réessayer</button>
      </section>
    )
  }

  if (view === 'new-request') {
    return <ClientDeliveryRequestForm accessToken={accessToken} defaultPickup={profile.defaultPickup} onCreated={(request, saved) => void handleCreated(request, saved)} onCancel={() => setView('history')} />
  }

  if (view === 'profile') {
    return <ClientProfilePanel accessToken={accessToken} profile={profile} onSaved={setProfile} onCancel={() => setView('history')} />
  }

  return (
    <section className="client-portal" aria-labelledby="client-dashboard-title">
      <header className="client-dashboard-hero">
        <div>
          <p className="eyebrow">Espace client</p>
          <h1 id="client-dashboard-title">Bonjour, {profile.displayName}</h1>
          <p>Préparez vos colis et gardez un œil sur chaque demande confiée à AZY Livraison.</p>
        </div>
        <div className="client-dashboard-hero__actions">
          <button className="button button--light" type="button" onClick={() => setView('profile')}><Settings2 aria-hidden="true" />Mon profil</button>
          <button className="button" type="button" onClick={() => {
            setSuccessMessage(undefined)
            setView('new-request')
          }}><PackagePlus aria-hidden="true" />Nouvelle demande</button>
        </div>
      </header>

      {successMessage && <p className="client-alert client-alert--success" role="status"><CheckCircle2 aria-hidden="true" />{successMessage}</p>}

      <div className="client-stat-grid" aria-label="Résumé de vos livraisons">
        <article><span aria-hidden="true"><History /></span><div><small>Demandes enregistrées</small><strong>{stats.requestCount}</strong></div></article>
        <article><span aria-hidden="true"><Clock3 /></span><div><small>Colis en attente</small><strong>{stats.pendingCount}</strong></div></article>
        <article><span aria-hidden="true"><PackageCheck /></span><div><small>Colis dispatchés</small><strong>{stats.dispatchedCount}</strong></div></article>
        <article className="client-stat-grid__pickup"><span aria-hidden="true"><MapPin /></span><div><small>Retrait par défaut</small><strong>{profile.defaultPickup?.label ?? 'Non défini'}</strong></div><button type="button" aria-label="Modifier le retrait par défaut" onClick={() => setView('profile')}><PencilLine aria-hidden="true" /></button></article>
      </div>

      <section className="client-surface client-history-section">
        <div className="client-history-heading">
          <div><p className="eyebrow">Suivi</p><h2>Mes demandes de livraison</h2></div>
          <button className="button button--secondary" type="button" onClick={() => void load()}><RefreshCw aria-hidden="true" />Actualiser</button>
        </div>
        <RequestHistory requests={requests} />
      </section>
    </section>
  )
}
