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

## Bloc 3 — 20:15 → 21:05 · La profondeur, prouvée sur géométrie réelle

**Objectif :** une profondeur servie mais fausse est pire que pas de profondeur — les passes
composite produiraient des artefacts silencieux.

**Concrètement :** trois points d'écran fixes (bloc proche, bloc lointain, ciel), reconstruction de
la distance via la matrice inverse, comparaison aux distances mesurées en jeu.

**Mesure :** erreur relative par point, en %, et le cas ciel (doit rester à 1,0).

**Livrable :** le test lui-même, réutilisable comme garde-fou avant chaque futur changement de
matrice.

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
