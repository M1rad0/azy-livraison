import { type FormEvent, useEffect, useState } from 'react'
import {
  Bike,
  Clock3,
  Gauge,
  MapPin,
  PackageOpen,
  Pencil,
  Phone,
  Plus,
  Power,
  Save,
  Smartphone,
  UserRound,
  WalletCards,
  X,
} from 'lucide-react'
import {
  createCourier,
  disableCourier,
  updateCourier,
  type Courier,
  type CourierInput,
  type VehiclePreset,
  type VehicleType,
} from '../../api/admin'
import type { Location } from '../../api/csvImports'
import { LocationPicker } from '../LocationPicker'
import { asConfirmedLocation, asPickerLocation, errorMessage, locationLabel, vehicleLabel } from './adminHelpers'

interface AdminCouriersPanelProps {
  accessToken: string
  couriers: Courier[]
  presets: VehiclePreset[]
  onSaved: (courier: Courier) => void
}

interface CourierDraft {
  name: string
  phoneNumber: string
  payoutOperator: string
  payoutAccountHolder: string
  payoutNumber: string
  active: boolean
  vehicleType: VehicleType
  maxWeightKg: string
  averageSpeedKmh: string
  commissionNumerator: string
  commissionDenominator: string
  fuelReimbursementMode: 'DEFAULT' | 'ENABLED' | 'DISABLED'
  fuelEfficiencyKmPerLiter: string
  fuelPricePerLiterAriary: string
  defaultDepartureTime: string
  departureLocation: Location
}

function emptyDraft(): CourierDraft {
  return {
    name: '',
    phoneNumber: '',
    payoutOperator: '',
    payoutAccountHolder: '',
    payoutNumber: '',
    active: true,
    vehicleType: 'MOTO',
    maxWeightKg: '',
    averageSpeedKmh: '',
    commissionNumerator: '',
    commissionDenominator: '',
    fuelReimbursementMode: 'DEFAULT',
    fuelEfficiencyKmPerLiter: '',
    fuelPricePerLiterAriary: '',
    defaultDepartureTime: '06:00',
    departureLocation: {},
  }
}

function courierDraft(courier: Courier): CourierDraft {
  return {
    name: courier.name,
    phoneNumber: courier.phoneNumber ?? courier.contact ?? '',
    payoutOperator: courier.payoutDestination?.operator ?? '',
    payoutAccountHolder: courier.payoutDestination?.accountHolder ?? '',
    payoutNumber: courier.payoutDestination?.number ?? '',
    active: courier.active,
    vehicleType: courier.vehicleType,
    maxWeightKg: courier.maxWeightKg?.toString() ?? '',
    averageSpeedKmh: courier.averageSpeedKmh?.toString() ?? '',
    commissionNumerator: courier.commissionNumerator?.toString() ?? '',
    commissionDenominator: courier.commissionDenominator?.toString() ?? '',
    fuelReimbursementMode: courier.fuelReimbursementEnabled == null
      ? 'DEFAULT'
      : courier.fuelReimbursementEnabled ? 'ENABLED' : 'DISABLED',
    fuelEfficiencyKmPerLiter: courier.fuelEfficiencyKmPerLiter?.toString() ?? '',
    fuelPricePerLiterAriary: courier.fuelPricePerLiterAriary?.toString() ?? '',
    defaultDepartureTime: courier.defaultDepartureTime.slice(0, 5),
    departureLocation: asPickerLocation(courier.departureLocation),
  }
}

function positiveNumber(value: string) {
  if (!value.trim()) return undefined
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : undefined
}

