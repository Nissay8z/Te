# MovieFR TV — lanceur externe, APK originale intacte

Le lanceur TV ne patche **pas** `com.mfr.moviefr` et ne tente pas de contourner les contrôles de signature, l'authentification ni la protection du fournisseur. Il lance **deux activités déjà exportées** par MovieFR V3.0.2 originale pour tester si le crash est propre à l'écran Splash.

Les deux activités sont :
- `com.mfr.moviefr.ui.login.splash.SplashActivity` — démarrage standard ;
- `com.mfr.moviefr.ui.MainActivity` — démarrage alternatif (test uniquement).

Si MovieFR plante à cause de son `AppApplication`, d'une bibliothèque native, d'une limitation Android TV ou d'un contrôle réseau, **ce lanceur ne peut pas corriger le problème**. Il sert à diagnostiquer le lancement en conservant les APK et signatures originales.

Pour une correction certaine, récupérer le rapport ADB du crash.
