import { useState } from 'react'
import {
  ArrowLeft,
  ArrowRight,
  ArrowRightLeft,
  CheckCircle2,
  Clock3,
  Download,
  FileSpreadsheet,
  MapPinned,
  PackageCheck,
  Route as RouteIcon,
  Sparkles,
  Timer,
  Users,
} from 'lucide-react'
import type {
  CourierHubManifest,
  OptimizationComparisonResult,
  OptimizationScenario,
  OptimizationStrategy,
  PackageReference,
  StrategyMetrics,
} from '../api/optimization'
import type { MultiClientDeliveryPlan } from '../api/plans'
import { OptimizationResults } from './OptimizationResults'

interface Props {
  comparison: OptimizationComparisonResult
  plan: MultiClientDeliveryPlan
  accessToken: string
  onBack: () => void
}

function formatDuration(seconds: number) {
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} min`
  const remaining = minutes % 60
  return `${Math.floor(minutes / 60)} h${remaining ? ` ${String(remaining).padStart(2, '0')}` : ''}`
}

function formatDistance(meters: number) {
  return `${(meters / 1_000).toLocaleString('fr-MG', { maximumFractionDigits: 1 })} km`
}

function formatDateTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('fr-MG', {
    timeZone: 'Indian/Antananarivo',
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function formatTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('fr-MG', {
    timeZone: 'Indian/Antananarivo',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function formatLocation(location: { label?: string; destinationQuery?: string; latitude?: number; longitude?: number }) {
  if (location.label) return location.label
  if (location.destinationQuery) return location.destinationQuery
  if (Number.isFinite(location.latitude) && Number.isFinite(location.longitude)) {
    return `${location.latitude?.toFixed(5)}, ${location.longitude?.toFixed(5)}`
  }
  return 'Siège confirmé'
}

function csvCell(value: string | number | undefined) {
  return `"${String(value ?? '').replaceAll('"', '""')}"`
}

function download(content: string, type: string, filename: string) {
  const blob = new Blob([content], { type })
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(objectUrl)
}

function scenarioName(strategy: OptimizationStrategy) {
  return strategy === 'DIRECT' ? 'Direct' : 'Via le siège'
}

function ScenarioMetrics({ metrics }: { metrics: StrategyMetrics }) {
  return (
    <dl className="comparison-scenario__metrics">
      <div><dt>Colis ponctuels</dt><dd>{metrics.onTimePackageCount}<small> / {metrics.assignedPackageCount}</small></dd></div>
      <div><dt>Retard</dt><dd>{metrics.latePackageCount ? formatDuration(metrics.totalLatenessSeconds) : 'Aucun'}<small>{metrics.latePackageCount ? ` · ${metrics.latePackageCount} colis` : ''}</small></dd></div>
      <div><dt>Distance</dt><dd>{formatDistance(metrics.totalDistanceMeters)}</dd></div>
      <div><dt>Fin</dt><dd>{formatDateTime(metrics.completedAt)}</dd></div>
      <div><dt>Livreurs</dt><dd>{metrics.usedCourierCount}</dd></div>
    </dl>
  )
}

function packageNames(items: PackageReference[]) {
  if (items.length === 0) return 'Aucun'
  return items.map((item) => item.packageLabel || item.packageId).join(', ')
}

function ManifestCard({ manifest }: { manifest: CourierHubManifest }) {
  const roles = manifest.roles.map((role) => role === 'COLLECTION' ? 'Collecte' : 'Distribution').join(' · ')
  return (
    <article className="hub-manifest-card">
      <header>
        <span><Users aria-hidden="true" /></span>
        <div><small>{roles}</small><h3>{manifest.courierName}</h3></div>
        <strong>{manifest.loadAtArrivalKg.toLocaleString('fr-MG')} → {manifest.loadAtDepartureKg.toLocaleString('fr-MG')} kg</strong>
      </header>
      <div className="hub-manifest-card__timing">
        <span>Arrivée <strong>{formatTime(manifest.arrivedAt)}</strong></span>
        <span>Départ <strong>{formatTime(manifest.departedAt)}</strong></span>
        <span>Attente <strong>{formatDuration(manifest.waitingSeconds)}</strong></span>
      </div>
      <dl>
        <div><dt>Récupérés</dt><dd>{packageNames(manifest.collectedPackages)}</dd></div>
        <div><dt>Gardés</dt><dd>{packageNames(manifest.keptPackages)}</dd></div>
        <div><dt>Remis</dt><dd>{packageNames(manifest.transferredOutPackages)}</dd></div>
        <div><dt>Pris au siège</dt><dd>{packageNames(manifest.receivedPackages)}</dd></div>
        <div><dt>À livrer</dt><dd>{packageNames(manifest.deliveredPackages)}</dd></div>
      </dl>
    </article>
  )
}

