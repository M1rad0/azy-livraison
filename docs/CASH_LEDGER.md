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

## Préparer les versements avant le briefing

Le parcours est **Dispatch → Versements → Briefing**. L'étape Versements affiche les soldes globaux des vendeurs et les caisses des livreurs sélectionnés pour la journée. Le disponible tient compte des réservations des autres plans ; les sommes déjà prévues dans le plan courant ne sont pas comptées deux fois.

Les versements peuvent être saisis manuellement (vendeur, livreur, montant) ou proposés par le bouton de répartition automatique. La saisie manuelle autorise un règlement partiel, sans dépasser le solde disponible du vendeur ni l'argent réellement disponible chez le livreur. La génération automatique conserve les priorités et la règle de règlement intégral ci-dessous. Aucun calcul automatique n'est lancé à l'ouverture de l'écran.

La liste et la sélection des vendeurs masquent les comptes sans solde disponible à verser. Les lignes déjà prévues restent accessibles pour correction si leur solde a changé. Les numéros de versement du compte vendeur sont administrables à la création et à la modification, avec les mêmes priorités que dans le profil client.

Chaque versement reprend par défaut le premier numéro actif du compte vendeur. L'administrateur peut saisir un autre numéro pour ce seul versement, sans modifier le compte, puis revenir au numéro du compte. Ce choix est conservé dans le briefing et l'historique. Une modification du numéro du compte rend les prévisions utilisant ce défaut à revalider ; elle ne remplace pas un numéro spécifique choisi explicitement. La migration additive V26 distingue ces deux cas et conserve tous les anciens versements.

Un enregistrement ou une nouvelle génération remplace atomiquement les prévisions précédentes, jamais les versements déjà confirmés. Si le calcul ou la validation échoue, la préparation précédente reste intacte. Une clé d'idempotence, la révision des caisses et l'identifiant de la préparation empêchent les doubles enregistrements et les écrasements concurrents.

La préparation crée un lot global commun à tous les livreurs et aux langues FR/MG. Elle ne crée aucun versement confirmé et ne s'appuie jamais sur les recettes prévisionnelles du plan. Seule la caisse déjà disponible des livreurs du plan peut financer les propositions ; aucun déplacement n'est ajouté, les transferts étant prévus par MVola.

En mode automatique, un vendeur est proposé uniquement si son solde global peut être entièrement réglé, éventuellement par plusieurs livreurs. Priorités : nombre de vendeurs soldés, montant total, ancienneté, puis nombre de transferts. L'argent et les dettes proposés sont réservés pour éviter les doubles propositions. Une modification financière rend le lot à revalider ; les lots expirés, libérés ou liés à un plan devenu invalide ne peuvent pas être utilisés comme instructions valides.

Le briefing affiche ensuite une section **Versements à faire**, en vue globale et par livreur. Les textes FR/MG reprennent uniquement les versements enregistrés encore à faire, avec vendeur, numéro MVola et montant ; ni génération ni modification de la préparation ne se fait dans le briefing.

**Copier ou modifier un briefing ne confirme rien.** Seul l'administrateur confirme les versements réels dans Finance, avec justificatif facultatif. Un lot remplacé impose de renouveler les textes pour ne pas conserver d'anciennes instructions de paiement. La migration V25 ajoute seulement le mode de préparation et son empreinte de requête ; elle conserve les préparations existantes.

## Rémunérations retirées du nouveau fonctionnement

Les nouvelles commissions par colis et les remboursements de carburant sont désactivés par défaut. Les anciens gains, versements, justificatifs et moyens de régularisation sont conservés séparément. La paie des salariés n'est pas incluse dans cette évolution.

L'ancien calcul reste présent pour la lecture de l'historique et ses tests de compatibilité. Le paramètre technique `app.finance.legacy-accruals-enabled`, désactivé par défaut, ne doit pas être activé dans le nouveau fonctionnement.

Les écrans historiques ne sont plus proposés dans la navigation. Accès administrateur uniquement, en saisissant directement `#/admin/finance-legacy-clients` ou `#/admin/finance-legacy-couriers`. Les API conservent les mêmes contrôles d'accès ; une route non listée n'est pas un mécanisme de sécurité.

L'espace courant sépare **Caisses**, **Vendeurs**, **Versements** proposés dans les briefings et **Mouvements** confirmés. Il n'affiche pas de bandeau permanent relatif à la bascule.
