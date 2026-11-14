import { describe, expect, it } from 'vitest'
import { dispatchPlace, dispatchPlaceText, shortDispatchAddress } from './manualDispatchPresentation'

describe('lieux affichés dans le dispatch manuel', () => {
  it('privilégie le libellé et réduit seulement l’adresse géographique', () => {
    const location = { label: 'Chez Soa, portail orange, entrée arrière', destinationQuery: 'Analakely, Antananarivo, Analamanga, Madagascar' }
    expect(dispatchPlace(location)).toEqual({ title: location.label, address: 'Analakely, Antananarivo' })
    expect(dispatchPlaceText(location)).toBe('Chez Soa, portail orange, entrée arrière · Analakely, Antananarivo')
    expect(location.destinationQuery).toBe('Analakely, Antananarivo, Analamanga, Madagascar')
  })

  it('abrège aussi un libellé copié depuis l’adresse complète', () => {
    const address = 'Analakely, Antananarivo, Analamanga, Madagascar'
    expect(dispatchPlace({ label: address, destinationQuery: address })).toEqual({ title: 'Analakely, Antananarivo', address: undefined })
  })

  it('ignore les segments vides et répétitifs sans perdre les lots ou numéros', () => {
    expect(shortDispatchAddress(' , Lot 42, , LOT 42, Analakely, Antananarivo')).toBe('Lot 42, Analakely')
    expect(shortDispatchAddress('Alasora')).toBe('Alasora')
  })

  it('utilise la seconde requête si la première est vide', () => {
    expect(dispatchPlace({ label: ' ', destinationQuery: ' ', query: 'Ankadivato, Antananarivo, Madagascar' }))
      .toEqual({ title: 'Ankadivato, Antananarivo', address: undefined })
  })

  it('ne répète pas une adresse déjà donnée par le libellé', () => {
    expect(dispatchPlace({ label: 'Analakely, Antananarivo', destinationQuery: 'Analakely, Antananarivo, Madagascar' }).address).toBeUndefined()
    expect(dispatchPlace({ label: 'Un repère sans adresse' }).title).toBe('Un repère sans adresse')
    expect(dispatchPlace().title).toBe('Lieu non renseigné')
  })
})
