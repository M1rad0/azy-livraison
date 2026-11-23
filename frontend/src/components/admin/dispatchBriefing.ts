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

export type BriefingLanguage = 'fr' | 'mg'

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

const BRIEFING_LABELS = {
  fr: {
    header: 'Briefing du', courierUnknown: 'Livreur sans nom',
    pickups: 'Récupérations :', noPickups: 'Aucune récupération.',
    sellerUnknown: 'Vendeur non renseigné', sellerContact: 'contact vendeur :',
    sellerContactMissing: 'contact vendeur non renseigné', sellerContactUnavailable: 'contact vendeur indisponible',
    packages: 'colis.', exchanges: 'Échanges :', noExchanges: 'Aucun échange de colis.',
    meet: 'Rencontre avec', and: ' et ', give: 'Donner à', take: 'Prendre chez',
    deliveries: 'Ta liste finale de colis :', noDeliveries: 'Aucune livraison.',
    contact: 'contact :', contactMissing: 'contact non renseigné',
    collect: 'à encaisser :', window: 'créneau :',
  },
  mg: {
    header: 'Toromarika ny', courierUnknown: 'Mpanatitra tsy voatondro',
    pickups: 'Entana alaina :', noPickups: 'Tsy misy entana alaina.',
    sellerUnknown: 'Mpivarotra tsy voatondro', sellerContact: "laharan'ny mpivarotra :",
    sellerContactMissing: "laharan'ny mpivarotra tsy voatondro", sellerContactUnavailable: "laharan'ny mpivarotra tsy azo alaina",
    packages: 'entana.', exchanges: 'Fifanakalozana entana :', noExchanges: 'Tsy misy fifanakalozana entana.',
    meet: "Miona amin'i", and: ' sy ', give: "Omena an'i", take: "Alaina any amin'i",
    deliveries: 'Ireto avy ny livraisons ataonao :', noDeliveries: 'Tsy misy livraison.',
    contact: 'laharana :', contactMissing: 'laharana tsy voatondro',
    collect: 'vola alaina :', window: 'fotoana :',
  },
} as const

function packageDescription(item: ManualPackage, language: BriefingLanguage) {
  const labels = BRIEFING_LABELS[language]
  const contact = text(item.contact)
  return `${text(dispatchPlaceText(item.destination))} — ${contact ? `${labels.contact} ${contact}` : labels.contactMissing}`
}

function displayDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  return match ? `${match[3]}/${match[2]}/${match[1]}` : text(value)
}

