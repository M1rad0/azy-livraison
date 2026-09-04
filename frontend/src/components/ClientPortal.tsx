import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react'
import {
  ArrowRight,
  Ban,
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
  WalletCards,
  Zap,
} from 'lucide-react'
import {
  cancelDeliveryPackage,
  getClientProfile,
  listDeliveryRequests,
  updateClientProfile,
  type ClientDefaultPickup,
  type ClientProfile,
  type DeliveryPackageStatus,
  type DeliveryPackageOutcome,
  type DeliveryRequestRecord,
  type DeliveryRequestStatus,
} from '../api/client'
import type { Location } from '../api/csvImports'
import { ClientDeliveryRequestForm } from './ClientDeliveryRequestForm'
import { ClientContactPreferences } from './ClientContactPreferences'
import { ClientReportsPanel } from './ClientReportsPanel'
import { ConfirmDialog } from './ConfirmDialog'
import { LocationPicker } from './LocationPicker'
import { usePortalNavigation } from './PortalShell'
import { PricingTransparencyPanel } from './PricingTransparencyPanel'
import './ClientPortal.css'

type ClientView = 'history' | 'new-request' | 'express' | 'pricing' | 'reports' | 'profile'
type ClientProfileSection = 'information' | 'payouts'

const REQUEST_STATUS: Record<DeliveryRequestStatus, string> = {
  PENDING: 'En cours de traitement',
  PARTIALLY_DISPATCHED: 'En cours de traitement',
  DISPATCHED: 'En cours de traitement',
  CANCELLED: 'Annulée',
}

const PACKAGE_STATUS: Record<DeliveryPackageStatus, string> = {
  PENDING: 'En cours de traitement',
  DISPATCHED: 'En cours de traitement',
  UNASSIGNED: 'En cours de traitement',
}

