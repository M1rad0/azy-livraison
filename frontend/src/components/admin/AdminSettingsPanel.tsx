import { type FormEvent, useEffect, useState } from 'react'
import {
  Bike,
  Clock3,
  Gauge,
  MapPinned,
  PackageOpen,
  Route,
  Save,
  Settings2,
  TimerReset,
  TriangleAlert,
  Warehouse,
} from 'lucide-react'
import {
  updateOperationalSettings,
  updateVehiclePresets,
  type OperationalSettings,
  type VehiclePreset,
} from '../../api/admin'
import type { Location } from '../../api/csvImports'
import { LocationPicker } from '../LocationPicker'
import { asConfirmedLocation, asPickerLocation, errorMessage, vehicleLabel } from './adminHelpers'

interface AdminSettingsPanelProps {
  accessToken: string
  settings?: OperationalSettings
  presets: VehiclePreset[]
  onSettingsSaved: (settings: OperationalSettings) => void
  onPresetsSaved: (presets: VehiclePreset[]) => void
}

interface SettingsDraft {
  dailyPackageLimit: string
  defaultPackageWeightKg: string
  pickupServiceMinutes: string
  hubServiceMinutes: string
  deliveryServiceMinutes: string
  earlyToleranceMinutes: string
  lateToleranceMinutes: string
  optimizationTimeLimitSeconds: string
  defaultCourierCommissionNumerator: string
  defaultCourierCommissionDenominator: string
  defaultFuelPricePerLiterAriary: string
  defaultExpressReturnToPickup: boolean
  defaultHub: Location
}

function makeDraft(settings: OperationalSettings): SettingsDraft {
  return {
    dailyPackageLimit: String(settings.dailyPackageLimit),
    defaultPackageWeightKg: String(settings.defaultPackageWeightKg),
    pickupServiceMinutes: String(settings.pickupServiceMinutes),
    hubServiceMinutes: String(settings.hubServiceMinutes),
    deliveryServiceMinutes: String(settings.deliveryServiceMinutes),
    earlyToleranceMinutes: String(settings.earlyToleranceMinutes),
    lateToleranceMinutes: String(settings.lateToleranceMinutes),
    optimizationTimeLimitSeconds: String(settings.optimizationTimeLimitSeconds),
    defaultCourierCommissionNumerator: String(settings.defaultCourierCommissionNumerator),
    defaultCourierCommissionDenominator: String(settings.defaultCourierCommissionDenominator),
    defaultFuelPricePerLiterAriary: String(settings.defaultFuelPricePerLiterAriary),
    defaultExpressReturnToPickup: settings.defaultExpressReturnToPickup,
    defaultHub: asPickerLocation(settings.defaultHub),
  }
}

function readPositive(value: string, label: string, maximum?: number) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed <= 0) throw new Error(`${label} doit être supérieur à zéro.`)
  if (maximum != null && parsed > maximum) throw new Error(`${label} ne peut pas dépasser ${maximum} secondes.`)
  return parsed
}

function readNonNegative(value: string, label: string) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed) || parsed < 0) throw new Error(`${label} ne peut pas être négatif.`)
  return parsed
}

function readPositiveInteger(value: string, label: string) {
  const parsed = readPositive(value, label)
  if (!Number.isInteger(parsed)) throw new Error(`${label} doit être un nombre entier.`)
  return parsed
}

