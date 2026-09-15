# sb-repl projekt-audit

Dátum: 2026-09-13. Vizsgált állapot: `cf9f1cc` és a munkakönyvtárban már meglévő, nem commitolt módosítások.

## 1. Rövid döntési javaslat

**A projektből lehet jól használható, Springhez kapcsolt interaktív fejlesztőeszköz, de most nem elsősorban teljesítményhangolásra van szükség. Több alapfunkció szerződése hibás vagy nincs végig implementálva.**

Az első kiadási cél egyetlen megbízható kör legyen:

**Normál Spring Boot indítás → azonosított JVM és kész context → tartós REPL-session → kijelölt kód futtatása → azonosítható eredmény → inspector → explicit adatmentés.**

Megtartanám a JShellt, az in-process hozzáférést és a normál Spring Boot Run Configurationt. Nem építenék még több kerülőutas reflectiont, újabb kiértékelési módot vagy AI-funkciót az alapokra.

A snapshotot három külön fogalomra bontanám: **LIVE objektumhivatkozás, tartós DATA pillanatkép, újrafuttatható RECIPE**. Ezek nem helyettesítik egymást. Egy Spring bean, egy JSON-fájl és egy teljes JVM-állapot nem ugyanaz.

## 2. Hatókör és tényleges ellenőrzések

Áttekintettem az IntelliJ plugin indítási és editor-integrációját, a kliens/protokoll/session réteget, a `dev-runtime` kiértékelőit és agentjét, mindkét snapshot-implementációt, a Maven agent/bridge modulokat, a régebbi `spring-boot-integration` mintát, az AI/HTTP/history rétegeket, valamint a build- és publikálási folyamatot.

Az audit során **nem változtattam meg a működési forráskódot**, nem telepítettem plugint, nem indítottam üzleti alkalmazást, nem töröltem Run Configurationt. A meglévő módosításokat megőriztem. Az izolált próbák a figyelmen kívül hagyott `build/audit/` könyvtárba kerültek; a snapshot-próbák külön `user.home` alatt futottak, nem a saját mentéseiden.

| Ellenőrzés | Eredmény | Korlát |
| --- | --- | --- |
| `./gradlew --offline :dev-runtime:test buildPlugin` | Blokkolt: a Gradle wrapper saját cache-lock fájlja nem írható ebben a környezetben. | Nem jutott el a fordításig. |
| Telepített Gradle, külön írható Gradle home, offline cache | Blokkolt: a daemon helyi socketnyitását a környezet tiltja. | Nem készült új, Gradle-lel ellenőrzött plugin ZIP. |
| A teljes aktuális `dev-runtime` Java-forrás, `javac --release 17` | Sikeres. | Kézzel összeállított, helyben cache-elt dependency classpath. |
| A teljes aktuális plugin Kotlin-forrás, Kotlin 1.9.21, IDEA IC 2024.1.4 SDK, JVM target 17 | Sikeres, figyelmeztetésekkel. | Közvetlen compiler-futtatás; nem Gradle instrumentation, Plugin Verifier vagy IDE-s UI-teszt. |
| Runtime-próbák | 15 elvárásból 12 hibát jelzett. | A tényleges runtime osztályok; a wire-választesztek hálózat nélkül hívták a szerver handlerét. |
| Snapshot save/load két külön JVM-ben | 6 elvárásból 4 hibát jelzett. | Egyszerű `LinkedHashMap`, Jackson 2.15.3; nem minden adattípus teljes tesztje. |
| Kliens bencode-próbák | 4 elvárásból 3 hibát jelzett. | UTF-8 hosszak, részleges olvasás, standard status-lista. |
| Frissen fordított extension típusszerződése | `RunConfigurationExtension.isAssignableFrom(...) == false`. | A fordíthatóság nem bizonyítja az extension point helyes implementációját. |
| Meglévő három `@Test` metódus közvetlen meghívása | Mindhárom lefutott. Kettő törzse üres. | Nem teljes JUnit/Gradle discovery-futtatás; érdemi lefedettséget nem bizonyít. |

Részletes helyi bizonyítékok: `build/audit/runtime-probe.log:1`, `build/audit/snapshot-save.log:1`, `build/audit/snapshot-load.log:1`, `build/audit/codec-probe.log:1`, `build/audit/type-contract-probe.log:1`, `build/audit/kotlin-compile.log:1`, `build/audit/existing-tests-probe.log:1`. A próbakódok ugyanitt találhatók; a `clean` ezeket törölheti.

**Nem igazoltam teljes végponttól végpontig működő IntelliJ + Spring Boot indítást vagy teljesítménybenchmarkot.** A lent megnevezett CPU-, memória- és UI-kockázatok egy része kódvizsgálatból következik, nem mért profilozásból.

## 3. A legsürgősebb hibák

A P0 kiadást blokkoló vagy közvetlen biztonsági kockázat; P1 az alapműködés helyreállításához szükséges; P2 az alapok után következik.

### F01 — P0: a korábbi Run Configuration hiba konkrét oka megmaradt

