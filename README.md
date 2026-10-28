# Agenda · Android-app

De agenda van [agenda.timvanbergen.nl](https://agenda.timvanbergen.nl) als Android-app.
De app toont de webinterface in een eigen venster, dus elke verbetering aan de
website zit direct ook in de app.

## Downloaden

De nieuwste APK staat altijd bij **Releases** (Agenda.apk). Bij elke push naar
`main` bouwt GitHub Actions automatisch een nieuwe, ondertekende versie.

Op een Samsung: open de link naar `Agenda.apk` op je telefoon, sta "onbekende apps"
toe voor je browser of Mijn bestanden, en installeer. Nieuwe versies installeer
je gewoon over de oude heen.

## Ondertekening

De APK wordt ondertekend met een vaste sleutel (`agenda-release.jks`). Die staat
**niet** in deze repo, maar naast het project op de Mac en als geheime
instelling in GitHub (`AGENDA_KEYSTORE_BASE64`, `AGENDA_KEYSTORE_PASSWORD`,
`AGENDA_KEY_ALIAS`, `AGENDA_KEY_PASSWORD`). Kwijt = geen updates meer mogelijk,
dus bewaar een back-up.

## Lokaal bouwen

```sh
./gradlew assembleRelease   # gebruikt ../agenda-release.properties
./gradlew assembleDebug
```
