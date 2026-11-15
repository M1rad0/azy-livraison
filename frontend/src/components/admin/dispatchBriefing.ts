import type { ManualDispatchDraft, ManualDraftCourier, ManualPackage, ManualPickup } from '../../api/manualDispatch'
import { formatOneHourWindow } from '../../deliveryWindows'
import { assignmentIds, manualTransfers } from './manualDispatchLayout'
import { dispatchPlaceText } from './manualDispatchPresentation'

export interface BriefingPickup {
  pickup: ManualPickup
  packages: ManualPackage[]
  clients: string[]
}

export interface BriefingExchange {
  courierId: string
  courierName: string
  packages: ManualPackage[]
}

export interface BriefingTransfer {
  fromCourierId: string
  fromCourierName: string
  toCourierId: string
  toCourierName: string
  packages: ManualPackage[]
}

export interface CourierBriefing {
  courier: ManualDraftCourier
  pickups: BriefingPickup[]
  give: BriefingExchange[]
  take: BriefingExchange[]
  deliveries: ManualPackage[]
}

export interface DispatchBriefing {
  couriers: CourierBriefing[]
  transfers: BriefingTransfer[]
  warnings: string[]
}

function text(value?: string | null) {
  return value?.trim().replace(/\s+/g, ' ') ?? ''
}

function recipient(item: ManualPackage) {
  return text(item.recipientName) || 'Destinataire non renseigné'
}

function sender(item: ManualPackage) {
  return text(item.serviceClientName) || 'Vendeur non renseigné'
}

/** Builds a read-only view; incomplete historical snapshots produce warnings, not invented assignments. */
export function buildDispatchBriefing(draft: ManualDispatchDraft): DispatchBriefing {
  const warnings = new Set<string>()
  const courierById = new Map<string, ManualDraftCourier>()
  for (const courier of draft.couriers) {
    if (courierById.has(courier.courierId)) {
      warnings.add('Un livreur apparaît plusieurs fois dans le dispatch.')
    } else {
      courierById.set(courier.courierId, courier)
    }
  }
  const packageById = new Map<string, ManualPackage>()
  for (const item of draft.packages) {
    if (packageById.has(item.packageId)) warnings.add('Un colis apparaît plusieurs fois dans le dispatch.')
    else packageById.set(item.packageId, item)
  }
  const pickupById = new Map<string, ManualPickup>()
  const packagePickup = new Set<string>()
  for (const pickup of draft.pickups) {
    if (pickupById.has(pickup.id)) {
      warnings.add('Un point de récupération apparaît plusieurs fois dans le dispatch.')
      continue
    }
    const packageIds: string[] = []
    for (const id of pickup.packageIds) {
      if (!packageById.has(id)) {
        warnings.add('Un point de récupération fait référence à un colis absent du dispatch.')
      } else if (packagePickup.has(id)) {
        warnings.add('Un colis est associé plusieurs fois à une récupération.')
      } else {
        packagePickup.add(id)
        packageIds.push(id)
      }
    }
    pickupById.set(pickup.id, { ...pickup, packageIds })
  }

  const collected = new Set<string>()
  const collections = draft.collections.filter((assignment) => {
    if (!courierById.has(assignment.courierId) || !pickupById.has(assignment.pickupId)) {
      warnings.add('Une affectation de récupération fait référence à un livreur ou à un point absent du dispatch.')
      return false
    }
    if (collected.has(assignment.pickupId)) {
      warnings.add('Une récupération est affectée plusieurs fois ; vérifier sa répartition.')
      return false
    }
    collected.add(assignment.pickupId)
    return true
  })
  const delivered = new Set<string>()
  const deliveries = draft.deliveries.filter((assignment) => {
    if (!courierById.has(assignment.courierId) || !packageById.has(assignment.packageId)) {
      warnings.add('Une affectation de livraison fait référence à un livreur ou à un colis absent du dispatch.')
      return false
    }
    if (delivered.has(assignment.packageId)) {
      warnings.add('Un colis est affecté plusieurs fois ; vérifier sa répartition.')
      return false
    }
    delivered.add(assignment.packageId)
    return true
  })
  for (const pickup of pickupById.values()) {
    if (pickup.packageIds.length > 0 && !collected.has(pickup.id)) {
      warnings.add(`Une récupération n’est affectée à aucun livreur : ${dispatchPlaceText(pickup.location)}.`)
    }
  }
  for (const item of packageById.values()) {
    if (!packagePickup.has(item.packageId)) warnings.add(`Le point de récupération de ${recipient(item)} est absent du dispatch.`)
    if (!delivered.has(item.packageId)) warnings.add(`Aucun livreur n’est affecté à la livraison de ${recipient(item)}.`)
  }

  const couriers = [...courierById.values()]
  const normalizedDraft = {
    ...draft,
    couriers,
    packages: [...packageById.values()],
    pickups: [...pickupById.values()],
    collections,
    deliveries,
  }
  const courierName = (id: string) => {
    const courier = courierById.get(id)!
    const name = text(courier.name) || 'Livreur sans nom'
    const sameName = couriers.filter((item) => text(item.name).toLocaleLowerCase('fr') === text(courier.name).toLocaleLowerCase('fr')).length > 1
    return sameName && text(courier.contact) ? `${name} (${text(courier.contact)})` : name
  }
  const transferByPair = new Map<string, BriefingTransfer>()
  for (const transfer of manualTransfers(normalizedDraft)) {
    const key = JSON.stringify([transfer.from, transfer.to])
    const group = transferByPair.get(key) ?? {
      fromCourierId: transfer.from,
      fromCourierName: courierName(transfer.from),
      toCourierId: transfer.to,
      toCourierName: courierName(transfer.to),
      packages: [],
    }
    group.packages.push(transfer.package)
    transferByPair.set(key, group)
  }
  const transfers = [...transferByPair.values()]
  // Final delivery order is also the useful order when handing packages to a peer.
  const order = new Map(deliveries.map((item) => [item.packageId, item.order]))
  for (const transfer of transfers) transfer.packages.sort((a, b) => order.get(a.packageId)! - order.get(b.packageId)!)

  return {
    couriers: couriers.map((courier) => ({
      courier,
      pickups: assignmentIds(normalizedDraft, 'COLLECTION', courier.courierId).map((id) => {
        const pickup = pickupById.get(id)!
        const packages = pickup.packageIds.map((packageId) => packageById.get(packageId)!)
        return { pickup, packages, clients: [...new Set(packages.map(sender))] }
      }),
      give: transfers.filter((transfer) => transfer.fromCourierId === courier.courierId).map((transfer) => ({
        courierId: transfer.toCourierId, courierName: transfer.toCourierName, packages: transfer.packages,
      })),
      take: transfers.filter((transfer) => transfer.toCourierId === courier.courierId).map((transfer) => ({
        courierId: transfer.fromCourierId, courierName: transfer.fromCourierName, packages: transfer.packages,
      })),
      deliveries: assignmentIds(normalizedDraft, 'DELIVERY', courier.courierId).map((id) => packageById.get(id)!),
    })),
    transfers,
    warnings: [...warnings],
  }
}