function CourierEditor({
  accessToken,
  courier,
  presets,
  onClose,
  onSaved,
}: {
  accessToken: string
  courier?: Courier
  presets: VehiclePreset[]
  onClose: () => void
  onSaved: (courier: Courier) => void
}) {
  const [draft, setDraft] = useState(() => courier ? courierDraft(courier) : emptyDraft())
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string>()
  const preset = presets.find((item) => item.vehicleType === draft.vehicleType)

  useEffect(() => {
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = overflow }
  }, [])

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const departureLocation = asConfirmedLocation(draft.departureLocation)
    if (!departureLocation) {
      setMessage('Confirmez le point de départ habituel sur la carte.')
      return
    }
    if (draft.maxWeightKg.trim() && !positiveNumber(draft.maxWeightKg)) {
      setMessage('La capacité personnalisée doit être un nombre positif.')
      return
    }
    if (draft.averageSpeedKmh.trim() && !positiveNumber(draft.averageSpeedKmh)) {
      setMessage('La vitesse personnalisée doit être un nombre positif.')
      return
    }
    if (draft.fuelEfficiencyKmPerLiter.trim() && !positiveNumber(draft.fuelEfficiencyKmPerLiter)) {
      setMessage('La distance parcourue avec un litre doit être un nombre positif.')
      return
    }
    const customFuelPrice = positiveNumber(draft.fuelPricePerLiterAriary)
    if (draft.fuelPricePerLiterAriary.trim() && !Number.isInteger(customFuelPrice)) {
      setMessage('Le prix personnalisé du litre doit être un nombre entier positif.')
      return
    }
    const customCommissionStarted = Boolean(
      draft.commissionNumerator.trim() || draft.commissionDenominator.trim(),
    )
    const commissionNumerator = positiveNumber(draft.commissionNumerator)
    const commissionDenominator = positiveNumber(draft.commissionDenominator)
    if (customCommissionStarted && (
      !Number.isInteger(commissionNumerator)
      || !Number.isInteger(commissionDenominator)
      || (commissionNumerator as number) > (commissionDenominator as number)
    )) {
      setMessage('Renseignez une fraction complète avec des nombres entiers, par exemple 1/3.')
      return
    }
    const hasPayoutValue = Boolean(
      draft.payoutOperator.trim() || draft.payoutAccountHolder.trim() || draft.payoutNumber.trim(),
    )
    if (hasPayoutValue && (!draft.payoutOperator.trim() || !draft.payoutAccountHolder.trim() || !draft.payoutNumber.trim())) {
      setMessage('Renseignez l’opérateur, le titulaire et le numéro de versement, ou laissez les trois champs vides.')
      return
    }

    const input: CourierInput = {
      name: draft.name.trim(),
      contact: draft.phoneNumber.trim() || undefined,
      phoneNumber: draft.phoneNumber.trim() || undefined,
      payoutDestination: hasPayoutValue ? {
        operator: draft.payoutOperator.trim(),
        accountHolder: draft.payoutAccountHolder.trim(),
        number: draft.payoutNumber.trim(),
      } : null,
      active: draft.active,
      vehicleType: draft.vehicleType,
      maxWeightKg: positiveNumber(draft.maxWeightKg),
      averageSpeedKmh: positiveNumber(draft.averageSpeedKmh),
      commissionNumerator: customCommissionStarted ? commissionNumerator : null,
      commissionDenominator: customCommissionStarted ? commissionDenominator : null,
      fuelReimbursementEnabled: draft.fuelReimbursementMode === 'DEFAULT'
        ? null
        : draft.fuelReimbursementMode === 'ENABLED',
      fuelEfficiencyKmPerLiter: positiveNumber(draft.fuelEfficiencyKmPerLiter) ?? null,
      fuelPricePerLiterAriary: customFuelPrice ?? null,
      defaultDepartureTime: draft.defaultDepartureTime,
      departureLocation,
      version: courier?.version,
    }
    setSaving(true)
    setMessage(undefined)
    try {
      const saved = courier
        ? await updateCourier(courier.id, input, accessToken)
        : await createCourier(input, accessToken)
      onSaved(saved)
      onClose()
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="admin-modal-overlay" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <section className="admin-modal" role="dialog" aria-modal="true" aria-labelledby="courier-editor-title">
        <header>
          <div><p className="admin-eyebrow">Profil opérationnel</p><h3 id="courier-editor-title">{courier ? 'Modifier le livreur' : 'Nouveau livreur'}</h3></div>
          <button type="button" className="admin-icon-button" onClick={onClose} aria-label="Fermer"><X aria-hidden="true" /></button>
        </header>
        <form onSubmit={(event) => void submit(event)}>
          <div className="admin-form-grid">
            <label><span>Nom du livreur</span><input required maxLength={150} value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} placeholder="Ex. Tiana" /></label>
            <label><span>Numéro de téléphone</span><input maxLength={50} inputMode="tel" value={draft.phoneNumber} onChange={(event) => setDraft({ ...draft, phoneNumber: event.target.value })} placeholder="Ex. 034 00 000 00" /></label>
            <label><span>Type de véhicule</span><select value={draft.vehicleType} onChange={(event) => setDraft({ ...draft, vehicleType: event.target.value as VehicleType })}><option value="MOTO">Moto</option><option value="BICYCLE">Vélo</option></select></label>
            <label><span>Heure de départ habituelle</span><input required type="time" value={draft.defaultDepartureTime} onChange={(event) => setDraft({ ...draft, defaultDepartureTime: event.target.value })} /></label>
            <label>
              <span>Capacité personnalisée <small>facultatif</small></span>
              <input min="0.01" step="0.01" type="number" value={draft.maxWeightKg} onChange={(event) => setDraft({ ...draft, maxWeightKg: event.target.value })} placeholder={preset ? `${preset.maxWeightKg} kg par défaut` : 'kg'} />
            </label>
            <label>
              <span>Vitesse personnalisée <small>facultatif</small></span>
              <input min="0.01" step="0.01" type="number" value={draft.averageSpeedKmh} onChange={(event) => setDraft({ ...draft, averageSpeedKmh: event.target.value })} placeholder={preset ? `${preset.averageSpeedKmh} km/h par défaut` : 'km/h'} />
            </label>
          </div>
          <section className="admin-courier-payout-fields">
            <header><WalletCards aria-hidden="true" /><div><strong>Numéro de versement</strong><small>Facultatif · utilisé pour les transferts de caisse</small></div></header>
            <div className="admin-form-grid">
              <label><span>Opérateur</span><input maxLength={80} value={draft.payoutOperator} onChange={(event) => setDraft({ ...draft, payoutOperator: event.target.value })} placeholder="Ex. MVola" /></label>
              <label><span>Titulaire</span><input maxLength={150} value={draft.payoutAccountHolder} onChange={(event) => setDraft({ ...draft, payoutAccountHolder: event.target.value })} /></label>
              <label><span>Numéro</span><input maxLength={100} inputMode="tel" value={draft.payoutNumber} onChange={(event) => setDraft({ ...draft, payoutNumber: event.target.value })} /></label>
            </div>
          </section>
          {courier && (
            <label className="admin-switch"><input type="checkbox" checked={draft.active} onChange={(event) => setDraft({ ...draft, active: event.target.checked })} /><span><i /><span><strong>Livreur actif</strong><small>Disponible dans la sélection du dispatch</small></span></span></label>
          )}
          <div className="admin-editor-location">
            <span>Point de départ habituel</span>
            <LocationPicker
              accessToken={accessToken}
              value={draft.departureLocation}
              title={`Départ habituel de ${draft.name || 'ce livreur'}`}
              description="Ce trajet vers le premier retrait sera inclus dans le calcul."
              onChange={(departureLocation) => setDraft({ ...draft, departureLocation })}
            />
          </div>
          {message && <p className="admin-form-error" role="alert">{message}</p>}
          <footer><button className="admin-button admin-button--ghost" type="button" onClick={onClose}>Annuler</button><button className="admin-button" type="submit" disabled={saving}><Save aria-hidden="true" />{saving ? 'Enregistrement…' : 'Enregistrer'}</button></footer>
        </form>
      </section>
    </div>
  )
}

