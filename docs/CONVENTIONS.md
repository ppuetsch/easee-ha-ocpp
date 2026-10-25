# Konventionen für alle Komponenten

Lies zuerst `docs/PLAN.md` (v.a. Abschnitte 2, 3, 13) und die Verträge in `src/main/kotlin/de/puetsch/easeeocpp/{domain/model,ports,config,control/ControlState.kt}`. Diese Verträge **nicht ändern**; fehlt etwas, ergänze nur in deinem eigenen Paket und melde es im Abschlussbericht.

## Stack
Kotlin 2.4, Spring Boot **4.1.2** (Spring Framework 7, **Jackson 3**: Pakete `tools.jackson.*`, nicht `com.fasterxml.jackson.*`; Annotationen weiter `com.fasterxml.jackson.annotation`), JDK 25, Gradle KTS (`./gradlew test --console=plain`). Tests: JUnit 5, MockK, Awaitility, Allure (`io.qameta.allure.*` Annotationen `@Epic`, `@Feature`, `@Story`, `Allure.step`). Keine neuen Abhängigkeiten ohne zwingenden Grund; wenn doch, in `build.gradle.kts` ergänzen und im Bericht nennen.

## Stil (verbindlich, Clean Code)
- Keine kommentierten Abschnitte; wenn ein Kommentar erklärt *was* der Block tut → Funktion mit sprechendem Namen.
- Hierarchisch: Funktionen tun eine Sache auf einer Abstraktionsebene, 2–5 Unterschritte.
- Ausgeschriebene englische Namen, keine Ein-Buchstaben-Variablen (außer Schleifenindex in Einzeilern).
- KDoc/Kommentare nur für das *Warum* / die fachliche Regel, nie für das *Was*.
- Magic Numbers → benannte Konstanten (oben in der Datei bzw. `config/AppProperties.kt`).
- Domänen-Typaliase (`Watt`, `Ampere`), Prädikatsmethoden an Datenklassen (`hasVehiclePluggedIn()`), kryptische Ausdrücke in benannte Zwischenwerte auflösen.
- Typen überall explizit bei öffentlichen Signaturen.
- Keine Secrets im Code, nur über `AppProperties` (Env-Vars).
- GUI-Texte deutsch, Bezeichner englisch.

## Arbeitsweise
- Arbeite NUR in den dir zugewiesenen Verzeichnissen/Paketen (siehe Auftrag). Fasse andere Pakete nicht an.
- Schreibe zu jeder Klasse Unit-Tests (Allure-annotiert). `./gradlew test --console=plain` muss grün sein, bevor du fertig meldest. Der erste Gradle-Lauf dauert ~1,5 min.
- Zeit immer über injizierte `java.time.Clock`, nie `Instant.now()` direkt.
- Committe auf deinem Branch (kurze, sachliche Message). Kein Push.
- Abschlussbericht: Dateiliste, was getestet ist, Abweichungen/Annahmen/offene Punkte, ehrlich (auch Fehlschläge).