const PACKAGE_OUTCOME: Record<DeliveryPackageOutcome, string> = {
  DELIVERED: 'Livré',
  CANCELLED: 'Annulé',
  POSTPONED: 'Reporté',
  RETURN_TO_SENDER: 'À retourner',
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

function RequestHistory({
  requests,
  onCancel,
}: {
  requests: DeliveryRequestRecord[]
  onCancel: (deliveryPackage: DeliveryRequestRecord['packages'][number]) => void
}) {
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
        const processingPackages = request.packages.filter((item) => !item.finalOutcome).length
        const allCancelled = request.packages.length > 0
          && request.packages.every((item) => item.finalOutcome === 'CANCELLED')
        const allFinalized = request.packages.length > 0
          && request.packages.every((item) => Boolean(item.finalOutcome))
        const cashToCollect = request.packages.reduce(
          (sum, item) => sum + (item.finalOutcome ? 0 : item.amountToCollectAriary),
          0,
        )
        return (
          <details className="client-history-card" key={request.id}>
            <summary>
              <span className={`client-status client-status--${allCancelled ? 'cancelled' : allFinalized ? 'completed' : 'processing'}`}>
                {allCancelled ? <Ban aria-hidden="true" /> : allFinalized ? <CheckCircle2 aria-hidden="true" /> : <Clock3 aria-hidden="true" />}
                {allCancelled ? 'Annulée' : allFinalized ? 'Terminée' : REQUEST_STATUS[request.status]}
              </span>
              <div className="client-history-card__identity">
                <strong>{request.pickup.label} {request.requestKind === 'EXPRESS' && <em className="client-express-badge">Express</em>}</strong>
                <small><CalendarClock aria-hidden="true" />{formatDate(request.requestedDate)}</small>
                {request.createdByAdministration && <small>Créée par l’administration</small>}
              </div>
              <div className="client-history-card__metric"><small>Colis</small><strong>{request.packages.length}</strong></div>
              <div className="client-history-card__metric"><small>En traitement</small><strong>{processingPackages}</strong></div>
              <div className="client-history-card__metric client-history-card__metric--amount"><small>À encaisser</small><strong>{formatAriary(cashToCollect)}</strong></div>
              <ArrowRight className="client-history-card__chevron" aria-hidden="true" />
            </summary>
            <div className="client-history-card__details">
              <div className="client-history-pickup">
                <MapPin aria-hidden="true" />
                <div><small>Point de retrait</small><strong>{request.pickup.label}</strong><span>{request.pickup.query}</span></div>
              </div>
              {request.capacity.warning && (
                <p className="client-history-warning">Demande historique enregistrée avec l’ancienne politique de capacité.</p>
              )}
              <div className="client-history-packages" role="list">
                {request.packages.map((item) => {
                  const applicableFee = item.finalDeliveryFeeAriary ?? item.deliveryFeeAriary ?? 0
                  const finalAmount = item.finalOutcome === 'DELIVERED'
                    ? (item.actualCollectedAriary ?? item.amountToCollectAriary)
                    : item.finalOutcome === 'RETURN_TO_SENDER'
                      ? (item.actualCollectedAriary ?? 0)
                      : item.finalOutcome
                        ? 0
                        : item.amountToCollectAriary
                  const finalFee = item.finalOutcome === 'DELIVERED' || item.finalOutcome === 'RETURN_TO_SENDER'
                    ? applicableFee
                    : item.finalOutcome
                      ? 0
                      : applicableFee
                  return (
                  <article role="listitem" key={item.id}>
                    <span className="client-history-packages__number">{item.sequenceNumber}</span>
                    <div><strong>{item.recipientName}</strong><small>{item.contact}</small></div>
                    <div><strong>{item.destination.label}</strong><small>{item.pricingZoneName ? `Zone ${item.pricingZoneName}` : 'Tarif non disponible'}{item.faritra ? ' · Faritra' : ''}</small></div>
                    <div><strong>{formatAriary(finalAmount)}</strong><small>{item.finalOutcome ? `frais facturés ${formatAriary(finalFee)}` : item.finalDeliveryFeeAriary != null || item.deliveryFeeAriary != null ? `dont ${formatAriary(applicableFee)} de livraison · à encaisser` : 'À encaisser'}{item.mobileMoneyPaid ? ' · Mobile Money signalé' : ''}</small></div>
                    <div className="client-history-packages__state">
                      <span className={`client-package-status client-package-status--${item.finalOutcome ? item.finalOutcome.toLowerCase() : 'processing'}`}>
                        {item.finalOutcome ? PACKAGE_OUTCOME[item.finalOutcome] : PACKAGE_STATUS[item.status]}
                      </span>
                      {item.finalOutcome === 'RETURN_TO_SENDER' && (
                        <small>{item.returnCompletedAt ? 'Remis à l’expéditeur' : 'Retour en attente'}</small>
                      )}
                      {item.status === 'PENDING' && !item.finalOutcome && (
                        <button className="client-cancel-package" type="button" onClick={() => onCancel(item)}>
                          <Ban aria-hidden="true" />Annuler
                        </button>
                      )}
                    </div>
                  </article>
                  )
                })}
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
  const [defaultPickup, setDefaultPickup] = useState<Location>(() => asPickerLocation(profile.defaultPickup))
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string>()
  const [error, setError] = useState<string>()
  const [section, setSection] = useState<ClientProfileSection>('information')
  const [preferencesSaving, setPreferencesSaving] = useState(false)
  const informationTabRef = useRef<HTMLButtonElement>(null)
  const payoutsTabRef = useRef<HTMLButtonElement>(null)

  function handleSectionKeyDown(event: ReactKeyboardEvent<HTMLButtonElement>) {
    let nextSection: ClientProfileSection | undefined
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') nextSection = section === 'information' ? 'payouts' : 'information'
    if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') nextSection = section === 'information' ? 'payouts' : 'information'
    if (event.key === 'End') nextSection = 'payouts'
    if (event.key === 'Home') nextSection = 'information'
    if (!nextSection) return
    event.preventDefault()
    setSection(nextSection)
    const nextTabRef = nextSection === 'information' ? informationTabRef : payoutsTabRef
    nextTabRef.current?.focus()
  }

  async function save() {
    if (preferencesSaving) return
    setError(undefined)
    setMessage(undefined)
    if (!displayName.trim()) {
      setError('Le nom affiché est obligatoire.')
      return
    }
    if (!profile.phone.trim()) {
      setError('Ajoutez d’abord un numéro de contact principal.')
      return
    }
    const hasDefaultPickup = Number.isFinite(defaultPickup.latitude) && Number.isFinite(defaultPickup.longitude)
    setSaving(true)
    try {
      const saved = await updateClientProfile({
        displayName: displayName.trim(),
        phone: profile.phone.trim(),
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
        <div><p className="eyebrow">Mon compte</p><h1 id="client-profile-title">Coordonnées</h1><p>Gérez séparément vos informations habituelles et les numéros utilisés pour vos versements.</p></div>
        <button className="button button--secondary" type="button" onClick={onCancel}>Retour à l’historique</button>
      </header>
      <div className="client-profile-tabs" role="tablist" aria-label="Sections des coordonnées">
        <button ref={informationTabRef} id="client-profile-information-tab" type="button" role="tab" tabIndex={section === 'information' ? 0 : -1} aria-selected={section === 'information'} aria-controls="client-profile-information-panel" className={section === 'information' ? 'is-active' : undefined} onClick={() => setSection('information')} onKeyDown={handleSectionKeyDown}>
          <UserRound aria-hidden="true" />
          <span><strong>Informations</strong><small>Profil, retrait et contacts</small></span>
        </button>
        <button ref={payoutsTabRef} id="client-profile-payouts-tab" type="button" role="tab" tabIndex={section === 'payouts' ? 0 : -1} aria-selected={section === 'payouts'} aria-controls="client-profile-payouts-panel" className={section === 'payouts' ? 'is-active' : undefined} onClick={() => setSection('payouts')} onKeyDown={handleSectionKeyDown}>
          <WalletCards aria-hidden="true" />
          <span><strong>Versements</strong><small>Numéros par ordre de priorité</small></span>
        </button>
      </div>

      <div id="client-profile-information-panel" role="tabpanel" aria-labelledby="client-profile-information-tab" hidden={section !== 'information'}>
        <div className="client-profile-layout">
          <section className="client-surface client-profile-card">
            <div className="client-section-heading">
              <span aria-hidden="true"><UserRound /></span>
              <div><p className="eyebrow">Coordonnées</p><h2>Informations du compte</h2></div>
            </div>
            <div className="client-form-grid">
              <label className="client-field"><span>Nom affiché</span><input value={displayName} maxLength={150} onChange={(event) => setDisplayName(event.target.value)} /></label>
              <label className="client-field client-field--readonly"><span>Téléphone principal</span><input aria-label="Téléphone principal" value={profile.phone} readOnly /><small>Modifiez ce numéro dans la liste de contacts ci-dessous.</small></label>
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
          <button className="button" type="button" disabled={saving || preferencesSaving} onClick={() => void save()}><Save aria-hidden="true" />{saving ? 'Enregistrement…' : 'Enregistrer les modifications'}</button>
        </div>
        <ClientContactPreferences accessToken={accessToken} profile={profile} onSaved={onSaved} section="contacts" disabled={saving || preferencesSaving} onBusyChange={setPreferencesSaving} />
      </div>

      <div id="client-profile-payouts-panel" role="tabpanel" aria-labelledby="client-profile-payouts-tab" hidden={section !== 'payouts'}>
        <ClientContactPreferences accessToken={accessToken} profile={profile} onSaved={onSaved} section="payouts" disabled={saving || preferencesSaving} onBusyChange={setPreferencesSaving} />
      </div>
    </section>
  )
}

export interface ClientPortalProps {
  accessToken: string
}

export function ClientPortal({ accessToken }: ClientPortalProps) {
  const portalNavigation = usePortalNavigation()
  const [localView, setLocalView] = useState<ClientView>('history')
  const view = portalNavigation?.role === 'CLIENT'
    && ['history', 'new-request', 'express', 'pricing', 'reports', 'profile'].includes(portalNavigation.section)
    ? portalNavigation.section as ClientView
    : localView
  const [profile, setProfile] = useState<ClientProfile>()
  const [requests, setRequests] = useState<DeliveryRequestRecord[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string>()
  const [successMessage, setSuccessMessage] = useState<string>()
  const [actionError, setActionError] = useState<string>()
  const [packageToCancel, setPackageToCancel] = useState<DeliveryRequestRecord['packages'][number]>()
  const [cancelling, setCancelling] = useState(false)
  const [requestFormMounted, setRequestFormMounted] = useState(view === 'new-request')
  const [profileMounted, setProfileMounted] = useState(view === 'profile')
  const [requestFormKey, setRequestFormKey] = useState(0)

  function openView(nextView: ClientView) {
    setLocalView(nextView)
    if (portalNavigation?.role === 'CLIENT') portalNavigation.navigate(nextView)
  }

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

  useEffect(() => {
    if (view === 'new-request' || view === 'express') setRequestFormMounted(true)
    if (view === 'profile') setProfileMounted(true)
  }, [view])

  useEffect(() => {
    if (portalNavigation?.role !== 'CLIENT' || !profile) return
    portalNavigation.setSectionVisible('express', Boolean(profile.expressDeliveryEnabled))
    if (!profile.expressDeliveryEnabled && portalNavigation.section === 'express') {
      portalNavigation.navigate('history')
    }
  }, [portalNavigation, profile])

  const stats = useMemo(() => {
    const packages = requests.flatMap((request) => request.packages)
    return {
      requestCount: requests.length,
      processingCount: packages.filter((item) => !item.finalOutcome).length,
      finalizedCount: packages.filter((item) => Boolean(item.finalOutcome)).length,
    }
  }, [requests])
  const cancellationDate = packageToCancel
    ? requests.find((request) => request.packages.some((item) => item.id === packageToCancel.id))?.requestedDate
    : undefined

  async function handleCreated(request: DeliveryRequestRecord, savedDefaultPickup: boolean) {
    setRequests((current) => [request, ...current])
    setSuccessMessage(`Votre demande de ${request.packages.length} colis a bien été enregistrée.`)
    setRequestFormKey((current) => current + 1)
    openView('history')
    if (savedDefaultPickup) {
      try {
        setProfile(await getClientProfile(accessToken))
      } catch {
        // The request itself is safely persisted; the next page load will refresh the profile.
      }
    }
  }

  async function confirmCancellation() {
    if (!packageToCancel) return
    setCancelling(true)
    setActionError(undefined)
    try {
      await cancelDeliveryPackage(packageToCancel.id, packageToCancel.version, accessToken)
      setRequests(await listDeliveryRequests(accessToken))
      setPackageToCancel(undefined)
      setSuccessMessage(`Le colis de ${packageToCancel.recipientName} a été annulé.`)
    } catch (cancelError) {
      setActionError(cancelError instanceof Error ? cancelError.message : 'Le colis n’a pas pu être annulé.')
      setPackageToCancel(undefined)
    } finally {
      setCancelling(false)
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

  return (
    <>
      {requestFormMounted && (
        <div hidden={view !== 'new-request' && view !== 'express'}>
          {view === 'express' && !profile.expressDeliveryEnabled ? (
            <section className="client-portal-state"><Zap /><strong>Livraison express non activée</strong><span>Contactez AZY Livraison pour activer les demandes expresses sur votre compte.</span><button className="button button--secondary" type="button" onClick={() => openView('history')}>Retour</button></section>
          ) : <ClientDeliveryRequestForm
            key={`${requestFormKey}-${view}`}
            accessToken={accessToken}
            defaultPickup={profile.defaultPickup}
            requestKind={view === 'express' ? 'EXPRESS' : 'NORMAL'}
            onCreated={(request, saved) => void handleCreated(request, saved)}
            onCancel={() => openView('history')}
          />}
        </div>
      )}
      {profileMounted && (
        <div hidden={view !== 'profile'}>
          <ClientProfilePanel accessToken={accessToken} profile={profile} onSaved={setProfile} onCancel={() => openView('history')} />
        </div>
      )}
      {view === 'reports' && <ClientReportsPanel accessToken={accessToken} />}
      {view === 'pricing' && <PricingTransparencyPanel accessToken={accessToken} />}
      <section className="client-portal" aria-labelledby="client-dashboard-title" hidden={view !== 'history'}>
      <header className="client-dashboard-hero">
        <div>
          <p className="eyebrow">Espace client</p>
          <h1 id="client-dashboard-title">Bonjour, {profile.displayName}</h1>
          <p>Préparez vos colis et gardez un œil sur chaque demande confiée à AZY Livraison.</p>
        </div>
        <div className="client-dashboard-hero__actions">
          <button className="button button--light" type="button" onClick={() => openView('profile')}><Settings2 aria-hidden="true" />Mon profil</button>
          <button className="button" type="button" onClick={() => {
            setSuccessMessage(undefined)
            openView('new-request')
          }}><PackagePlus aria-hidden="true" />Nouvelle demande</button>
          {profile.expressDeliveryEnabled && <button className="button client-button--express" type="button" onClick={() => { setSuccessMessage(undefined); openView('express') }}><Zap aria-hidden="true" />Livraison express</button>}
        </div>
      </header>

      {successMessage && <p className="client-alert client-alert--success" role="status"><CheckCircle2 aria-hidden="true" />{successMessage}</p>}
      {actionError && <p className="client-alert client-alert--error" role="alert">{actionError}</p>}

      <div className="client-stat-grid" aria-label="Résumé de vos livraisons">
        <article><span aria-hidden="true"><History /></span><div><small>Demandes enregistrées</small><strong>{stats.requestCount}</strong></div></article>
        <article><span aria-hidden="true"><Clock3 /></span><div><small>Colis en traitement</small><strong>{stats.processingCount}</strong></div></article>
        <article><span aria-hidden="true"><PackageCheck /></span><div><small>Colis finalisés</small><strong>{stats.finalizedCount}</strong></div></article>
        <article className="client-stat-grid__pickup"><span aria-hidden="true"><MapPin /></span><div><small>Retrait par défaut</small><strong>{profile.defaultPickup?.label ?? 'Non défini'}</strong></div><button type="button" aria-label="Modifier le retrait par défaut" onClick={() => openView('profile')}><PencilLine aria-hidden="true" /></button></article>
      </div>

      <section className="client-surface client-history-section">
        <div className="client-history-heading">
          <div><p className="eyebrow">Suivi</p><h2>Mes demandes de livraison</h2></div>
          <button className="button button--secondary" type="button" onClick={() => void load()}><RefreshCw aria-hidden="true" />Actualiser</button>
        </div>
        <RequestHistory requests={requests} onCancel={setPackageToCancel} />
      </section>

      {packageToCancel && (
        <ConfirmDialog
          title="Annuler ce colis ?"
          confirmLabel="Annuler le colis"
          busy={cancelling}
          danger
          onClose={() => setPackageToCancel(undefined)}
          onConfirm={() => void confirmCancellation()}
        >
          <p>Le colis destiné à <strong>{packageToCancel.recipientName}</strong> sera annulé{cancellationDate ? ` et libérera une place pour le ${formatDate(cancellationDate)}` : ''}. Cette action est réservée aux colis encore en attente.</p>
        </ConfirmDialog>
      )}
      </section>
    </>
  )
}