export function AdminCouriersPanel({ accessToken, couriers, presets, onSaved }: AdminCouriersPanelProps) {
  const [editor, setEditor] = useState<Courier | 'new'>()
  const [disablingId, setDisablingId] = useState<string>()
  const [message, setMessage] = useState<string>()

  async function disable(courier: Courier) {
    setDisablingId(courier.id)
    setMessage(undefined)
    try {
      onSaved(await disableCourier(courier.id, courier.version, accessToken))
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setDisablingId(undefined)
    }
  }

  return (
    <section className="admin-panel" aria-labelledby="couriers-title">
      <header className="admin-panel__heading">
        <div><p className="admin-eyebrow">Équipe opérationnelle</p><h2 id="couriers-title">Livreurs</h2><p>Les profils sont utilisés par le calcul, même sans compte de connexion livreur.</p></div>
        <button className="admin-button" type="button" onClick={() => setEditor('new')}><Plus aria-hidden="true" />Ajouter un livreur</button>
      </header>

      {message && <p className="admin-form-error" role="alert">{message}</p>}
      {couriers.length === 0 ? (
        <div className="admin-empty"><UserRound aria-hidden="true" /><strong>Aucun livreur enregistré</strong><span>Ajoutez le premier profil pour préparer les dispatchs.</span></div>
      ) : (
        <div className="admin-courier-list">
          {couriers.map((courier) => (
            <article className={!courier.active ? 'is-disabled' : undefined} key={courier.id}>
              <header>
                <div className="admin-courier-avatar"><Bike aria-hidden="true" /></div>
                <div><small>{vehicleLabel(courier.vehicleType)}</small><strong>{courier.name}</strong>{(courier.phoneNumber ?? courier.contact) && <span><Phone aria-hidden="true" />{courier.phoneNumber ?? courier.contact}</span>}</div>
                <span className={`admin-chip ${courier.active ? 'admin-chip--ready' : ''}`}>{courier.active ? 'Actif' : 'Désactivé'}</span>
              </header>
              <dl>
                <div><dt><PackageOpen aria-hidden="true" />Capacité</dt><dd>{courier.effectiveMaxWeightKg} kg{courier.maxWeightKg != null && <small>personnalisée</small>}</dd></div>
                <div><dt><Gauge aria-hidden="true" />Vitesse</dt><dd>{courier.effectiveAverageSpeedKmh} km/h{courier.averageSpeedKmh != null && <small>personnalisée</small>}</dd></div>
                <div><dt><Clock3 aria-hidden="true" />Départ</dt><dd>{courier.defaultDepartureTime.slice(0, 5)}</dd></div>
              </dl>
              <div className="admin-courier-location"><MapPin aria-hidden="true" /><span><small>Point de départ</small><strong>{locationLabel(courier.departureLocation)}</strong></span></div>
              {courier.payoutDestination && <div className="admin-courier-location"><Smartphone aria-hidden="true" /><span><small>Versement · {courier.payoutDestination.operator}</small><strong>{courier.payoutDestination.number} · {courier.payoutDestination.accountHolder}</strong></span></div>}
              <footer>
                <button className="admin-button admin-button--ghost" type="button" onClick={() => setEditor(courier)}><Pencil aria-hidden="true" />Modifier</button>
                {courier.active && <button className="admin-button admin-button--danger-ghost" type="button" onClick={() => void disable(courier)} disabled={disablingId === courier.id}><Power aria-hidden="true" />{disablingId === courier.id ? 'Désactivation…' : 'Désactiver'}</button>}
              </footer>
            </article>
          ))}
        </div>
      )}

      {editor && <CourierEditor accessToken={accessToken} courier={editor === 'new' ? undefined : editor} presets={presets} onClose={() => setEditor(undefined)} onSaved={onSaved} />}
    </section>
  )
}
