# Spring Boot REPL 0.10.0 – a négy munkafolyamat

## Telepítés

- [Plugin ZIP](build/distributions/Spring-Boot-REPL-0.10.0-local.zip): IDEA → Settings → Plugins → Install Plugin from Disk.
- [Bridge JAR](build/distributions/sb-repl-bridge-0.10.0-local.jar): az alkalmazáskódba írt új `capture` / `captureLazy` hívásokhoz.
- [A beépített agent külön](build/distributions/dev-runtime-agent-0.10.0.jar): a ZIP már ezt tartalmazza.

Telepítés után indítsd újra az IDEA-t és a fejlesztői alkalmazást. A normál Spring Boot konfigurációban maradnak a profilok és a meglévő **Enable Spring Boot REPL** beállítás. Régi custom agent útvonal helyett használd az új beépített agentet.

A bridge új koordinátája `hu.baader:sb-repl-bridge:0.10.0`. Ez a helyi verzió nincs közzétéve Maven-tárolóban. A checkoutból normál fejlesztői környezetben telepíthető:

```sh
mvn -f sb-repl-bridge/pom.xml install -Dgpg.skip=true
```

Ha a mellékelt, közvetlenül fordított bridge JAR-t telepíted a helyi Maven-repositoryba:

```sh
mvn install:install-file \
  -Dfile=build/distributions/sb-repl-bridge-0.10.0-local.jar \
  -DpomFile=sb-repl-bridge/pom.xml
```

Ezután az alkalmazás fejlesztői függőségében állítsd be a 0.10.0 verziót. A helyi ellenőrzés során nem írtam a Maven-repositorydba és nem telepítettem plugint a futó IDEA-ba.

## 1. Cellánkénti futtatás

```java
// %% Setup
var service = ctx.getBean(com.example.CvService.class);

// %% Input
var input = com.baader.devrt.SnapshotManager.load(
    "cv-input-capture", com.example.CvInputDto.class);

// %% Experiment
var result = service.process(input);
result
```

| Művelet | Viselkedés |
|---|---|
| Ctrl+Enter / Cmd+Enter, **Run cell / selection** | A kijelölést futtatja; kijelölés nélkül az aktuális cellát. |
| Shift+Enter, **Run + next** | Sikeres futás után a következő meglévő cellára lép, ha közben nem változott a dokumentum vagy a kurzor helye. |
| **Run all** | Kifejezetten a teljes munkafüzetet futtatja. |
| **Insert cell** | Új `// %%` határt szúr be az aktuális cella végére. |

A delimiter önálló kommentes sor, opcionális címmel. Java stringben, text blockban vagy blokkkommentben szereplő szöveg nem hoz létre cellahatárt. A delimiter sora a következő cellához tartozik. Delimiter nélküli munkafüzet egy cella. Mentés, megnyitás, history- vagy RECIPE-beszúrás továbbra sem futtat kódot.

## 2. Kódkiegészítés a szerkesztőben

**Ctrl+Space / Complete** natív IntelliJ lookupot nyit a kurzornál. Azonosító vagy pont gépelése után 300 ms késleltetéssel automatikusan is kér javaslatokat. A javaslatok a tényleges JShell-sessionből érkeznek; a deklarációs cellákat előbb futtasd le.

- A lekérés aszinkron; egyszerre legfeljebb egy kérés van úton.
- A forrás az aktuális cella kurzorig tartó része. A runtime a megelőző teljes statementeket csak elemzi, nem futtatja újra.
- Régi válasz nem írhatja át a közben módosított dokumentumot vagy más kurzorpozíciót. Futtatás, reset és sessionváltozás érvényteleníti a korábbi kiegészítéseket.
- Az explicit generikus típussal mentett DATA-lista visszatöltéskor megtartja az elemtípust a Java-változóban: például `items.get(0).date()` is kiegészíthető.
- A speciális popup csak a REPL munkafüzetéhez tartozik; a normál Java-forrásfájlok kiegészítése megmarad.

