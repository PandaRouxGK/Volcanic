# Vitrail — plan de 5 heures

Établi le 2026-11-08 vers 18:40 (heure locale, RST +0100), à partir de l'état mesuré suivant :
9 passes BSL compilées, 0 refusée, **22/31 samplers servis**, 9 non servis
(`depthtex0`, `depthtex1`, `shadowtex0`, `shadowtex1`, `shadowcolor0`), terrain éclairé par la
carte d'ombre prouvé par sonde (−34 % d'éclairage en occlusion forcée).

Ordre choisi par levier réel : débloquer d'abord ce qui rend trois passes sur neuf inopérantes,
puis prouver la cohérence, puis mesurer la couverture, puis le coût, puis l'anomalie ouverte.
Chaque bloc se termine par une mesure chiffrée, pas par une impression.
Budget : ~50 min de travail + ~10 min de vérification par bloc ; un run de vérification coûte ~35 s.

## Bloc 1 — 18:45 → 19:35 · `depthtex0` (le plus gros levier)

**Objectif :** servir le type `DEPTH` du plan de samplers, aujourd'hui compté `unbound` sur
`deferred`, `deferred1`, `composite`, `composite5`, `composite7`.

**Concrètement :** exposer la profondeur principale comme texture échantillonnable, en tirer
`depthtex0`, et laisser `depthtex1` (profondeur sans translucides) à un bloc séparé.

**Mesure :** le log de plan doit passer de `samplers=22/31 unbound=9` à **~26/31**, et une sonde
doit reconstruire une distance monde depuis la profondeur lue, comparée à une distance connue
(bloc sous les pieds).

**Livrable :** commit « serve the depth a stage asks for » avec le ratio avant/après.

### Bloc 1 — exécuté le 2026-11-08 à 18:41, et le plan était faux

La prémisse ne tenait pas : **la profondeur est déjà servie**. La mesure qui l'établit, chaîne
complète tirée (sans `chain.limit=0`, qui saute `bind()` et n'avait donc jamais rien mesuré) :

| ce qui est lu | valeur mesurée |
|---|---|
| samplers au tirage | **31 avec une image, 0 sur le texel blanc** (9 passes tirées) |
| `depthtex0` (deferred, deferred1, composite, composite5) | 854×480, `fmt=0x7e` soit D32_SFLOAT |
| `depthtex1` (composite, composite7) | 854×480, `fmt=0x7e` |
| image présentée | `mean=(198, 141, 80)`, aucune passe refusée |
| second run, autres defines | `samplers_planned=19/28`, **28/28 servis** |

Les 9 noms que le log de plan disait `unbound` sont ceux que le plan ne peut pas résoudre tout
seul ; le résolveur du moteur les répond au tirage (`resolveEngineResource`, `"depthtex"` →
profondeur du monde, `"shadowtex0"` → cascade du pack). Le compteur du plan était donc un
plancher lu comme un trou, et il a envoyé une session entière après une texture qui existait.

**Correction livrée :** les compteurs s'appellent désormais `samplers_planned` et
`unbound_in_plan`, avec un commentaire qui porte la mesure de 31/31, pour que la prochaine
session ne reparte pas sur cette fausse piste.

**Ce qui reste vrai du bloc 1 :** présence, provenance et format de la profondeur sont prouvés —
c'est la même image que lisent les passes radiance du moteur. Sa *convention* (profondeur fenêtre
[0,1] attendue par les packs, et antériorité aux translucides pour `depthtex1`) n'est pas encore
prouvée ; c'est ce qui reste du bloc 1 et cela passe au bloc 3, qui le fait sur géométrie réelle.

**Effet sur l'estimation de parité :** le ratio « 22/31 samplers » qui tirait mon ~55 % vers le bas
était faux. Le vrai ratio au tirage est 31/31, et trois passes que je croyais inopérantes
reçoivent leur profondeur. L'estimation remonte, mais rien n'est gagné en rendu : ce qui manque
n'est pas un branchement, c'est la justesse des conventions et la couverture des stages.

## Bloc 2 — 19:35 → 20:15 · `shadowtex0/1` en lecture directe

**Objectif :** les passes qui lisent la carte d'ombre comme une texture (distorsion, volumétriques,
eau) ne l'ont pas.

**Concrètement :** la carte existe déjà comme `ShadowMap` ; il s'agit de la publier en
`SHADOW_DEPTH` plutôt que de la garder privée au stage terrain.

**Mesure :** une sonde qui lit `shadowtex0` à la main doit retrouver la même valeur que le chemin
`texture2DShadow` déjà validé (0,8225 de part solaire). Si les deux divergent, c'est un bug de la
carte, pas du shader.

**Livrable :** `~28/31`, plus la preuve de cohérence des deux chemins.

