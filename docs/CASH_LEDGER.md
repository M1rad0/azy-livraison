# Caisses et reversements vendeurs

## Nouveau registre, ancien historique préservé

La migration V23 ouvre une nouvelle époque financière dans des tables dédiées du même PostgreSQL. Elle ne supprime aucune table et ne réimporte aucun ancien encaissement ou solde. Toutes les caisses commencent à zéro. Les encaissements et rapports déjà présents servent uniquement de référence : une correction ultérieure ne comptabilise que sa différence, pas le montant historique complet.

Terminer les régularisations de l'ancien suivi **avant le déploiement**. La date de début du registre est conservée (`activatedAt`). Il n'y a ni remise à zéro silencieuse, ni suppression du journal.

## Deux notions distinctes

- AZY et livreurs : argent détenu pour le service. Un solde livreur négatif représente une avance à régulariser, pas un salaire.
- Vendeur : montant net encore dû par AZY. Positif : AZY doit payer le vendeur ; négatif : le vendeur doit payer AZY.
- Les réservations de briefing réduisent le disponible, jamais le solde réel.
- Aucune compensation automatique entre l'argent détenu et les anciennes rémunérations.

Exemple : le livreur réel encaisse 30 000 Ar, dont 5 000 Ar de frais. Sa caisse reçoit 30 000 Ar. La clôture client reconnaît une dette vendeur de 25 000 Ar. Le versement confirmé de 25 000 Ar au vendeur laisse 5 000 Ar chez le livreur, 0 Ar restant dû au vendeur et 0 Ar détenu directement par AZY.

## Mouvements

Le compte rendu crédite le **livreur réellement intervenu**, pour le montant réellement encaissé, indépendamment du dispatch. Les corrections d'état, de montant ou de livreur produisent des ajustements traçables dans la même transaction que le compte rendu. Un encaissement éventuellement enregistré sur un retour est également pris en compte. Mobile Money déjà payé reste informatif : seul le montant réellement encaissé entre dans la caisse.

Une clôture client reconnaît le net du rapport : encaissements moins frais finaux. La clôture globale ne le comptabilise pas une deuxième fois. Une réouverture retire l'obligation correspondante ; un rapport avec un versement actif doit d'abord être régularisé/son versement annulé.

Les transferts manuels sont possibles entre tous les comptes distincts, y compris entre livreurs. Les paiements partiels et les découverts sont autorisés manuellement. Un ajustement motivé permet de régulariser un compte ou de le ramener explicitement à zéro. Chaque transfert conserve sa méthode, sa référence, sa note, son auteur et son justificatif facultatif. Les règlements vendeurs sont affectés aux rapports les plus anciens.

Une annulation conserve le mouvement et crée une contre-écriture motivée, y compris pour les affectations aux rapports. Les clés d'idempotence évitent les doubles enregistrements ; une clé réutilisée pour un autre contenu est refusée. Les écritures et réservations sont sérialisées par un verrou financier et une révision.

## Consulter les listes financières

Les règlements par compte rendu, les mouvements de caisse et les versements des briefings ont chacun leur pagination et leur filtre « Du / Au ». Les deux dates sont incluses ; une borne vide laisse la période ouverte. Les règlements et les briefings utilisent la date d’activité ; les mouvements utilisent la date réelle d’enregistrement, selon le fuseau `Indian/Antananarivo`.

La recherche et le filtrage précèdent la pagination côté serveur. Modifier la recherche, une date ou le nombre de lignes ramène à la première page. Les caisses, soldes et réservations restent globaux : ils ne sont jamais recalculés sur la seule page ou période affichée.

Les versements de briefing confirmés restent consultables après la fin d’un lot. Les anciennes propositions non confirmées qui ont été remplacées, libérées ou invalidées ne sont pas présentées comme des versements encore à faire. Consulter une page ne confirme aucun paiement.

Les API paginées acceptent `page` (à partir de 0), `size` (20 par défaut, de 1 à 100), `from`, `to` et `q` :

- `GET /api/admin/cash-ledger/obligations`
- `GET /api/admin/cash-ledger/movements`
- `GET /api/admin/cash-briefings/transfers`

Leur réponse contient `items`, `totalElements`, `page`, `size` et `totalPages`. L’interface demande les soldes avec `GET /api/admin/cash-ledger?includeHistory=false` pour éviter de charger l’historique complet en parallèle. L’ancien instantané reste disponible sans ce paramètre pour les consommateurs existants.

## Préparer les versements avant le briefing

Le parcours est **Dispatch → Versements → Briefing**. L'étape Versements affiche les soldes globaux des vendeurs et les caisses des livreurs sélectionnés pour la journée. Le disponible tient compte des réservations des autres plans ; les sommes déjà prévues dans le plan courant ne sont pas comptées deux fois.

Les versements peuvent être saisis manuellement ou proposés par le bouton de répartition automatique. Chaque nouvelle préparation règle le solde complet d'un vendeur par un seul livreur, en mode manuel comme automatique. Un vendeur déjà réservé dans une autre préparation reste indisponible : son solde n'est pas réparti entre plusieurs préparations. Aucun calcul automatique n'est lancé à l'ouverture de l'écran.

Une préparation peut prévoir des transferts entre les livreurs actifs pour réunir l'argent chez les payeurs. Ces transferts ont leurs propres montants, états et confirmations dans Finance. Les boucles sont interdites et chaque suite de transferts doit être réalisable à partir des seules caisses réelles disponibles, sans compter les encaissements futurs du dispatch. Les apports successifs entre livreurs sont possibles ; un transfert reste bloqué tant que la caisse réelle de son donneur est insuffisante. Le paiement d'un vendeur reste bloqué jusqu'à confirmation des apports entrants de son payeur.