/** Plain editable text: no routing estimates, technical identifiers or meeting location are invented. */
export function generateCourierBriefing(
  briefing: CourierBriefing,
  serviceDate: string,
  sellerContacts?: Readonly<Record<string, string | null | undefined>>,
  language: BriefingLanguage = 'fr',
  payouts: ReadonlyArray<{ courierAccountId: string; sellerName: string; sellerPayoutNumber?: string | null; sellerPayoutAccountHolder?: string | null; sellerPayoutOperator?: string | null; amountAriary: number; status: string }> = [],
  courierTransfers: ReadonlyArray<{ fromCourierAccountId: string; fromCourierName: string; toCourierAccountId: string; toCourierName: string; amountAriary: number; status: string }> = [],
): string {
  const labels = BRIEFING_LABELS[language]
  const lines = [`${labels.header} ${displayDate(serviceDate)} — ${text(briefing.courier.name) || labels.courierUnknown}`, '', labels.pickups]
  if (briefing.pickups.length === 0) lines.push(labels.noPickups)
  briefing.pickups.forEach(({ pickup, packages }, index) => {
    const sellers = new Map<string, ManualPackage>()
    for (const item of packages) {
      if (!sellers.has(item.ownerAccountId)) sellers.set(item.ownerAccountId, item)
    }
    const contacts = [...sellers.values()].map((item) => {
      const available = sellerContacts && Object.hasOwn(sellerContacts, item.ownerAccountId)
      const contact = available ? text(sellerContacts[item.ownerAccountId]) : ''
      const contactLabel = available
        ? contact ? `${labels.sellerContact} ${contact}` : labels.sellerContactMissing
        : labels.sellerContactUnavailable
      return `${text(item.serviceClientName) || labels.sellerUnknown} — ${contactLabel}`
    })
    lines.push(`${index + 1}. ${text(dispatchPlaceText(pickup.location))} — ${contacts.join(' ; ') || `${labels.sellerUnknown} — ${labels.sellerContactUnavailable}`} — ${packages.length} ${labels.packages}`)
  })

  lines.push('', labels.exchanges)
  const peers = new Map([...briefing.give, ...briefing.take].map((exchange) => [exchange.courierId, exchange.courierName]))
  if (peers.size === 0) lines.push(labels.noExchanges)
  else {
    lines.push(`${labels.meet} ${[...peers.values()].join(labels.and)}.`)
    for (const exchange of briefing.give) {
      lines.push(`${labels.give} ${exchange.courierName} :`)
      exchange.packages.forEach((item) => lines.push(`- ${packageDescription(item, language)}`))
    }
    for (const exchange of briefing.take) {
      lines.push(`${labels.take} ${exchange.courierName} :`)
      exchange.packages.forEach((item) => lines.push(`- ${packageDescription(item, language)}`))
    }
  }

  lines.push('', labels.deliveries)
  if (briefing.deliveries.length === 0) lines.push(labels.noDeliveries)
  briefing.deliveries.forEach((item, index) => {
    const formattedWindow = formatOneHourWindow(item.desiredDeliveryTime, item.desiredDeliveryEndTime, undefined, item.deliveryTimePreference)
    const window = language === 'mg'
      ? formattedWindow === 'Matin' ? 'Maraina'
        : formattedWindow === 'Soir' ? 'Hariva'
          : formattedWindow?.replace(/\(lendemain\)$/, '(ny ampitso)')
      : formattedWindow
    const details = [
      packageDescription(item, language),
      `${labels.collect} ${new Intl.NumberFormat('fr-FR', { useGrouping: false }).format(item.amountToCollectAriary)} Ar`,
      window ? `${labels.window} ${window}` : undefined,
    ].filter(Boolean)
    lines.push(`${index + 1}. ${details.join(' — ')}`)
  })
  const cashInstructions = courierTransfers.filter((transfer) => transfer.status === 'PENDING'
    && (transfer.fromCourierAccountId === briefing.courier.courierId || transfer.toCourierAccountId === briefing.courier.courierId))
  if (cashInstructions.length) {
    lines.push('', language === 'mg' ? 'Fifamindram-bola amin’ny livreurs :' : 'Transferts entre livreurs :')
    // Receiving money comes first so the payer's instructions read in execution order.
    for (const transfer of cashInstructions.filter((item) => item.toCourierAccountId === briefing.courier.courierId)) {
      lines.push(language === 'mg'
        ? `- Mandray ${transfer.amountAriary} Ar avy amin'i ${text(transfer.fromCourierName)}.`
        : `- Recevoir ${transfer.amountAriary} Ar de ${text(transfer.fromCourierName)}.`)
    }
    for (const transfer of cashInstructions.filter((item) => item.fromCourierAccountId === briefing.courier.courierId)) {
      lines.push(language === 'mg'
        ? `- Manome ${transfer.amountAriary} Ar an'i ${text(transfer.toCourierName)}.`
        : `- Donner ${transfer.amountAriary} Ar à ${text(transfer.toCourierName)}.`)
    }
    if (cashInstructions.some((item) => item.toCourierAccountId === briefing.courier.courierId)) {
      lines.push(language === 'mg' ? 'Andraso aloha ny vola vao manao ny fandoavana miankina aminy.'
        : 'Attendre les fonds avant les versements qui en dépendent.')
    }
  }
  const pending = payouts.filter((payout) => payout.courierAccountId === briefing.courier.courierId && payout.status === 'PENDING')
  if (pending.length) {
    lines.push('', language === 'mg' ? 'Vola halefa :' : 'Versements à faire :')
    for (const payout of pending) lines.push(`- ${text(payout.sellerName)} — ${text(payout.sellerPayoutOperator) || 'MVola'} : ${text(payout.sellerPayoutNumber)} — ${language === 'mg' ? 'Anarana' : 'Titulaire'} : ${text(payout.sellerPayoutAccountHolder) || '—'} — ${payout.amountAriary} Ar`)
  }
  return lines.join('\n')
}