### Bloc 2 — exécuté le 2026-11-08 à 18:48, et la prémisse était encore fausse

`shadowtex0` n'était pas manquant non plus. Ce que la mesure a établi :

| ce qui est lu | valeur mesurée |
|---|---|
| `shadowtex0`, `shadowtex1`, `shadowtex2` | **la même image de cascade** (`PACK_CASCADE`) |
| `shadowtex1` pour un stage terrain | `VTextureSelector.SHADOW_SLOT`, donc la même carte que `shadowtex0` |
| `shadowcolor0` pour un stage terrain | le **texel blanc** (`engineSlot` → `default`) |
| image de teinte (chaîne) | 1536², `fmt=0x25`, **92,6 % de texels noirs**, moyenne (4, 6, 3), max 201 |
| passe de teinte sous un pack | **jamais tirée** : `Config.isCamille()` exige `selectedShader == "radiance"` |
| frame terrain après le bloc 2 | `0,4107–0,4125 / 0,5002–0,5030 / 0,5879–0,5893`, 0 erreur de compilation |

**Conséquences, et elles ne sont pas cosmétiques**

1. BSL lit `shadowtex1` sous `SHADOW_COLOR` pour décider si l'occulteur était translucide. Avec une
   seule carte, le comparateur au même texel répond la même valeur : **la fonctionnalité est
   absente**, pas fausse. Idem pour les caustiques d'eau.
2. `shadowcolor0` répond le blanc à un pack. BSL multiplie ce blanc par `shadowtex1` et **élève le
   produit au carré**, donc un texel occulté de facteur `s` ressort à `s + s²(1−s)` : jusqu'à +15 %
   de lumière ajoutée dans la pénombre (maximum vers `s = 2/3`). C'est une dérivation depuis le
   shader, **pas encore une mesure de bout en bout**.
3. La passe de teinte et les casters d'entités sont sautés sous un pack : la porte est
   `isCamille()`. Les entités ne projettent donc aucune ombre sur ce chemin.

**Décision du bloc :** ne rien changer au comportement sans mesure. Un correctif plausible —
répondre blanc là où l'image n'a pas été écrite — a été écrit puis **retiré** : le code de BSL montre
que le blanc est justement le pire des deux choix, puisqu'il remet de la lumière dans la pénombre.
Ce qui est livré : la sonde de teinte (mesure permanente), et la dérivation consignée dans
`VitrailProgram.engineSlot`, à l'endroit exact où le nom est résolu pour un stage terrain.

**A/B à faire, chiffré :** répondre noir au lieu du blanc pour `shadowcolor0` et comparer le facteur
d'ombre moyen sur la même pose. Le noir est la réponse neutre tant qu'il n'y a qu'une carte ; le
blanc est la réponse actuelle. La différence attendue est celle du terme `s²(1−s)`.

## Bloc 3 — 20:15 → 21:05 · La profondeur, prouvée sur géométrie réelle

**Objectif :** une profondeur servie mais fausse est pire que pas de profondeur — les passes
composite produiraient des artefacts silencieux.

**Concrètement :** trois points d'écran fixes (bloc proche, bloc lointain, ciel), reconstruction de
la distance via la matrice inverse, comparaison aux distances mesurées en jeu.

**Mesure :** erreur relative par point, en %, et le cas ciel (doit rester à 1,0).

**Livrable :** le test lui-même, réutilisable comme garde-fou avant chaque futur changement de
matrice.

### Bloc 3 — exécuté le 2026-11-08 à 18:57, et il a trouvé un vrai défaut

Ce bloc a commencé par un piège de mesure. `deferred` écrit son AO dans **colortex4**
(`DRAWBUFFERS:4`), et cette cible se lisait à zéro. Or cinq des huit buffers se lisaient à zéro
alors que l'image présentée était correcte — ce qu'aucune chaîne ne peut produire à partir de
zéro. La sonde lisait donc **la mauvaise moitié du ping-pong**. Corrigé : `-Dvitrail.debug.probe.side=alt`.

**Conséquence à retenir :** toute moyenne de buffer de chaîne lue avant ce correctif est suspecte,
sauf les runs à `chain.limit=0`, où aucune passe n'écrit et où la face lue est forcément la bonne.

Ensuite, la profondeur, mesurée deux fois indépendamment :

| mesure | résultat |
|---|---|
| `z = textureLod(depthtex0, ...)` relu dans la cible du pack | **1,0000 sur tous les texels** |
| même chemin avec un témoin forcé à 0,5 | **0,5020** — la sonde est vivante |
| image de profondeur lue directement | 854×480, `fmt=0x7e` (D32), **0 caster sur 409 920, 1,0 partout** |
| AO que le pack en déduit (colortex4) | 1,0 partout, ciel et sol confondus |

