export type PricingDistanceMode = 'AIR_DISTANCE' | 'ROAD_DISTANCE'

export function pricingDistanceModeLabel(mode: PricingDistanceMode | undefined): string {
  return mode === 'ROAD_DISTANCE' ? 'distance routière' : 'distance à vol d’oiseau'
}

export function formatPricingDistance(meters: number): string {
  return `${(meters / 1_000).toLocaleString('fr-MG', {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  })} km`
}

export function formatPricingDistanceSummary(
  meters: number,
  mode: PricingDistanceMode | undefined,
): string {
  return `${formatPricingDistance(meters)} · ${pricingDistanceModeLabel(mode)}`
}