Az implementáció a session-protokollt és az [IntelliJ lookup API-t](https://plugins.jetbrains.com/docs/intellij/code-completion.html) kapcsolja össze.

## 3. Egyszeri snapshot-trigger

Az alkalmazásban egyszer helyezz el egy capture-pontot:

```java
import com.baader.sbrepl.bridge.SnapshotHelper;

SnapshotHelper.capture("cv-input", requestId, inputDto);
```

Drága DTO-projekcióhoz:

```java
SnapshotHelper.captureLazy("cv-input", requestId, () -> projectToDto(input));
```

A **Snapshots → Capture next** lapon:

1. Capture point: `cv-input`.
2. Snapshot name: például `cv-input-capture`.
3. Case ID: opcionális, pontos egyezést kérő szűrő.
4. Szükség esetén declared type, például `java.util.List<com.example.ItemDto>`.
5. **Arm once · 5 minutes**.

Az első megfelelő alkalmazáshívás lefoglalja a mentést, szinkron módon ment, majd kikapcsolja a triggert. A panelen látható a név, a fájlméret, az időtartam és a hiba. A `capture` siker esetén `true`, egyébként `false`; a szokásos capture-hibák nem buktatják el az alkalmazáskérést. A supplier kizárólag a lefoglalt capture-ben fut le. Élesítés nélkül, lejáratkor, nem egyező szűrőnél vagy agent nélkül nem fut le.

A JVM-ben egy capture-slot van, amelyet az élesítő REPL-session birtokol. Párhuzamos és rekurzív hívások sem készíthetnek kétszer mentést ugyanarra az élesítésre. **Disarm** egy még várakozó capture-t kapcsol ki. Reset, sessionlezárás és contextváltás elengedi a függő capture-t; egy már lefoglalt mentés befejeződhet. A session mix-in beállításai és a mentés metaadatai a projekció előtt rögzülnek, így egy közbeni reset nem törli a már elkezdett capture mezőkizárásait. Súlyos alkalmazás/JVM `Error` továbbra is továbbterjed, de nem hagy beragadt CAPTURING állapotot.

A korlát továbbra is **200 MiB**, a JSON-fejléccel együtt. A mentés szinkron, és stabil DTO/projekcióból célszerű végezni. Nem állítja meg a párhuzamos objektummódosításokat, és nem ment teljes JVM-et vagy adatbázis-tranzakciót. Az eredeti `SnapshotHelper.save` továbbra is feltétel nélküli mentés, amely hibát dob sikertelenség esetén.

## 4. Snapshot-összehasonlítás

A **Snapshots → Saved** lapon Ctrl/Cmd-kattintással válassz ki két DATA snapshotot, majd **Compare**. A Compare lapon látható a Before/After irány; **Swap before / after** felcseréli, **Previous / Next** lapozza az eltéréseket.

Az eredmény mezőútvonalat vagy tömbindexet, változástípust és rövid előtte/utána értéket mutat. A hiányzó mező különbözik a `null` értéktől. A tömböket index szerint hasonlítja össze. A fejléc időbélyege és más capture-metaadata nem számít adatváltozásnak. A JSON-pointerben `~0` jelenti a `~`, `~1` a `/` karaktert.

Az összehasonlítás a tárolt JSON-adatot olvassa; nem példányosít DTO-kat, nem hív gettereket és nem futtat RECIPE-t. Oldalanként legfeljebb 100 eltérés látszik, összesen legfeljebb 10 000 eltérés lapozható. A vizsgálat legfeljebb 1 000 000 csomópontot és 128 szint mélységet jár be. Korlátnál részleges eredményt jelez, nem állít egyezőséget. A két payload betöltése az alkalmazás heapjét használja; nagy snapshotokhoz a fájlméretek összegénél több memória kellhet.

## Ellenőrzés

| Ellenőrzés | JDK 17.0.17 | JDK 21.0.8 |
|---|---|---|
| Runtime, protokoll, snapshot, trigger, diff, bridge | **65 sikeres** | **65 sikeres** |
| Plugin, cellák, kiegészítés-adatkezelés, HTTP, checkbox, Swing-layout | **21 sikeres** | **21 sikeres** |
| Becsomagolt agent, Spring-indulás, profilok, HotSwap, executable JAR | **3 sikeres** | **3 sikeres** |
| Valódi TCP-integrációs próba | Socket-tiltás miatt nem futott | Socket-tiltás miatt nem futott |

Ez **89 külön teszt sikeres futását jelenti mindkét JDK-n**, nem 178 külön tesztesetet. A végső runtime-próbák 512 MiB-os szülő JVM-mel futottak; a 200 MiB-os határteszt a korábbi 96 MiB-os mentő/exportáló és 1 GiB-os visszatöltő alfolyamatokat is lefuttatta.

További ellenőrzések:

- Összes Java- és Kotlin-forrás sikeresen lefordult Java 17 célra; a Kotlin-fordításban négy meglévő deprecated API warning maradt.
- Külön platform/Java/plugin classloaderekkel az IC 2024.1.4 és IU 2025.1.7 próba is sikeres. A próba az új completion extensiont és snapshot UI-osztályokat is betölti.
- A ZIP és bridge JAR épek, a descriptorban 0.10.0 verzió és kötelező Java-plugin függőség szerepel. A beépített agent bájtpontosan megegyezik a külön JAR-ral. A bridge nem tartalmaz második runtime holdert.
- A JDK 17-es próbák feltártak egy meglévő HTTP-segédkód-darabolási hibát; a generátor klasszikus switch-címkéket használ, és mindkét JDK HTTP-tesztjei sikeresek.
- `git diff --check` sikeres.

Logok: `build/repair/workflow/runtime-tests-17.log`, `runtime-tests-21.log`, `plugin-tests-17.log`, `plugin-tests-21.log`, `agent-tests-17.log`, `agent-tests-21.log`, `final-plugin-compile.log`, `classloader-2024.1.log`, `classloader-2025.1.log`.

## A helyi ellenőrzés határa

A `./gradlew --offline --no-daemon check buildPlugin` az írásvédett `~/.gradle` wrapper-locknál megállt; a hiba a `build/repair/workflow/gradle-build.log` fájlban szerepel. A korábbi írható Gradle home-próbát a daemon socketnyitásának tiltása állította meg. A `-local.zip` és `-local.jar` közvetlen fordítóval létrehozott, tesztelt helyi artifactok; nem jelentenek sikeres teljes Gradle/Maven/Plugin Verifier futást.

Az agentpróbák a cache-ben elérhető Spring Boot 3.2.0 / Spring 6.1.1 és executable-JAR indításhoz Boot loader 2.7.18 mellett futottak. A CI-ben beállított Boot 3.5.6 teljes mátrix és a Plugin Verifier itt nem futott le. A natív lookupot és az új teljes panelmunkafolyamatot élő IDEA-felületen nem kattintottam végig; az SDK-fordítás, a komponens/adatkezelési tesztek és a külön classloader-próba sikeres.

A teljes szokásos ellenőrzés normál fejlesztői környezetben: `./gradlew check buildPlugin verifyPlugin`.