**Le défaut :** la profondeur que reçoit la chaîne est **vide**. Ce n'est pas un problème de lien
ni de format, c'est la mauvaise image : `adoptWorldTarget` passe `scaledFramebuffer` comme tampon de
profondeur au framebuffer où le monde est dessiné, et cette image reste au plan lointain — donc le
pack ne voit aucune géométrie nulle part.

**Ce que cela éteint dans BSL, sans aucun symptôme visible :** l'occlusion ambiante (AO = 1
partout), la distance de brouillard, la profondeur de champ, les volumétriques, et la séparation
ciel/géométrie. La géométrie du pack ne lit pas la profondeur, donc l'image peut rester plausible.
C'est exactement le type de panne que les moyennes de frame ne montrent pas.

**Livré :** la sonde de profondeur (image lue directement), le sélecteur de face du ping-pong, et la
sonde `z` dans le shader de la passe qui la consomme, témoin compris.

**Reste ouvert, et c'est la suite :** établir si la passe monde du pack écrit la profondeur du tout
(test et attachement), puis alimenter la chaîne avec l'image que le monde a réellement écrite. Tant
que ce n'est pas fait, aucun effet dépendant de la profondeur ne peut être jugé correct ou incorrect.

## Bloc 4 — 21:05 → 21:55 · Qui occupe vraiment l'écran

**Objectif :** répondre chiffré à « quelle part de l'image chaque stage possède », ce qui conditionne
l'ordre des priorités suivantes.

**Concrètement :** sonde sur eau, entités, ciel, main, météo — la même mesure que pour le terrain
(`éclairage`, `part solaire`, `facteur d'ombre`).

**Mesure :** couverture et éclairage par stage à la pose de référence. C'est aussi ce qui corrige
l'indicateur de couverture invalide (le bleu du ciel confondu avec le marqueur).

**Livrable :** tableau par stage, et décision argumentée sur ce qui mérite d'être instrumenté
ensuite.

## Bloc 5 — 21:55 → 22:40 · Coût réel

**Objectif :** aucun chiffre de performance n'existe aujourd'hui — c'est ce qui peut faire baisser
l'estimation de ~55 % de parité.

**Concrètement :** temps de frame GPU à la pose de référence, BSL activé contre chaîne limitée à 0,
plus le coût isolé de la carte d'ombre.

**Mesure :** ms par frame, médiane et p95 sur 300 frames, pas une moyenne seule.

**Livrable :** soit « utilisable », soit la liste des postes à couper. Si c'est rouge, ça devient la
priorité 1 des 5 heures suivantes.

## Bloc 6 — 22:40 → 23:20 · Le point ouvert, celui qui traîne

**Objectif :** clore ou documenter l'anomalie mesurée : −34 % d'éclairage, +0,2 % sur l'albedo
stocké.

**Concrètement :** écrire depuis le même fragment `dot(albedo)` avant la pondération, `dot(albedo)`
après, et `sceneLighting` — trois valeurs dans trois canaux, une seule run.

**Résultat attendu :** soit la cause apparaît (terme dominant, `sqrt`, chemin d'écriture), soit la
conclusion est écrite dans le code plutôt que laissée en suspens.

## Bloc 7 — 23:20 → 23:45 · Clôture

Nettoyage de l'instrumentation temporaire, `compileJava`, un run sans flag pour prouver que rien n'a
bougé, commit et push sur `vitrail/port-runtime`, plus un état honnête de ce qui est mesuré et de ce
qui reste supposé.

## Ce qui peut faire glisser

- **`depthtex1`** dépend des translucides : si l'eau n'est pas encore dessinée dans un passage
  séparé, le dire et servir `depthtex0` seul plutôt que de simuler une fausse profondeur propre.
- **`shadowcolor0`** exige une cible couleur sur la passe d'ombre : si le format de cette passe ne
  s'y prête pas, il glisse en fin de plan et ne bloque rien.
- **L'heure** : vérifier l'horloge locale à chaque bloc et signaler tout décalage.

## Repères de mesure (état au départ du plan)

| grandeur | valeur | contexte |
|---|---|---|
| passes compilées | 9/9, `refused=0` | BSL 10.1.8, `place=world0` |
| samplers servis | 22/31 | 9 non servis, 0 inservable |
| éclairage terrain, carte réelle | 0,6603 | midi, 36 frames |
| éclairage terrain, occlusion forcée | 0,4360 | même pose |
| part solaire, carte réelle | 0,8225 | `lightCol × fullShadow × shadowMult` |
| part solaire, occlusion forcée | 0,5739 | même pose |
| ambiante (témoin) | 0,36052 vs 0,36051 | prouve que la scène n'a pas bougé |
| anomalie ouverte | éclairage −34 %, albedo stocké +0,2 % | sans explication |