export function OptimizationComparisonResults({ comparison, plan, accessToken, onBack }: Props) {
  const [selectedStrategy, setSelectedStrategy] = useState<OptimizationStrategy>(comparison.recommendedStrategy)
  const selectedScenario: OptimizationScenario = selectedStrategy === 'DIRECT' ? comparison.direct : comparison.viaHub
  const recommendedName = scenarioName(comparison.recommendedStrategy)

  function exportComparisonJson() {
    download(
      `${JSON.stringify(comparison, null, 2)}\n`,
      'application/json;charset=utf-8',
      `${comparison.sourcePlanId || 'resultat-azy'}-comparaison.json`,
    )
  }

  function exportComparisonCsv() {
    const direct = comparison.direct.metrics
    const hub = comparison.viaHub.metrics
    const rows: Array<Array<string | number | undefined>> = [
      ['Critère', 'Direct', 'Via le siège', 'Écart siège - direct'],
      ['Colis livrés', direct.assignedPackageCount, hub.assignedPackageCount, comparison.delta.assignedPackageCount],
      ['Colis ponctuels', direct.onTimePackageCount, hub.onTimePackageCount, comparison.delta.onTimePackageCount],
      ['Colis en retard', direct.latePackageCount, hub.latePackageCount, hub.latePackageCount - direct.latePackageCount],
      ['Retard total (secondes)', direct.totalLatenessSeconds, hub.totalLatenessSeconds, comparison.delta.totalLatenessSeconds],
      ['Distance totale (mètres)', direct.totalDistanceMeters, hub.totalDistanceMeters, comparison.delta.totalDistanceMeters],
      ['Fin estimée', direct.completedAt, hub.completedAt, comparison.delta.completionSeconds],
      ['Livreurs mobilisés', direct.usedCourierCount, hub.usedCourierCount, comparison.delta.usedCourierCount],
      ['Stratégie recommandée', comparison.recommendedStrategy === 'DIRECT' ? 'Oui' : '', comparison.recommendedStrategy === 'VIA_HUB' ? 'Oui' : '', ''],
    ]
    const csv = `\uFEFF${rows.map((row) => row.map(csvCell).join(';')).join('\r\n')}\r\n`
    download(csv, 'text/csv;charset=utf-8', `${comparison.sourcePlanId || 'resultat-azy'}-comparaison.csv`)
  }

  return (
    <div className="optimization-comparison">
      <section className="comparison-hero" aria-labelledby="comparison-title">
        <div className="comparison-hero__topline">
          <button className="button button--light" type="button" onClick={onBack}><ArrowLeft aria-hidden="true" />Modifier le plan</button>
          <span><Sparkles aria-hidden="true" />Recommandation · {recommendedName}</span>
        </div>
        <div className="comparison-hero__content">
          <div>
            <p className="eyebrow">Comparaison · {plan.name}</p>
            <h1 id="comparison-title">Deux façons d’organiser la journée.</h1>
            <p>{comparison.recommendationReason}</p>
          </div>
          <div className="comparison-export-actions">
            <button className="button button--light" type="button" onClick={exportComparisonCsv}><FileSpreadsheet aria-hidden="true" />Comparatif CSV</button>
            <button className="button" type="button" onClick={exportComparisonJson}><Download aria-hidden="true" />Résultat complet</button>
          </div>
        </div>
      </section>

      <section className="comparison-options" aria-label="Résultats des deux stratégies">
        {[comparison.direct, comparison.viaHub].map((scenario) => {
          const selected = selectedStrategy === scenario.strategy
          const recommended = comparison.recommendedStrategy === scenario.strategy
          return (
            <button
              className={`comparison-scenario${selected ? ' is-selected' : ''}${recommended ? ' is-recommended' : ''}`}
              type="button"
              aria-pressed={selected}
              onClick={() => setSelectedStrategy(scenario.strategy)}
              key={scenario.strategy}
            >
              <header>
                <span>{scenario.strategy === 'DIRECT' ? <RouteIcon aria-hidden="true" /> : <MapPinned aria-hidden="true" />}</span>
                <div><small>{scenario.strategy === 'DIRECT' ? 'Sans échange intermédiaire' : 'Redistribution au point de rencontre'}</small><h2>{scenarioName(scenario.strategy)}</h2></div>
                {recommended && <b><CheckCircle2 aria-hidden="true" />Recommandée</b>}
              </header>
              <ScenarioMetrics metrics={scenario.metrics} />
            </button>
          )
        })}
      </section>

      <div className="comparison-policy" aria-label="Règles opérationnelles appliquées">
        <span><Clock3 aria-hidden="true" /><strong>{formatDuration(comparison.operationalPolicy.pickupServiceSeconds)}</strong> par retrait</span>
        <span><ArrowRightLeft aria-hidden="true" /><strong>{formatDuration(comparison.operationalPolicy.hubTransferSeconds)}</strong> au siège</span>
        <span><Timer aria-hidden="true" /><strong>{formatDuration(comparison.operationalPolicy.deliveryServiceSeconds)}</strong> par livraison</span>
        <span><PackageCheck aria-hidden="true" /><strong>{formatDuration(comparison.operationalPolicy.deliveryWindowLateToleranceSeconds)}</strong> de tolérance maximale</span>
      </div>

      <div className="comparison-switch" role="tablist" aria-label="Scénario affiché en détail">
        <button type="button" role="tab" aria-selected={selectedStrategy === 'DIRECT'} className={selectedStrategy === 'DIRECT' ? 'is-active' : ''} onClick={() => setSelectedStrategy('DIRECT')}><RouteIcon aria-hidden="true" />Direct</button>
        <button type="button" role="tab" aria-selected={selectedStrategy === 'VIA_HUB'} className={selectedStrategy === 'VIA_HUB' ? 'is-active' : ''} onClick={() => setSelectedStrategy('VIA_HUB')}><MapPinned aria-hidden="true" />Via le siège</button>
      </div>

      {selectedStrategy === 'VIA_HUB' && (
        <section className="hub-execution" aria-labelledby="hub-execution-title">
          <div className="hub-execution__heading">
            <span><MapPinned aria-hidden="true" /></span>
            <div><p className="eyebrow">Passage au siège</p><h2 id="hub-execution-title">Qui remet, garde ou prend chaque colis</h2><small>{formatLocation(comparison.hubExecution.hub)}</small></div>
            <div><small>Rendez-vous</small><strong>{formatDateTime(comparison.hubExecution.meetingAt)}</strong><span>Redistribution terminée à {formatTime(comparison.hubExecution.redistributionCompletedAt)}</span></div>
          </div>

          <div className="hub-transfer-list">
            {comparison.hubExecution.packageTransfers.map((transfer) => (
              <article key={transfer.deliveryPackage.packageId} className={transfer.transferred ? 'is-transferred' : 'is-kept'}>
                <span className="hub-transfer-list__package"><PackageCheck aria-hidden="true" /><strong>{transfer.deliveryPackage.packageLabel}</strong><small>{transfer.deliveryPackage.weightKg.toLocaleString('fr-MG')} kg</small></span>
                {!transfer.distributorCourierId ? (
                  <div><strong>{transfer.collectorCourierName}</strong><b>dépose au siège · livraison non affectée</b></div>
                ) : transfer.transferred ? (
                  <div><strong>{transfer.collectorCourierName}</strong><b>remet</b><ArrowRight aria-hidden="true" /><strong>{transfer.distributorCourierName}</strong><b>prend</b></div>
                ) : (
                  <div><strong>{transfer.collectorCourierName}</strong><b>garde le colis</b></div>
                )}
              </article>
            ))}
            {comparison.hubExecution.packageTransfers.length === 0 && <p>Aucun transfert de colis n’est nécessaire.</p>}
          </div>

          <div className="hub-manifest-grid">
            {comparison.hubExecution.manifests.map((manifest) => <ManifestCard manifest={manifest} key={manifest.courierId} />)}
          </div>
        </section>
      )}

      <section className="comparison-selected-heading">
        <div><small>Scénario affiché</small><h2>{scenarioName(selectedStrategy)}</h2></div>
        <span>{selectedScenario.metrics.assignedPackageCount} colis livrés · {formatDistance(selectedScenario.metrics.totalDistanceMeters)}</span>
      </section>
      <OptimizationResults
        key={selectedStrategy}
        embedded
        strategy={selectedStrategy}
        hubExecution={selectedStrategy === 'VIA_HUB' ? comparison.hubExecution : undefined}
        result={selectedScenario.result}
        plan={plan}
        accessToken={accessToken}
      />
    </div>
  )
}