export function AdminSettingsPanel({
  accessToken,
  settings,
  presets,
  onSettingsSaved,
  onPresetsSaved,
}: AdminSettingsPanelProps) {
  const [draft, setDraft] = useState<SettingsDraft>()
  const [presetDrafts, setPresetDrafts] = useState<VehiclePreset[]>([])
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string>()
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    if (settings) setDraft(makeDraft(settings))
  }, [settings])

  useEffect(() => setPresetDrafts(presets), [presets])

  if (!settings || !draft) {
    return <section className="admin-panel"><div className="admin-empty"><Settings2 className="admin-spin" aria-hidden="true" /><strong>Chargement des paramètres…</strong></div></section>
  }

  const currentSettings = settings
  const currentDraft = draft

  function updatePreset(index: number, field: 'maxWeightKg' | 'averageSpeedKmh', value: string) {
    setPresetDrafts((current) => current.map((preset, presetIndex) => presetIndex === index
      ? { ...preset, [field]: Number(value) }
      : preset))
    setSaved(false)
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setMessage(undefined)
    setSaved(false)
    const defaultHub = asConfirmedLocation(currentDraft.defaultHub)
    const hubStarted = Boolean(
      currentDraft.defaultHub.label?.trim()
      || currentDraft.defaultHub.destinationQuery?.trim()
      || currentDraft.defaultHub.latitude != null
      || currentDraft.defaultHub.longitude != null,
    )
    if (hubStarted && !defaultHub) {
      setMessage('Confirmez le point de ralliement par défaut sur la carte.')
      return
    }
    let generalSettingsSaved = false
    try {
      const input: OperationalSettings = {
        ...currentSettings,
        dailyPackageLimit: readPositive(currentDraft.dailyPackageLimit, 'La limite quotidienne'),
        defaultPackageWeightKg: readPositive(currentDraft.defaultPackageWeightKg, 'Le poids par défaut'),
        pickupServiceMinutes: readPositive(currentDraft.pickupServiceMinutes, 'La durée d’un retrait'),
        hubServiceMinutes: readPositive(currentDraft.hubServiceMinutes, 'La durée au siège'),
        deliveryServiceMinutes: readPositive(currentDraft.deliveryServiceMinutes, 'La durée d’une livraison'),
        earlyToleranceMinutes: readNonNegative(currentDraft.earlyToleranceMinutes, 'La tolérance d’avance'),
        lateToleranceMinutes: readNonNegative(currentDraft.lateToleranceMinutes, 'La tolérance de retard'),
        optimizationTimeLimitSeconds: readPositive(currentDraft.optimizationTimeLimitSeconds, 'Le temps de calcul', 1200),
        defaultCourierCommissionNumerator: readPositiveInteger(currentDraft.defaultCourierCommissionNumerator, 'Le numérateur de la part livreur'),
        defaultCourierCommissionDenominator: readPositiveInteger(currentDraft.defaultCourierCommissionDenominator, 'Le dénominateur de la part livreur'),
        defaultFuelPricePerLiterAriary: readPositiveInteger(currentDraft.defaultFuelPricePerLiterAriary, 'Le prix du litre'),
        defaultExpressReturnToPickup: currentDraft.defaultExpressReturnToPickup,
        defaultHub,
      }
      if (input.defaultCourierCommissionNumerator > input.defaultCourierCommissionDenominator) {
        throw new Error('La part du livreur ne peut pas dépasser la totalité des frais de livraison.')
      }
      for (const preset of presetDrafts) {
        readPositive(String(preset.maxWeightKg), `La capacité ${vehicleLabel(preset.vehicleType).toLowerCase()}`)
        readPositive(String(preset.averageSpeedKmh), `La vitesse ${vehicleLabel(preset.vehicleType).toLowerCase()}`)
      }

      setSaving(true)
      const savedSettings = await updateOperationalSettings(input, accessToken)
      onSettingsSaved(savedSettings)
      generalSettingsSaved = true
      const savedPresets = await updateVehiclePresets(presetDrafts, accessToken)
      onPresetsSaved(savedPresets)
      setSaved(true)
    } catch (error) {
      setMessage(generalSettingsSaved
        ? `Les paramètres généraux sont enregistrés, mais les préréglages des véhicules ne le sont pas : ${errorMessage(error)}`
        : errorMessage(error))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="admin-panel" aria-labelledby="settings-title">
      <header className="admin-panel__heading">
        <div><p className="admin-eyebrow">Règles par défaut</p><h2 id="settings-title">Paramètres</h2><p>Ces valeurs sont figées dans chaque plan lors du dispatch afin de conserver son historique exact.</p></div>
      </header>

      <form className="admin-settings-form" onSubmit={(event) => void submit(event)}>
        <section className="admin-settings-card">
          <header><span><PackageOpen aria-hidden="true" /></span><div><strong>Capacité quotidienne</strong><small>Déclenche l’avertissement présenté aux clients</small></div></header>
          <div className="admin-form-grid">
            <label><span>Maximum de colis par jour</span><input required type="number" min="1" step="1" value={draft.dailyPackageLimit} onChange={(event) => { setDraft({ ...draft, dailyPackageLimit: event.target.value }); setSaved(false) }} /></label>
            <label><span>Poids d’un colis non renseigné</span><div className="admin-input-unit"><input required type="number" min="0.01" step="0.01" value={draft.defaultPackageWeightKg} onChange={(event) => { setDraft({ ...draft, defaultPackageWeightKg: event.target.value }); setSaved(false) }} /><b>kg</b></div></label>
          </div>
        </section>

        <section className="admin-settings-card">
          <header><span><Warehouse aria-hidden="true" /></span><div><strong>Point de ralliement par défaut</strong><small>Utilisé automatiquement pour tous les dispatchs, sauf remplacement ponctuel</small></div></header>
          <LocationPicker
            accessToken={accessToken}
            value={draft.defaultHub}
            title="Point de ralliement par défaut"
            description="Confirmez le lieu où les livreurs se retrouvent après les retraits."
            onChange={(defaultHub) => { setDraft({ ...draft, defaultHub }); setSaved(false) }}
          />
        </section>

        <section className="admin-settings-card">
          <header><span><Clock3 aria-hidden="true" /></span><div><strong>Temps opérationnels</strong><small>Minutes ajoutées aux horaires pour chaque action sur le terrain</small></div></header>
          <div className="admin-form-grid admin-form-grid--three">
            <label><span>Durée d’un retrait</span><div className="admin-input-unit"><input required type="number" min="1" step="1" value={draft.pickupServiceMinutes} onChange={(event) => { setDraft({ ...draft, pickupServiceMinutes: event.target.value }); setSaved(false) }} /><b>min</b></div></label>
            <label><span>Redistribution au siège</span><div className="admin-input-unit"><input required type="number" min="1" step="1" value={draft.hubServiceMinutes} onChange={(event) => { setDraft({ ...draft, hubServiceMinutes: event.target.value }); setSaved(false) }} /><b>min</b></div></label>
            <label><span>Durée d’une livraison</span><div className="admin-input-unit"><input required type="number" min="1" step="1" value={draft.deliveryServiceMinutes} onChange={(event) => { setDraft({ ...draft, deliveryServiceMinutes: event.target.value }); setSaved(false) }} /><b>min</b></div></label>
          </div>
          <p className="admin-settings-hint"><TimerReset aria-hidden="true" />Référence actuelle : {draft.pickupServiceMinutes} min par retrait · {draft.hubServiceMinutes} min au siège · {draft.deliveryServiceMinutes} min par livraison.</p>
        </section>

        <section className="admin-settings-card">
          <header><span><Route aria-hidden="true" /></span><div><strong>Calcul des tournées</strong><small>Le mode utilise toujours les routes principales et le passage obligatoire au siège</small></div></header>
          <div className="admin-form-grid admin-form-grid--three">
            <label><span>Tolérance d’avance</span><div className="admin-input-unit"><input required type="number" min="0" step="1" value={draft.earlyToleranceMinutes} onChange={(event) => { setDraft({ ...draft, earlyToleranceMinutes: event.target.value }); setSaved(false) }} /><b>min</b></div></label>
            <label><span>Tolérance de retard</span><div className="admin-input-unit"><input required type="number" min="0" step="1" value={draft.lateToleranceMinutes} onChange={(event) => { setDraft({ ...draft, lateToleranceMinutes: event.target.value }); setSaved(false) }} /><b>min</b></div></label>
            <label><span>Temps maximal de calcul</span><div className="admin-input-unit"><input required type="number" min="1" max="1200" step="1" value={draft.optimizationTimeLimitSeconds} onChange={(event) => { setDraft({ ...draft, optimizationTimeLimitSeconds: event.target.value }); setSaved(false) }} /><b>sec</b></div></label>
          </div>
          <div className="admin-locked-setting"><MapPinned aria-hidden="true" /><div><small>Profil routier actif</small><strong>Routes principales · Madagascar</strong></div><span>Fixe</span></div>
        </section>

        <section className="admin-settings-card">
          <header><span><Bike aria-hidden="true" /></span><div><strong>Préréglages des véhicules</strong><small>Appliqués tant qu’un livreur ne possède pas de valeur personnalisée</small></div></header>
          <div className="admin-preset-grid">
            {presetDrafts.map((preset, index) => (
              <article key={preset.vehicleType}>
                <header><Bike aria-hidden="true" /><strong>{vehicleLabel(preset.vehicleType)}</strong></header>
                <label><span>Capacité maximale</span><div className="admin-input-unit"><input required type="number" min="0.01" step="0.01" value={Number.isNaN(preset.maxWeightKg) ? '' : preset.maxWeightKg} onChange={(event) => updatePreset(index, 'maxWeightKg', event.target.value)} /><b>kg</b></div></label>
                <label><span>Vitesse moyenne</span><div className="admin-input-unit"><input required type="number" min="0.01" step="0.01" value={Number.isNaN(preset.averageSpeedKmh) ? '' : preset.averageSpeedKmh} onChange={(event) => updatePreset(index, 'averageSpeedKmh', event.target.value)} /><b>km/h</b></div></label>
                <p><Gauge aria-hidden="true" />Ces valeurs restent modifiables livreur par livreur.</p>
              </article>
            ))}
          </div>
        </section>

        {message && <div className="admin-callout admin-callout--error" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Enregistrement impossible</strong><span>{message}</span></div></div>}
        {saved && <p className="admin-settings-success" role="status">Les paramètres opérationnels sont à jour.</p>}
        <div className="admin-settings-actions"><button className="admin-button" type="submit" disabled={saving}><Save aria-hidden="true" />{saving ? 'Enregistrement…' : 'Enregistrer les paramètres'}</button></div>
      </form>
    </section>
  )
}
