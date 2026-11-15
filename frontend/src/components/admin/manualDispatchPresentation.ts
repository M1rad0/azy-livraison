type DisplayLocation = {
  label?: string | null
  destinationQuery?: string | null
  query?: string | null
}

function normalized(value: string) {
  return value.split(',').map((part) => part.trim().replace(/\s+/g, ' ')).join(',').toLocaleLowerCase('fr')
}

/** Display only: preserve complete labels, queries and coordinates in the draft. */
export function shortDispatchAddress(value?: string | null) {
  const seen = new Set<string>()
  return (value ?? '').split(',').map((part) => part.trim()).filter((part) => {
    const key = normalized(part)
    if (!key || seen.has(key)) return false
    seen.add(key)
    return true
  }).slice(0, 2).join(', ')
}

export function dispatchPlace(location?: DisplayLocation | null) {
  const query = location?.destinationQuery?.trim() || location?.query?.trim() || ''
  const address = shortDispatchAddress(query)
  const label = location?.label?.trim() || ''
  // Labels copied from geocoding are addresses too; retain actual custom labels verbatim.
  const title = label && normalized(label) !== normalized(query) ? label : address || label || 'Lieu non renseigné'
  return { title, address: address && normalized(address) !== normalized(title) ? address : undefined }
}

export function dispatchPlaceText(location?: DisplayLocation | null) {
  const place = dispatchPlace(location)
  return [place.title, place.address].filter(Boolean).join(' · ')
}