Helyek: `src/main/kotlin/hu/baader/repl/runner/SpringBootReplRunConfigurationExtension.kt:30`, `src/main/resources/META-INF/plugin.xml:1`, `src/main/resources/META-INF/plugin.xml:66`.

- Az osztály `RunConfigurationExtensionBase`-ből öröklődik, miközben a regisztrált Java extension point `com.intellij.execution.RunConfigurationExtension` példányt vár. Utóbbi maga is a base leszármazottja; a kettő nem felcserélhető. Ezt a helyi IDE API-jával és a frissen fordított osztályon is ellenőriztem.
- A `use-idea-classloader="true"` a Java plugin osztályaihoz szükséges normál plugin-classloader útvonal ellen dolgozik. A korábbi `ApplicationConfiguration` és `JavaFileType` hiányok ezzel összhangban vannak.
- Az `isApplicableFor` elején egy Java-plugin osztálytól függő saját konfigurációt is vizsgál, majd minden `Throwable`-t `false`-ra fordít. Így az összeomlás eltűnhet, miközben a checkbox is eltűnik. Ez nem javítás.

**A korábbi „biztosan rossz az IDE-verzió” magyarázat nem volt megalapozott.** A gépen IDEA 2025.1.7, build `251.29188.11` található; a plugin deklarált tartománya `241.0–252.*`. A hibás öröklés önmagában elegendő a cast-hibához. Két külön plugin classloader megjelenése a stack trace-ben önmagában nem bizonyít duplikált telepítést. A JetBrains normál esetben külön classloadert használ pluginonként, a függőségek láthatóságát deklarált dependency biztosítja. [JetBrains: Class Loaders](https://plugins.jetbrains.com/docs/intellij/plugin-class-loaders.html).

Javaslat: normál plugin classloader, megfelelő Java dependency, helyes `RunConfigurationExtension`-öröklés és `updateJavaParameters`-alapú agent-injektálás. A normál Spring Boot konfiguráció profiljai, környezeti változói, argumentumai és classpathja maradjanak érintetlenek. Kompatibilitási teszt kell, nem találgatott osztálynevek és verziószámok.

### F02 — P0: a runtime Java-kódfuttatást kínál nem csak localhoston

Hely: `dev-runtime/src/main/java/com/baader/devrt/MiniNreplServer.java:22`.

`new ServerSocket(port)` nem korlátozza a listenert loopback címre. A handlerben nincs hitelesítés; az elérhető műveletek között tetszőleges Java-kiértékelés és class reload szerepel. A kliens `127.0.0.1` beállítása **nem** korlátozza a szerver hálózati kitettségét. Tényleges távoli elérhetőség a hálózattól és tűzfaltól függ; ezt nem próbáltam ki.

Javaslat: explicit loopback bind, futásonkénti véletlen token, a token nélküli műveletek elutasítása, korlátozott üzenetméret és kapcsolatszám. Távoli használat csak külön engedélyezett biztonságos csatornán. Éles környezetben alapból ne induljon REPL. Egy REPL teljes alkalmazásjogosultságú kódfuttatás, nem sandbox.

### F03 — P0: a kliens és a runtime más protokollt beszél

Helyek: `src/main/kotlin/hu/baader/repl/nrepl/NreplService.kt:108`, `src/main/kotlin/hu/baader/repl/nrepl/NreplService.kt:210`, `dev-runtime/src/main/java/com/baader/devrt/ReplHandler.java:20`, `dev-runtime/src/main/java/com/baader/devrt/MiniNreplServer.java:69`.

| Funkció | Kliens | Runtime |
| --- | --- | --- |
| Session reset | `session-reset` | `session/reset` |
| Importlista | `imports-get` | `imports/get` |
| Import hozzáadása | `imports-add` | `imports/add` |
| Snapshot mentés/töltés/lista | Többek között `snapshot-save`, `snapshot-load`, `snapshot-list-simple` | Nincs snapshot-ág a handlerben. |

További hiba: a szerver `describe` snapshot-műveleteket hirdet, amelyeket nem kezel. Még a helyes `imports/get` esetén is eldobja az `imports` mezőt a válaszfordító. Az ismeretlen művelet `status=error` eredményét egyszerű `out` szövegként, majd `done`-ként továbbítja.

**Ez megmagyarázza, miért látszik több gomb működőnek, miközben nem történik meg a művelet.** Önmagában az opnevek átírása nem elég: a válaszmezőket és a hibastátuszokat is helyre kell állítani.

Javaslat: egyetlen verziózott protokollszerződés, közös műveletdefiníciók, valódi capability-lista, minden hirdetett műveletre kliens–szerver szerződésteszt. Nem támogatott funkció ne legyen aktív a UI-ban.

### F04 — P1: elvesző válaszok és hibás kapcsolatábrázolás

Helyek: `src/main/kotlin/hu/baader/repl/nrepl/NreplClient.kt:36`, `src/main/kotlin/hu/baader/repl/nrepl/NreplClient.kt:89`, `src/main/kotlin/hu/baader/repl/nrepl/NreplService.kt:33`.

- A `pending.remove(id)` már az első válaszkeretnél törli a callbacket. Egy későbbi `err` vagy végső `done` nem jut el ugyanahhoz a kéréshez tartozó callbackhez.
- EOF esetén a kliens olvasóciklusa újra és újra `null`-t kaphat megszakítás nélkül; a service kapcsolati flagje nem frissül a socket tényleges állapotából. Ez CPU-pörgést és hamis „Connected” állapotot okozhat.
- A `connectAsync` neve ellenére közvetlen, blokkoló socketcsatlakozást végez. A hibaág `onComplete(false)` jelzését a UI „legacy módú sikeres kapcsolatként” is kezelheti. Külön siker/hiba/mód eredménytípus kell.
- A bencode hosszakat karakterekben számolja. A futtatott `árvíz` próba ötöt írt a szükséges hét UTF-8 byte helyett. A részleges olvasási próba is hibázott; a standard nREPL status-listát pedig eldobta.
- Egyetlen globális `lastEvalSnippet` próbál több egymást átfedő kéréshez eredményt párosítani.

A nREPL-ben egy kéréshez több válaszüzenet tartozhat; a lezárást a `done` státusz jelzi. [nREPL: Design overview](https://nrepl.org/nrepl/design/overview.html).

Javaslat: byte-alapú codec, teljes beolvasás, strukturált listák/mapek, request-ID szerinti eseményfolyam, callback lezárása csak terminal státusznál, timeout és disconnect esetén minden függő kérés determinisztikus lezárása. A hálózatkezelés ne az EDT-n fusson.

### F05 — P1: a JShell előfeldolgozása érvényes Java-kódot veszít el

Helyek: `dev-runtime/src/main/java/com/baader/devrt/JShellSession.java:60`, `dev-runtime/src/main/java/com/baader/devrt/JShellSession.java:186`, `dev-runtime/src/main/java/com/baader/devrt/ReplHandler.java:49`.

Futtatással igazolt példa:

```java
int firstAudit = 20;
int secondAudit = 22;
firstAudit + secondAudit
```

Egyetlen kérésként elküldve nem `42` jött vissza: az első deklaráció lefutott, a második változó hiányzott. Az importtal egy sorban álló deklaráció is elveszett. A kézi sor-/üres sor-/kapcsoszárójel-feldolgozás nem Java-parser.

További igazolt gond: a `System.out.println` kimenete az alkalmazás stdoutjára került, az eval-eredmény `output` mezője üres maradt. Egy sikeres importot viszont az `Imports updated.` szöveg miatt hibának minősített a handler.

Javaslat: a JShell `SourceCodeAnalysis.analyzeCompletion` API-jával bontani teljes snippetekre a bemenetet, majd sorban futtatni. Ez az API kifejezetten erre, valamint sessionfüggő completionre és típusinformációra szolgál. [JDK: SourceCodeAnalysis](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jshell/jdk/jshell/SourceCodeAnalysis.html).

Külön adat legyen a fordítási diagnosztika, runtime exception, stdout, stderr és a visszatérési érték. A kimenet elfogását in-process környezetben külön tesztelni kell: a globális `System.setOut` cseréje más alkalmazásszálak logját is elterelheti. Hibás snippetnél legyen látható, mi futott le már; a teljes kérés nem atomi tranzakció.

### F06 — P1: nincs valódi session- és context-életciklus

Helyek: `dev-runtime/src/main/java/com/baader/devrt/MiniNreplServer.java:18`, `dev-runtime/src/main/java/com/baader/devrt/ReplHandler.java:13`, `dev-runtime/src/main/java/com/baader/devrt/ReplHandler.java:136`, `src/main/kotlin/hu/baader/repl/runner/SpringBootReplRunConfiguration.kt:64`.

- A `clone` session-ID-t ad, de egyetlen közös `JShellSession` szolgál ki minden klienst. A próba során B session elérte A változóját.
- Több kliens külön worker szálról férhet ugyanahhoz a JShellhez; az `AtomicReference` nem eval-sor és nem kölcsönös kizárás.
- A sikeres `bind-spring` új JShellt készít, így eltünteti a korábbi definíciókat. `ctx` változót viszont maga a session nem definiál; ezt a kliens több külön helyről próbálja kipótolni.
- A runner 1500 ms után egyszer próbál kapcsolódni. Az agent portjának indulása, a nREPL-session létrejötte és a Spring teljes felállása három külön állapot.
- Az alkalmazásszintű host/port beállítás és a „már connected, tehát kész” logika nem azonosítja, hogy melyik JVM-hez tartozik a kapcsolat.

Javaslat: sessionönként soros végrehajtó és sessiontár; azonosított `processId`, `sessionId`, `contextEpoch`; idempotens bind; valódi READY-jelzés; folyamatleálláskor takarítás. Devtools restartnál a korábbi contexthez tartozó hivatkozások legyenek lejártak, ne maradjanak látszólag érvényesek.

### F07 — P1: az agent nem önálló, a buildútvonalak eltérnek

Helyek: `build.gradle.kts:29`, `dev-runtime/build.gradle.kts:18`, `dev-runtime/src/main/java/com/baader/devrt/Agent.java:44`, `sb-repl-agent/pom.xml:23`.

A plugin a normál `jar` kimenetet csomagolja, nem a runtime-függőségeket is tartalmazó agentet. A helyi agent JAR vizsgálata szerint nincs benne Byte Buddy; az `Agent` viszont közvetlenül használja. A célalkalmazás véletlenül jelen lévő Byte Buddy verziójától függő indulás nem „zero-config”. Az agent inicializálásának hibája nem mindenhol van elválasztva az alkalmazásindítástól.

A Maven agent ugyanazt a Java-forrást veszi át, de a POM csak Spring dependencyt sorol fel: a Byte Buddy és a közvetlen SLF4J-használat nincs megfelelően leképezve. Ráadásul `Can-Retransform-Classes=false`, miközben az agent `RETRANSFORMATION` stratégiát kér. Ezt a Maven-útvonalat most nem építettem végig.

Javaslat: egy hiteles agent-artifact, annak beágyazása és publikálása; agent-saját instrumentációs dependencyk izolált/shadelt csomagolása, Springet viszont ne csomagolj második példányban. „Minimal Spring app + csak az agent” indulási próba kell, nem az aktuális nagy backend dependencykészletére támaszkodás.

### F08 — P1: a snapshot jelenleg nem megbízható tartós mentés

Helyek: `dev-runtime/src/main/java/com/baader/devrt/SnapshotManager.java:61`, `dev-runtime/src/main/java/com/baader/devrt/SnapshotManager.java:95`, `dev-runtime/src/main/java/com/baader/devrt/SnapshotManager.java:278`, `dev-runtime/src/main/java/com/baader/devrt/SnapshotStore.java:19`, `sb-repl-bridge/src/main/java/com/baader/sbrepl/bridge/SnapshotHelper.java:22`.

Igazolt hibák:

- Első mentéskor a JSON kiírása megelőzi a `typeCache` feltöltését; a kiíró innen olvasná a `.type` tartalmát. A JSON megvan, a típusfájl nincs. Új JVM-ben a sima és az explicit típusú `load` is `null`-t adott vissza ugyanarra az egyszerű mapre.
- A `../escaped-audit` névvel a mentés kilépett a snapshot-könyvtárból. A próba csak az audit izolált saját mappájába írt. A fájlneveket nem szabad közvetlenül felhasználói névből képezni.
- A LIVE pin ugyanazt a módosítható objektumot tartja. Egy későbbi lista-hozzáadás a „mentett” értéket is megváltoztatta. Ez live handle-ként helyes lehet, időpillanatként nem.

További kódvizsgálati megállapítások: a fájlíró elnyel IO-hibákat; a JSON és típus nem atomi egység; sikertelen serializáció csendben memóriatárolásra vált és törölheti a régi JSON-t; a két store nincs összhangban; nincs projektazonosítás, séma/verzió, TTL vagy kvóta. A `SnapshotHelper` két helyre ír, majd a live példányt részesíti előnyben, ezzel elfedi a tartós visszatöltés hibáját. Egyes logok adatértékeket is kiírnak.

Javaslat: az alábbi hibrid modell és egyetlen `SnapshotService`. Az UI snapshot-végpontjainak bekötése önmagában ezeket a hibákat nem javítaná ki.

### F09 — P1: a felület és a végrehajtás szemantikája eltér

Helyek: `src/main/kotlin/hu/baader/repl/ui/JavaReplToolWindowFactory.kt:213`, `src/main/kotlin/hu/baader/repl/ui/JavaReplToolWindowFactory.kt:610`, `src/main/kotlin/hu/baader/repl/ui/JavaReplToolWindowFactory.kt:1063`, `src/main/kotlin/hu/baader/repl/ui/LoadedVariablesPanel.kt:185`, `src/main/kotlin/hu/baader/repl/actions/EvaluateAtCaretAction.kt:125`.

- A jEval címke külön `JavaCodeEvaluator`-módot ígér, de a küldés `eval`, a runtime pedig az `eval` és `java-eval` kérést is ugyanoda irányítja.
- Több generált snippet top-level `return`-t használ, ami nem a JShell kifejezésmódja. Az egyszerűsített snapshot panel néhány Java-string/text-block sablonja is hibás.
- Az Advanced scratch mentéséhez session-reset + teljes újrajátszás van kötve. A reset-protokoll javítása ezt is életre keltheti: fájlmentéskor újrafuthat egy adatbázisírás vagy HTTP-hívás. **Mentés nem jelenthet futtatást.**
- A caret-eval action explicit rejtett/letiltott. A completion contributor nincs regisztrálva és a toolwindow nem az enhanced editor providert használja.
- A Loaded Variables panel nem a JShell tényleges változólistájából dolgozik; `null` helyettesítőértékkel és sikertelen kiértékelés után is létrejöhet UI-bejegyzés.
- A kiértékelés elküldésekor a bemenet törlődhet a lezáró válasz előtt; a transcript korlátlanul nő, és ismételten átrendezi a foldingokat.

Javaslat: egyetlen Java REPL-mód, tartós munkafüzet/kijelölés-futtatás, hibánál megmaradó bemenet, runtime-alapú vars/list és inspector. Valódi kérésazonosítóhoz kötött eredmény, látható „queued/running/failed/done” állapot.

### F10 — P1: a beanlista nem puszta metadata-lekérdezés

Helyek: `dev-runtime/src/main/java/com/baader/devrt/ReplHandler.java:118`, `dev-runtime/src/main/java/com/baader/devrt/AutoBinder.java:94`, `sb-repl-bridge/src/main/java/com/baader/sbrepl/bridge/DevRuntimeBridgeConfig.java:23`.

A beanlista minden névre `getBean`-t hív. Ez lazy/prototype beanelemeket is példányosíthat; nagy contextben lassú és mellékhatásos. A proxy konkrét osztálynevét visszaadva a UI nem feltétlenül fordítható vagy használható Java-típust kap.

Az auto-binder betöltött osztályok statikus mezőit/metódusait vizsgálja; ezek olvasása/hívása inicializálást is kiválthat. A bridge context-hozzárendelése még nem jelent teljes alkalmazáskészültséget. Ugyanannak a `SpringContextHolder` FQN-nek két JAR-ban való szereplése önmagában nem oldja meg a külön classloaderek problémáját.

Javaslat: lifecycle-alapú context regisztráció, explicit kész/lezárt esemény, contextazonosító. A beanlista bean-definition/metadatából készüljön, ne példányosítsa végig a containert. Bean kiválasztásnál név + publikus interfész/típus; példányosítás csak kérésre.

### F11 — P2: az AI/HTTP/history funkciók megelőzték az alapok stabilitását

Helyek: `src/main/kotlin/hu/baader/repl/settings/PluginSettingsState.kt:10`, `src/main/kotlin/hu/baader/repl/settings/PluginSettingsState.kt:25`, `src/main/kotlin/hu/baader/repl/ui/HttpRequestsPanel.kt:517`, `src/main/kotlin/hu/baader/repl/ui/HttpRequestRunner.kt:69`.

Az API-kulcs plain persistent state-ben tárolódik; a HTTP case-ek header/body adatai XML-be mentődnek, a REPL-history pedig teljes kódot tartalmazhat. Ezek titkot vagy személyes adatot is tárolhatnak. Nem vizsgáltam vagy másoltam ki tényleges felhasználói titkokat.

Az HTTP runner csak connect timeoutot állít, teljes request timeoutot nem; minden HTTP-választ SUCCESS-ként jelöl, státuszkódtól függetlenül, és a teljes bodyt memóriába/logba viszi. Az AI-felület szövege „későbbi LLM hívást” említ, de a kód már külső API-t hív. Pozitívum: a generált kódot beilleszti, nem futtatja automatikusan.

Javaslat: IDE PasswordSafe a titkoknak, paraméterezett HTTP környezetek, history opt-out és törlés, redakció, body-/outputlimit. Külső AI-küldés előtt látható legyen a tényleges prompt és a küldött metadata; ezt a REPL alapműködésétől függetlenül lehessen kikapcsolni.

### F12 — P1: a jelenlegi tesztek/build nem védenek a regresszióktól

Helyek: `dev-runtime/src/test/java/com/baader/devrt/JShellSessionTest.java:8`, `.github/workflows/publish-to-central.yml:1`, `.github/workflows/publish-github-packages.yml:1`, `build.sh:27`.

Két teszt törzse teljesen kommentelt. A harmadik azt igazolja, hogy az új sessionben nincs egy változó, de nem ellenőrzi, hogy az előző sessionben valóban létrejött-e. A közvetlen futtatás mindhárom esetben „sikeres”, miközben az auditpróbák alapfunkciókat törtek el.

A meglévő workflow-k címkére Maven-artifactokat publikálnak; nem tesztelik PR-on a plugin–agent együttműködését. A build script `buildPlugin`-t hív, külön teszt/Verifier kapu nélkül. A régebbi integráció és a Maven-wrapper nincs a root Gradle modulgráfban.

Javaslat: kötelező PR build + runtime teszt + wire contract teszt + IDE kompatibilitás + csomagolt agent smoke test. Csak ugyanebből az ellenőrzött artifactból legyen kiadás. Az auditban készült reprodukciókat rendezett regressziós tesztekké kell alakítani; a temporális `build/audit` nem helyettesíti ezeket.

## 4. Mitől lenne Clojure-szerűen jól használható?

Nem a konzol megjelenését kell másolni, hanem a rövid visszacsatolási kört: **kód → élő érték → vizsgálat → kisebb módosítás → újraértékelés**, újraindítás és kézi újrabindolás nélkül. A Clojure REPL például megőrzi az utolsó eredményeket és a legutóbbi hibát. [Clojure: REPL](https://clojure.org/reference/repl_and_main).

| Képesség | Javasolt sb-repl viselkedés |
| --- | --- |
| Egy kijelölés / aktuális teljes snippet futtatása | Nem kell teljes fájlt vagy reflektív boilerplate-et futtatni. |
| Tartós definíciók és importok | A következő kérés ténylegesen látja őket; bind nem nullázza. |
| Utolsó eredmények | `last1`, `last2`, `last3`, `lastError` jellegű runtime-hivatkozások; nem csak stringek. |
| Inspector | Típus, méretkorlátos előnézet, lapozott lista/map, részérték új változóhoz kötése. |
| Completion és dokumentáció | A session definíciói + projekt típusai + bean metadata, nem fix szövegsablonok. |
| Munkafüzet | Menthető snippetek, cellánkénti futtatás, látható eredmény/proveniencia; mentéskor nincs végrehajtás. |
| Megszakítás | Független vezérlőút és együttműködő cancellation; ne akadjon a UI egy hosszú eval mögött. |
| Újraindulás | Egyértelmű „a régi session lejárt”; legfeljebb explicit, biztonságos setup visszaállítása. |

**Java-korlát:** egy JShellben újradefiniált metódus/osztály nem automatikusan a már futó Spring bean új implementációja. A JVM HotSwap más mechanizmus; az aktív hívások és létező példányok nem egyszerűen újrainicializálódnak. Strukturális módosításnál támogatott reload vagy restart szükséges. [JDK: Instrumentation](https://docs.oracle.com/en/java/javase/21/docs/api/java.instrument/java/lang/instrument/Instrumentation.html).

In-process tetszőleges Java-kódot nem lehet általánosan biztonságosan, mellékhatásmentesen „visszavonni” vagy erőszakosan leállítani. Ez az architektúra korlátja, nem egy jobb Stop gomb hiánya. A timeout nem adatbázis-rollback.

### JShell vagy valódi Clojure?

**Alapjavaslat:** először egy stabil Java/JShell útvonal, ne két félig működő engine. Ha viszont a valódi Clojure-fejlesztési élmény fontosabb a Java-szintaxisnál, érdemes külön, dev-only Clojure + valódi nREPL adaptert választani. Clojure-ből a Java/Spring objektumok meghívhatók Java interoppal; ez nem igényli az üzleti alkalmazás Clojure-re átírását. [Clojure: Java interop](https://clojure.org/reference/java_interop).

A repository régi `spring-boot-integration/NreplServerComponent.java:20` mintája ebbe az irányba mutat, de nem a jelenlegi agent integrált, ellenőrzött motorja. Ezt nem kapcsolnám be egyszerűen kész megoldásként. A Clojure-engine sem oldja meg magától a hibás snapshotot, a Java HotSwapot vagy a devtools classloader-életciklust.

## 5. Javasolt célarchitektúra

Ezek logikai komponensek; nem szükséges mindegyikből külön deployolható szolgáltatást vagy új Gradle-modult készíteni.

```text
IntelliJ editor / munkafüzet / inspector
                    |
       Connection + request/event modell
                    |
      Verziózott, hitelesített protokoll
                    |
              Session manager
               /          \
       JShell engine     Snapshot service
               \          /
          Object handles + Context registry
                    |
         Spring lifecycle / app classloader
```

- Egyetlen protokollszerződés. Ha a Clojure/nREPL kompatibilitás cél, szabványos byte codec és tényleges session/middleware szemantika kell. Ha nem cél, őszintén saját protokollként kell verziózni; nem érdemes csak a javítás elkerülésére transportot cserélni.
- A kapcsolat állapotai: `DISCONNECTED → CONNECTING → SESSION_READY → WAITING_CONTEXT → READY`, továbbá `RECONNECTING` és `FAILED`. A socket megléte nem READY.
- Sessionönként soros eval-végrehajtás, korlátos sor, külön vezérlőút. Contextváltáskor új epoch, régi handle-ök invalidálása és erőforrás-felszabadítás.
- A runtime tartja az objektumokat, az IDE alapból csak `handleId`, típus, rövid előnézet és lejárati információt kap. Nem küldünk teljes objektumgráfot minden eredményhez.
- A contexthez tartozó classloadert explicit kezelni és tesztelni kell: sima classpath, Boot executable JAR és devtools külön eset. A háromféle agent-resolver és a két párhuzamos snapshot-tár helyett egy-egy hiteles belépési pont legyen.

## 6. Snapshot: a javasolt hibrid megoldás

### 6.1. Három mentési mód, világos ígéretekkel

| Mód | Mit tárol? | Restart után | Mire való? |
| --- | --- | --- | --- |
| LIVE / Pin | Ugyanazon JVM/session objektumhivatkozása, TTL-lel és kvótával. | Lejár; nem tölthető vissza fájlból. | Bean, proxy, nagy vagy nem serializálható objektum interaktív vizsgálata. |
| DATA / Freeze | Korlátozott, serializált adatprojekció és típus/séma metadata. | Kompatibilis codec/típus esetén betölthető. | DTO, request/response, lista/map, tesztfixture, összehasonlítás. |
| RECIPE / Setup | Kód és input-hivatkozások, nem élő VM-állapot. | A felhasználó kérésére újrafuttatható. | Importok, beanlookup, tiszta adattranszformációk új sessionben. |

Spring bean tartós „mentése” inkább `beanName + publicType` descriptor legyen, amely a **jelenlegi** contextben oldódik fel újra. Nem mentenék teljes `ApplicationContext`, datasource, EntityManager, szál, socket vagy proxygráfot.

**Alapértelmezett UX:** eredményen jobb klikk → Inspect / Pin live / Freeze data / Export fixture. A mentés a már elkészült eredmény handle-jét kapja, **ne futtassa újra az eredeti kifejezést**. Így egy mentés nem ismétel meg például egy üzleti írást.

### 6.2. Egyetlen tartós formátum

Egy verziózott envelope tartalmazza legalább:

```json
{
  "schemaVersion": 1,
  "snapshotId": "generated-id",
  "name": "example-page",
  "kind": "DATA",
  "applicationId": "local-dev-app",
  "contextEpoch": "context-id",
  "capturedAt": "2026-09-13T10:00:00Z",
  "declaredType": "java.util.List<example.ItemDto>",
  "codec": "json-dto-v1",
  "payload": []
}
```

Ez terv, nem a jelenlegi implementáció formátuma. A deklarált Java-típust külön validálni kell; a mező nem engedély tetszőleges osztály példányosítására. A `contextEpoch` eredetinformáció a DATA esetén, nem kötelező egyezőség: a LIVE hivatkozásokkal ellentétben az adatot éppen új JVM-ben is használni akarjuk.

- Egy atomi mentési egység: ideiglenes fájl + lezárás + atomi átnevezés, ahol támogatott; biztonságos alternatíva máshol. Hiba esetén maradjon meg az előző érvényes snapshot.
- Generált fájlazonosító, felhasználói név csak metadata; normalizált gyökérút és symlink-kezelés. Ne legyen `../`-alapú kilépés.
- Projektenként/alkalmazásonként elkülönített tár; byte-, elemszám-, mélység- és retentionlimitek.
- Hibánál explicit eredmény: „csak LIVE-ként tárolható” vagy serializációs hiba. Tilos a tartós mentés csendes memóriává visszaminősítése.
- Érzékeny mezők kizárása, előnézet, opcionális titkosított tárolás. A snapshot tartalma alapból ne kerüljön logba vagy Gitbe.
- Visszatöltés után a runtime igazolja a valódi változókötést; a UI csak ezután mutasson Loaded állapotot.

### 6.3. Jackson mix-in: hasznos, de csak egy réteg

Ha a „jobb mix” alatt Jackson mix-int is értesz: **igen, snapshot-specifikus serializálási szabályokra jó megoldás**. Külső/domain típusokhoz is társíthatók annotációk az eredeti osztály módosítása nélkül. [FasterXML: Jackson mix-in annotations](https://github.com/FasterXML/jackson-docs/wiki/JacksonMixInAnnotations).

Javasolt kombináció:

1. Az alkalmazás támogatott mapper-konfigurációjából készített külön snapshot mapper/adapter; a globális Spring `ObjectMapper`-t ne módosítsuk helyben.
2. JavaTime/record/generic collection típusok explicit tesztjei, szükséges modulokkal.
3. Mix-in vagy adapter a technikai, érzékeny és rekurzív mezők kihagyására.
4. JPA/Hibernate entitásoknál elsődlegesen DTO-projekció, nem automatikus lazy-gráfbejárás. A getter meghívása is indíthat SQL-t.
5. Inputból kapott polymorphic típusnevekhez ne legyen korlátlan default typing/deserializáció.

A mix-in nem oldja meg a változó/session megőrzését, az atomi fájlírást, a rossz protokollt vagy a teljes VM visszaállítását. A redaktált export és a teljes típusazonos roundtrip külön ígéret; ha mezőket kihagyunk, ezt a visszatöltési szemantikában is vállalni kell.

## 7. Hogyan használd addig?

Ez átmeneti, korlátozott munkamód, nem annak állítása, hogy a teljes plugin már megbízható.

1. Csak izolált fejlesztői környezetben dolgozz. A listener hálózati hibájának javításáig ne tedd elérhetővé a REPL-portot más gépekről.
2. A működő **normál Spring Boot** konfigurációban tartsd a profilokat és az environmentet. A plugin miatt ne hozz létre párhuzamos, eltérő argumentumú alkalmazásindítást. Ellenőrizd az alkalmazás logjában a tényleges aktív profilokat.
3. Várd meg a sikeres alkalmazásindítást. A korábbi `project.llm.remove-required` konfigurációs hiba az üzleti alkalmazás külön indulási hibája; annak kijavítása nem javítja a plugin hibás extensionjét.
4. Ha az adott telepített plugin és agent már képes csatlakozni, először csak két külön kérésben futtasd: `int checkValue = 41;`, majd `checkValue + 1`. Az auditban ez a tartós változóút működött.
5. Egy kérésben egy teljes top-level deklarációt vagy kifejezést küldj. Ne importtal egy sorba írt deklarációt, ne teljes alkalmazásfájlt és ne top-level `return`-t.
6. A snapshot UI-ra még ne bízz kizárólagos mentést. Fontos eredményt adatként, ellenőrizhető módon exportálj; ne feltételezd, hogy egy „saved” üzenet restartálló mentést jelent.

Ha a holderben már megvan a Spring context, a kézi bootstrap egyetlen top-level deklaráció lehet:

```java
org.springframework.context.ApplicationContext ctx = (org.springframework.context.ApplicationContext) com.baader.devrt.SpringContextHolder.get();
```

Utána külön kérésben `ctx != null`. Csak sikeres eredmény esetén például `java.util.Arrays.asList(ctx.getBeanDefinitionNames())`. Ez a runtime API-jának megfelelő kerülőút; az IntelliJ-ben történő teljes futtatását ebben az auditban nem ellenőriztem. A hiányzó contextet vagy classloader-problémát önmagában nem oldja meg. A végleges megoldásban ezt nem a felhasználónak kell kézzel elvégeznie.

A kényelmes, javítás utáni napi munkamód: külön scratch/munkafüzet, egyszeri beanlookup névvel és publikus típussal, kis kijelölések futtatása, eredmény inspectálása, DATA snapshotból tesztfixture. Automatikus teljes fájl-újrajátszás helyett explicit „Run cell” és „Run setup”.

## 8. Megvalósítási sorrend és elfogadási feltételek

| Szakasz | Munka | Akkor kész, ha… |
| --- | --- | --- |
| 1. Indítás és biztonság | F01, F02, F07: extension/classloader, agent-artifact, loopback/token. | Checkbox ki/be, IDE restart és konfigurációmentés után sem sérülnek a Run Configurationök; REPL nélkül az indítás változatlan; minimal app agenttel elindul; nem hitelesített kérés tiltott. |
| 2. Stabil REPL-mag | F03–F06: wire contract, parser, request lifecycle, session/context állapotgép. | Több snippet, import, késői hiba, stdout, disconnect, reconnect és két session tesztje determinisztikusan helyes. |
| 3. Napi használhatóság | F09–F10: egy engine, editor actionök, completion, inspector, metadata-alapú beanlista. | Mentés nem futtat kódot; futtatás nem töröl elvesző inputot; beanlista nem inicializál végig lazy/prototype beanelemeket. |
| 4. Snapshot v1 | F08: LIVE/DATA/RECIPE, egy store API, codec és atomi tár. | Új JVM-ben typed roundtrip; generikus DTO-lista/dátum/null tesztek; íráshibánál megmaradó régi adat; nincs path traversal vagy csendes fallback. |
| 5. Kiadási kapu és kiegészítők | F11–F12: CI, verifier, dokumentáció, AI/HTTP titokkezelés. | A csomagolt artifactot ellenőrzi a CI, és a README csak ténylegesen kipróbált funkciót ígér. A CI alapjai már az 1. szakasztól szükségesek. |

Minimális tesztmátrix: JDK 17/21, egy támogatott Boot 3 minta és a konkrét érintett Boot 3.5.6 alkalmazás, a deklarált legkorábbi IDEA SDK és a használt IDEA 2025.1.7. Devtools, lassú indulás, portütközés, két alkalmazás, classpath/Boot JAR külön eset. Újabb IDE-tartományt csak külön ellenőrzés után szabad ígérni.

Mérési célként, nem jelenlegi eredményként: meleg sessionben egyszerű eval p95 legfeljebb néhány száz ms; 1000 egymást követő kérésnél nincs elveszett vagy rossz snippethez rendelt válasz; EOF után nincs olvasóciklus-pörgés; lezárt session után nem nő korlátlanul a handle/transcript/memória. Az üzleti művelet idejét külön kell mérni a transport- és UI-késleltetéstől.

## 9. Amit most nem javaslok

- Nem teljes újraírás, nem újabb AI-funkció és nem az összes dependency vak frissítése az első lépés.
- Nem IDE-cache-ek vagy Run Configurationök törlése a hibás extension javítása helyett. Az eltűnt konfigurációk visszaállítása külön, mentésből vizsgálandó feladat.
- Nem egész Spring context/JPA-gráf serializálása, és nem JVM-checkpoint ígérete JSON-mentésként.
- Nem „retry mindent”: hálózati bizonytalanság esetén egy üzleti kód újraküldése megismételheti a mellékhatást.
- Nem Clojure-szemantika ígérete kizárólag Java HotSwappal. Vagy stabil JShell-alapú interaktív Java munkamód, vagy tudatosan vállalt opcionális valódi Clojure-engine.

**Összegzés:** előbb az indítás–protokoll–session láncot kell megbízhatóvá tenni. Ezután az inspector és a tisztán szétválasztott live/adat/recipe mentés hozza a legnagyobb napi használati javulást. A gyorsítás ezekre épüljön, ne elfedje a hibáikat.
