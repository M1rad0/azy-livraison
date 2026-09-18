import { useEffect, useState } from 'react'
import { BadgeDollarSign, Clock3, LoaderCircle, MapPin, Scale, Zap } from 'lucide-react'
import { getClientPricingPolicies, type DeliveryPricingPolicy } from '../api/client'
import { pricingDistanceModeLabel } from '../api/pricing'

function formatAriary(value: number) { return `${value.toLocaleString('fr-MG')} Ar` }

export function PricingTransparencyPanel({ accessToken }: { accessToken: string }) {
  const [policies, setPolicies] = useState<DeliveryPricingPolicy[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string>()

  useEffect(() => {
    void getClientPricingPolicies(accessToken).then(setPolicies)
      .catch((failure) => setError(failure instanceof Error ? failure.message : 'Tarifs indisponibles.'))
      .finally(() => setLoading(false))
  }, [accessToken])

  if (loading) return <section className="client-portal-state"><LoaderCircle className="spin" /><strong>Chargement de vos tarifs…</strong></section>
  if (error) return <p className="client-alert client-alert--error">{error}</p>

  return (
    <section className="client-pricing-page">
      <header className="client-page-heading"><div><p className="eyebrow">Transparence</p><h1>Mes tarifs</h1><p>Le tarif affiché lors de l’enregistrement est conservé avec chaque colis.</p></div></header>
      <div className="client-pricing-policy-grid">
        {policies.map((policy) => (
          <article className="client-surface client-pricing-policy" key={policy.requestKind}>
            <header><span>{policy.requestKind === 'EXPRESS' ? <Zap /> : <BadgeDollarSign />}</span><div><p className="eyebrow">{policy.clientSpecific ? 'Tarification personnalisée' : 'Tarification AZY'}</p><h2>{policy.requestKind === 'EXPRESS' ? 'Livraison express' : 'Livraison normale'}</h2></div></header>
            <p className="client-pricing-policy__center"><MapPin /><span>Centre de calcul : <strong>{policy.center.label}</strong><small>Mode : {pricingDistanceModeLabel(policy.distanceMode)}</small></span></p>
            <div className="client-pricing-table"><div><strong>Zone</strong><strong>Jusqu’à</strong><strong>Tarif</strong></div>{policy.zones.map((zone) => <div key={zone.id}><span>{zone.name}</span><span>{(zone.maxDistanceMeters / 1000).toLocaleString('fr-MG')} km</span><strong>{formatAriary(zone.deliveryFeeAriary)}</strong></div>)}</div>
            {policy.weightTiers.length > 0 && <section className="client-pricing-addons"><h3><Scale />Suppléments de poids</h3>{policy.weightTiers.map((tier) => <p key={tier.id}><span>Jusqu’à {tier.maxWeightKg} kg</span><strong>+ {formatAriary(tier.surchargeAriary)}</strong></p>)}<small>Poids utilisé en l’absence de saisie : {policy.defaultWeightKg} kg.</small></section>}
            {policy.timeSlots.length > 0 && <section className="client-pricing-addons"><h3><Clock3 />Créneaux horaires</h3>{policy.timeSlots.map((slot) => <p key={slot.id}><span>{slot.label} · {slot.startTime.slice(0, 5)}–{slot.endTime.slice(0, 5)}</span><strong>+ {formatAriary(slot.surchargeAriary)}</strong></p>)}</section>}
          </article>
        ))}
      </div>
    </section>
  )
}