function packageDescription(item: ManualPackage) {
  return `${recipient(item)} — ${text(dispatchPlaceText(item.destination))} — vendeur : ${sender(item)}`
}

function displayDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  return match ? `${match[3]}/${match[2]}/${match[1]}` : text(value)
}

/** Plain editable text: no routing estimates, technical identifiers or meeting location are invented. */
export function generateCourierBriefing(briefing: CourierBriefing, serviceDate: string): string {
  const lines = [`Briefing du ${displayDate(serviceDate)} — ${text(briefing.courier.name) || 'Livreur sans nom'}`, '', 'Récupérations :']
  if (briefing.pickups.length === 0) lines.push('Aucune récupération.')
  briefing.pickups.forEach(({ pickup, packages, clients }, index) => {
    lines.push(`${index + 1}. ${text(dispatchPlaceText(pickup.location))} — ${clients.map((client) => text(client)).join(', ') || 'Vendeur non renseigné'} — ${packages.length} colis.`)
  })

  lines.push('', 'Échanges :')
  const peers = new Map([...briefing.give, ...briefing.take].map((exchange) => [exchange.courierId, exchange.courierName]))
  if (peers.size === 0) lines.push('Aucun échange de colis.')
  else {
    lines.push(`Rencontre avec ${[...peers.values()].join(' et ')}.`)
    for (const exchange of briefing.give) {
      lines.push(`Donner à ${exchange.courierName} :`)
      exchange.packages.forEach((item) => lines.push(`- ${packageDescription(item)}`))
    }
    for (const exchange of briefing.take) {
      lines.push(`Prendre chez ${exchange.courierName} :`)
      exchange.packages.forEach((item) => lines.push(`- ${packageDescription(item)}`))
    }
  }

  lines.push('', 'Ta liste finale de colis :')
  if (briefing.deliveries.length === 0) lines.push('Aucune livraison.')
  briefing.deliveries.forEach((item, index) => {
    const window = formatOneHourWindow(item.desiredDeliveryTime, item.desiredDeliveryEndTime, undefined, item.deliveryTimePreference)
    const details = [
      packageDescription(item),
      text(item.contact) ? `contact : ${text(item.contact)}` : undefined,
      `à encaisser : ${new Intl.NumberFormat('fr-FR', { useGrouping: false }).format(item.amountToCollectAriary)} Ar`,
      window ? `créneau : ${window}` : undefined,
    ].filter(Boolean)
    lines.push(`${index + 1}. ${details.join(' — ')}`)
  })
  return lines.join('\n')
}
