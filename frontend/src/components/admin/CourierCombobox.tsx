import { Check, ChevronDown, Search, UserRound, X } from 'lucide-react'
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'

export interface CourierComboboxOption {
  id: string
  name: string
  contact?: string | null
  phoneNumber?: string | null
  active: boolean
}

function normalize(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLocaleLowerCase('fr')
    .trim()
}

function courierLabel(courier: CourierComboboxOption): string {
  return `${courier.name}${courier.active ? '' : ' (inactif)'}`
}

export function CourierCombobox({
  couriers,
  value,
  inputId: providedInputId,
  ariaLabel,
  missingLabel,
  describedBy,
  onChange,
}: {
  couriers: CourierComboboxOption[]
  value: string
  inputId?: string
  ariaLabel?: string
  missingLabel?: string
  describedBy?: string
  onChange: (courierId: string) => void
}) {
  const generatedInputId = useId()
  const inputId = providedInputId ?? generatedInputId
  const listboxId = `${inputId}-listbox`
  const rootRef = useRef<HTMLDivElement>(null)
  const clearingSelectionFromTyping = useRef(false)
  const selectedCourier = couriers.find((courier) => courier.id === value)
  const selectedLabel = selectedCourier ? courierLabel(selectedCourier) : value ? (missingLabel ?? 'Livreur enregistré') : ''
  const [query, setQuery] = useState(selectedLabel)
  const [open, setOpen] = useState(false)
  const [highlightedIndex, setHighlightedIndex] = useState(0)

  useEffect(() => {
    if (clearingSelectionFromTyping.current) {
      clearingSelectionFromTyping.current = false
      return
    }
    setQuery(selectedLabel)
  }, [selectedLabel])

  const options = useMemo(() => {
    const search = normalize(query)
    return [...couriers]
      .filter((courier) => {
        if (!search || courier.id === value) return true
        return normalize(`${courier.name} ${courier.phoneNumber ?? courier.contact ?? ''}`).includes(search)
      })
      .sort((left, right) => Number(right.active) - Number(left.active)
        || left.name.localeCompare(right.name, 'fr'))
  }, [couriers, query, value])
  const showMissingSelection = Boolean(value && !selectedCourier && missingLabel
    && (!normalize(query) || normalize(missingLabel).includes(normalize(query))))

  useEffect(() => {
    setHighlightedIndex((current) => Math.min(current, Math.max(options.length - 1, 0)))
  }, [options.length])

  function select(courier: CourierComboboxOption) {
    onChange(courier.id)
    setQuery(courierLabel(courier))
    setOpen(false)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setOpen(true)
      setHighlightedIndex((current) => Math.min(current + (open ? 1 : 0), Math.max(options.length - 1, 0)))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setOpen(true)
      setHighlightedIndex((current) => Math.max(current - 1, 0))
    } else if (event.key === 'Enter' && open && options[highlightedIndex]) {
      event.preventDefault()
      select(options[highlightedIndex])
    } else if (event.key === 'Escape' && open) {
      event.preventDefault()
      setOpen(false)
      setQuery(selectedLabel)
    }
  }

  return (
    <div
      className="admin-courier-combobox"
      ref={rootRef}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) {
          setOpen(false)
          setQuery(selectedLabel)
        }
      }}
    >
      <div className="admin-courier-combobox__input">
        <Search aria-hidden="true" />
        <input
          id={inputId}
          type="text"
          role="combobox"
          autoComplete="off"
          aria-autocomplete="list"
          aria-label={ariaLabel}
          aria-expanded={open}
          aria-controls={listboxId}
          aria-activedescendant={open && options[highlightedIndex] ? `${listboxId}-${options[highlightedIndex].id}` : undefined}
          aria-describedby={describedBy}
          aria-required="true"
          value={query}
          placeholder="Rechercher par nom ou numéro"
          onFocus={() => {
            setOpen(true)
            const selectedIndex = options.findIndex((courier) => courier.id === value)
            setHighlightedIndex(selectedIndex >= 0 ? selectedIndex : 0)
          }}
          onChange={(event) => {
            setQuery(event.target.value)
            if (value) {
              clearingSelectionFromTyping.current = true
              onChange('')
            }
            setOpen(true)
            setHighlightedIndex(0)
          }}
          onKeyDown={handleKeyDown}
        />
        {value ? (
          <button
            type="button"
            aria-label="Effacer le livreur choisi"
            onClick={() => {
              onChange('')
              setQuery('')
              setOpen(true)
            }}
          ><X aria-hidden="true" /></button>
        ) : <ChevronDown aria-hidden="true" />}
      </div>

      {open && (
        <ul id={listboxId} role="listbox" aria-label="Livreurs disponibles">
          {showMissingSelection && (
            <li
              id={`${listboxId}-missing`}
              role="option"
              aria-label={`${missingLabel} (fiche indisponible)`}
              aria-selected="true"
              className="is-selected"
              onMouseDown={(event) => {
                event.preventDefault()
                setQuery(selectedLabel)
                setOpen(false)
              }}
            >
              <span aria-hidden="true"><UserRound /></span>
              <div><strong>{missingLabel} (fiche indisponible)</strong><small>Valeur conservée dans le plan</small></div>
              <Check aria-hidden="true" />
            </li>
          )}
          {options.map((courier, index) => (
            <li
              id={`${listboxId}-${courier.id}`}
              key={courier.id}
              role="option"
              aria-selected={courier.id === value}
              className={`${index === highlightedIndex ? 'is-highlighted' : ''}${courier.id === value ? ' is-selected' : ''}`}
              onMouseDown={(event) => {
                event.preventDefault()
                select(courier)
              }}
              onMouseEnter={() => setHighlightedIndex(index)}
            >
              <span aria-hidden="true"><UserRound /></span>
              <div><strong>{courier.name}</strong><small>{courier.phoneNumber ?? courier.contact ?? 'Aucun numéro'}{courier.active ? '' : ' · Inactif'}</small></div>
              {courier.id === value && <Check aria-hidden="true" />}
            </li>
          ))}
          {options.length === 0 && !showMissingSelection && <li className="is-empty">Aucun livreur ne correspond à cette recherche.</li>}
        </ul>
      )}
    </div>
  )
}
