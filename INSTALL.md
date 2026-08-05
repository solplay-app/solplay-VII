# SolPlay v11 — Patch home screen (sidebar + hero + category cards)

## Fichiers modifiés (2)
- `app/src/main/res/layout/activity_home.xml`           (~33 KB)
- `app/src/main/java/com/solplay/iptv/HomeActivity.kt`  (~16 KB)

## Fichiers ajoutés (14 drawables)
- `bg_sidebar_btn.xml`             bouton rond sidebar (état repos / focus orange plein)
- `bg_featured_panel.xml`          cadre arrondi du panneau vedette
- `bg_hero_scrim_v11.xml`          voile dégradé du panneau vedette (3 couches)
- `bg_hero_badge.xml`              pastille "★ COUP DE CŒUR DU JOUR"
- `bg_hero_btn_play.xml`           bouton Lecture orange (focus liseré blanc)
- `bg_hero_btn_ghost.xml`          bouton Ma liste translucide
- `bg_category_live.xml`           carte TV en direct (dégradé bleu, focus liseré blanc)
- `bg_category_movie.xml`          carte Films (dégradé orange SolPlay)
- `bg_category_series.xml`         carte Séries (dégradé violet)
- `bg_category_icon_live.xml`      pastille verte pour icône carte Live
- `bg_category_icon_movie.xml`     pastille orange pour icône carte Films
- `bg_category_icon_series.xml`    pastille violette pour icône carte Séries
- `bg_glow_pill.xml`               pilule verte transparente (UPDATE EPG / ACCOUNT / CATCH UP)
- `ic_star_meta.xml`               étoile 14dp pour notation hero

## Captures Playwright (preview uniquement, pas dans l'APK)
- `preview/index.html`             preview HTML reproduisant le layout
- `preview/render-phone-390x844.png`
- `preview/render-tablet-1024x768.png`
- `preview/render-tv-1920x1080.png`

## Compatibilité ascendante — IDs historiques préservés
- ✓ Sidebar : tileLiveTv, tileMovies, tileSeries, tileAccount, tileChangeServer
- ✓ Top bar : tileHistory, tileFavorites, tileSettings, tvConnectedAs, tvExpiration, tvClock
- ✓ Hero : ivHeroBackdrop, tvHeroTitle
- ✓ Resume pill : tileResume, tvResumeLabel
- ✓ Posters : tvPosterSectionLabel, recyclerHomePosters
- ✓ Animation : tile_focus_pop.xml (réutilisé sur cartes, CTA, pilules)

## Installation
1. Copier ces fichiers dans votre projet SolPlay en respectant l'arborescence.
2. Ne rien changer d'autre — IDs historiques préservés.
3. `./gradlew :app:assembleDebug` (signature ancrée sur `keystore/debug.keystore`,
   installation par-dessus l'APK existant possible).
4. `screenOrientation="landscape"` déjà défini pour HomeActivity dans AndroidManifest.

## Responsive Android TV / box TV / télécommande 10-foot
- Télécommande : sidebar + cartes catégories + panneau vedette + pilules vertes
  supportent toutes le focus `tile_focus_pop.xml` (scale 1.08 + translationZ 14).
- Overscan-safe : padding="20dp" sur la top bar + la rangée hero, marge 14dp sur
  les bordures latérales pour les TV avec rognage d'overscan.
- Aucune cible n'est hover-only : tout passe par `state_focused`.
- Tailles de texte : 18–34sp pour la lisibilité TV, ratios respectés en mode paysage.