Les entrées et sorties prévues sont présentées séparément des caisses réelles. Seul l'argent déjà détenu est réservé, chez son détenteur actuel ; confirmer un apport déplace réellement l'argent et sa réservation vers le receveur dans la même transaction. Copier le briefing ou enregistrer une préparation ne confirme aucun apport ni paiement. Une annulation financière rend la préparation à revalider et ne recrée jamais automatiquement un apport annulé.

La liste et la sélection des vendeurs masquent les comptes sans solde disponible à verser. Les lignes déjà prévues restent accessibles pour correction si leur solde a changé. Les numéros de versement du compte vendeur sont administrables à la création et à la modification, avec les mêmes priorités que dans le profil client.

Chaque versement propose par défaut la première destination active compatible du compte vendeur. Des flèches permettent de parcourir ces destinations par priorité, en lecture seule ; la dernière position « Personnalisé » permet de saisir un opérateur, un numéro et son titulaire pour ce seul versement, sans modifier le compte. Le choix d'une destination du compte reste lié à son identifiant, même si son ordre de priorité change.

Les opérateurs des nouvelles destinations de versement sont limités à **MVola**, **Orange Money** et **Airtel Money**, côté formulaire et API. Cette règle s'applique aux comptes clients et livreurs ainsi qu'aux coordonnées personnalisées des briefings. L'opérateur choisi est conservé avec le numéro et le titulaire. Les anciens historiques et les nouvelles tentatives d'enregistrement déjà prises en compte conservent leur compatibilité. Les moyens de paiement des mouvements de caisse, notamment les espèces et les virements, restent indépendants de cette liste.

Le numéro, le titulaire et l'opérateur retenus sont conservés dans le briefing et l'historique. Une modification des coordonnées sélectionnées rend les prévisions liées au compte à revalider ; elle ne remplace pas les coordonnées personnalisées. Les migrations additives V26/V27 distinguent ces cas sans réécrire les versements confirmés. Un ancien titulaire inconnu reste non renseigné : le nom du vendeur n'est jamais présenté comme celui du titulaire par supposition. Les prévisions personnalisées sans titulaire doivent être complétées avant réutilisation.

Un enregistrement ou une nouvelle génération remplace atomiquement les prévisions précédentes, jamais les versements déjà confirmés. Si le calcul ou la validation échoue, la préparation précédente reste intacte. Une clé d'idempotence, la révision des caisses et l'identifiant de la préparation empêchent les doubles enregistrements et les écrasements concurrents.

La préparation crée un lot global commun à tous les livreurs et aux langues FR/MG. Elle ne crée aucun versement confirmé et ne s'appuie jamais sur les recettes prévisionnelles du plan. Seule la caisse déjà disponible des livreurs du plan peut financer les propositions ; aucun déplacement n'est ajouté, les transferts étant prévus par Mobile Money.

En mode automatique, un vendeur est proposé uniquement si son solde global peut être entièrement réglé par un seul livreur, après les apports nécessaires de ses collègues. Priorités : nombre de vendeurs soldés, montant total, ancienneté, puis nombre de transferts entre livreurs. L'argent et les dettes proposés sont réservés pour éviter les doubles propositions. Une modification financière rend le lot à revalider ; les lots expirés, libérés ou liés à un plan devenu invalide ne peuvent pas être utilisés comme instructions valides. Les livreurs à découvert ne financent pas les propositions automatiques ; leur éventuelle régularisation reste manuelle.

La migration additive V28 ajoute les transferts entre livreurs et la version du fonctionnement des lots, sans modifier les caisses ni réécrire les anciens versements. Les préparations historiques de version 1 conservent leurs règlements partiels ou répartis et restent consultables et confirmables. Les nouvelles préparations utilisent la version 2.

Le briefing affiche d'abord les **Transferts entre livreurs**, puis les **Versements à faire**, en vue globale et par livreur. Les textes FR/MG précisent les montants à donner ou recevoir et les collègues concernés, avant les paiements vendeurs avec opérateur, numéro, titulaire et montant. Ils reprennent seulement les instructions enregistrées encore à faire ; ni génération ni modification de la préparation ne se fait dans le briefing. Les transferts et les paiements réels sont confirmés séparément dans Finance.

**Copier ou modifier un briefing ne confirme rien.** Seul l'administrateur confirme les versements réels dans Finance, avec justificatif facultatif. Un lot remplacé impose de renouveler les textes pour ne pas conserver d'anciennes instructions de paiement. La migration V25 ajoute seulement le mode de préparation et son empreinte de requête ; elle conserve les préparations existantes.

## Rémunérations retirées du nouveau fonctionnement

Les nouvelles commissions par colis et les remboursements de carburant sont désactivés par défaut. Les anciens gains, versements, justificatifs et moyens de régularisation sont conservés séparément. La paie des salariés n'est pas incluse dans cette évolution.

L'ancien calcul reste présent pour la lecture de l'historique et ses tests de compatibilité. Le paramètre technique `app.finance.legacy-accruals-enabled`, désactivé par défaut, ne doit pas être activé dans le nouveau fonctionnement.

Les écrans historiques ne sont plus proposés dans la navigation. Accès administrateur uniquement, en saisissant directement `#/admin/finance-legacy-clients` ou `#/admin/finance-legacy-couriers`. Les API conservent les mêmes contrôles d'accès ; une route non listée n'est pas un mécanisme de sécurité.

L'espace courant sépare **Caisses**, **Vendeurs**, **Versements** proposés dans les briefings et **Mouvements** confirmés. Il n'affiche pas de bandeau permanent relatif à la bascule.
