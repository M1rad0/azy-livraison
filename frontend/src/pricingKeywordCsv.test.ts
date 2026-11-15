import { describe, expect, it } from 'vitest'
import { mergePricingKeywordRules, normalizePricingKeyword, parsePricingKeywordCsv } from './pricingKeywordCsv'

describe('CSV des tarifs par mot-clé', () => {
  it('lit les en-têtes français, le BOM UTF-8, les fins de ligne Windows et les montants groupés', () => {
    expect(parsePricingKeywordCsv('\uFEFFclé;tarif\r\nIvándry;4 000\r\nAnalakely;0\r\n')).toEqual([
      { keyword: 'Ivándry', deliveryFeeAriary: 4000 }, { keyword: 'Analakely', deliveryFeeAriary: 0 },
    ])
  })

  it.each(['key,price', 'keyword,delivery_fee_ariary', 'mot_clé,prix', 'cle,tarif_ariary'])('accepte les alias %s et les cellules citées', (headers) => {
    expect(parsePricingKeywordCsv(`${headers}\n"Lieu, portail ""bleu""",5000`)).toEqual([{ keyword: 'Lieu, portail "bleu"', deliveryFeeAriary: 5000 }])
  })

  it('lit une tabulation et une clé multiligne sans perdre la ligne d’erreur suivante', () => {
    expect(parsePricingKeywordCsv('keyword\tprice\n"Lieu\nbleu"\t500')).toEqual([{ keyword: 'Lieu bleu', deliveryFeeAriary: 500 }])
    expect(() => parsePricingKeywordCsv('keyword;price\n"Lieu\nbleu";500\nAutre;-1')).toThrow('Ligne 4')
  })

  it.each([
    ['clé;tarif\nLieu;', 'Ligne 2'],
    ['clé;tarif\nLieu;-2', 'Ligne 2'],
    ['clé;tarif\nLieu;1.5', 'Ligne 2'],
    ['clé;tarif\nLieu;1e3', 'Ligne 2'],
    ['clé;tarif\nLieu;9007199254740992', 'Ligne 2'],
    ['clé;tarif\n;500', 'Ligne 2'],
    [`clé;tarif\n${'a'.repeat(201)};500`, '200 caractères'],
    ['clé;keyword\nLieu;500', 'deux colonnes'],
    ['clé;tarif\nLieu;500;extra', 'nombre de cellules'],
    ['clé;tarif\n"Lieu;500', 'pas fermée'],
    ['clé;tarif\n"Lieu"oops;500', 'guillemets'],
    ['clé;tarif\nLieu;\0', 'non textuelles'],
    ['clé;tarif\n', 'au moins une règle'],
  ])('rejette le fichier invalide avec une erreur claire', (content, message) => {
    expect(() => parsePricingKeywordCsv(content)).toThrow(message)
  })

  it('déduplique les clés sans remplacer les tarifs existants ni modifier leur ordre', () => {
    const existing = [{ id: '1', keyword: 'Ivándry  Nord', deliveryFeeAriary: 4000 }]
    const imported = parsePricingKeywordCsv('clé;tarif\n IVANDRY nord ;1000\nAnalakely;2000\nanálakely;3000\nIvandry;5000')
    expect(mergePricingKeywordRules(existing, imported)).toEqual({
      added: [{ keyword: 'Analakely', deliveryFeeAriary: 2000 }, { keyword: 'Ivandry', deliveryFeeAriary: 5000 }], skipped: 2,
    })
    expect(existing[0].deliveryFeeAriary).toBe(4000)
    expect(normalizePricingKeyword('  IVÁNDRY   Nord ')).toBe('ivandry nord')
  })

  it('vérifie la limite après déduplication sans appliquer un import partiel', () => {
    const existing = Array.from({ length: 100 }, (_, index) => ({ keyword: `Lieu ${index}` }))
    expect(mergePricingKeywordRules(existing, [{ keyword: 'lieu 0', deliveryFeeAriary: 0 }])).toEqual({ added: [], skipped: 1 })
    expect(() => mergePricingKeywordRules(existing, [{ keyword: 'Nouveau', deliveryFeeAriary: 0 }])).toThrow('100 règles')
  })
})
