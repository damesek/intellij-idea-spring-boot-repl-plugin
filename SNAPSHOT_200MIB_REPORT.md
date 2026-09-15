# 200 MiB snapshot – 0.9.1

## Elkészült

- A DATA snapshot felső határa **209 715 200 bájt (200 MiB)**, a teljes UTF-8 JSON-fejléccel együtt. A forrásobjektum mérete nem azonos a JSON méretével.
- A serializer közvetlenül egy korlátos, privát ideiglenes fájlba ír. A sikeres írás atomikusan cseréli a régi fájlt; mérettúllépés vagy serializerhiba megőrzi a korábbi snapshotot és eltakarítja az ideiglenes fájlt. A felhasználói serializer nem fogja a contextlezárás közös lockját.
- A List/Info csak a fejlécet olvassa, és az Info megjeleníti a fájlméretet. A fejléc sorrendje az alkalmazás map-sorrendezési beállításától független.
- Az **Import file / Export file** műveletek helyi fájlútvonallal működnek; a fájlt az alkalmazás JVM-je olvassa/írja. Az IDEA-nak és az alkalmazásnak ugyanazt a fájlrendszert kell elérnie. A nagy JSON nem kerül a REPL üzenetébe.
- A **Paste JSON** és az inline protokoll import/export korlátja 2 MiB. Túllépéskor lezárt hibaválasz irányít a fájlos művelethez; a kapcsolat használható marad. A protokoll keretmérete továbbra is 4 MiB.
- Importkor a tárolt Java-típus nem válik példányosítási utasítássá. Hibás vagy több egymás utáni JSON-érték, túl nagy fájl és symlink visszautasításra kerül.
- A Jackson 2.15+ szöveghosszkorlátját csak a snapshothoz másolt mapperben növeli a runtime. Az alkalmazás saját mapperének beállításai változatlanok.
- Hosszú REPL String eredménynél csak a korlátos előnézet formázódik; a változó és eredményhandle a teljes eredeti értéket őrzi.

Az alkalmazásból hívott `SnapshotHelper.save(...)` is ezt a 200 MiB-os tárolót használja, szinkron módon. A betöltés és import továbbra is felépíti az adatot az alkalmazás memóriájában; ehhez a JSON méretének többszöröse is szükséges lehet. A snapshot nem teljes JVM-checkpoint és nem rögzíti atomikusan a párhuzamosan módosított teljes objektumgráfot.

## Lefuttatott ellenőrzések

| Ellenőrzés | Eredmény |
|---|---|
| Runtime/protokoll/bridge Java-források | Sikeres közvetlen fordítás, `javac --release 17`, Temurin 21.0.8. |
| Plugin összes Kotlin-forrása | Sikeres fordítás IC 2024.1.4 SDK-val; öt meglévő deprecated API warning. |
| Runtime, protokoll, session, snapshot, bridge tesztek | **46 sikeres**. |
| Tényleges 200 MiB-os határteszt, külön JVM-ekben | **1 sikeres**. |
| Plugin, kliens, HTTP és checkbox regressziók | **14 sikeres**, headless Swing-komponensteszt. |
| A becsomagolt 0.9.1 agent tesztjei | **3 sikeres**, 1 TCP-teszt a socket-tiltás miatt nem futott. |
| Külön platform/Java/plugin classloaderek | IC 2024.1.4 és IU 2025.1.7: sikeres a ZIP-ből kibontott JAR-okkal. |
| ZIP, descriptor, bytecode, beépített agent | Sikeres; Java 17 bytecode, 0.9.1 verzió, Java-plugin függőség, SDK-osztályok nélkül, egyező beépített agent. |

Összesen **64 sikeres teszt**, **1 nem futtatott TCP-teszt**. Ezen felül két külön IDEA-classloader próba és csomagellenőrzések futottak.

A határteszt 96 MiB heap mellett közel 200 MiB adatot szerializált ismétlődő, közösen hivatkozott szövegdarabokból. A létrejött JSON-t legális whitespace-szel pontosan 200 MiB-ra egészítette ki, majd listázta, ellenőrizte a méretét és bájtpontosan exportálta. A 200 MiB fölé kerülő új mentés hibára futott, az előző fájl változatlan maradt. Külön, 1 GiB heapű JVM a pontosan 200 MiB-os fájlt visszatöltötte, importálta, majd az importot is visszatöltötte és minden szövegdarabot ellenőrzött. Egy további teszt a korábbi Jackson stringlimitet meghaladó Unicode-szöveg mentését, betöltését és importját ellenőrzi.

Logok: `build/repair/large-snapshot/runtime-tests.log`, `large-boundary-tests.log`, `plugin-compile.log`, `plugin-tests.log`, `agent-tests.log`, `classloader-2024.1.log`, `classloader-2025.1.log`.

## Csomag és a helyi ellenőrzés határa

- [0.9.1 helyi plugin ZIP](build/distributions/Spring-Boot-REPL-0.9.1-local.zip)
- [Beépített agent külön JAR-ként](build/distributions/dev-runtime-agent-0.9.1.jar)

A csomag közvetlen javac/Kotlin-fordítás eredménye; **teljes Gradle-build és Plugin Verifier sikerét nem igazolja**. A korábbi környezeti akadályok a [javítási jelentésben](REPAIR_REPORT_2026-09-13.md) szerepelnek: a wrapper-lock írása, majd írható Gradle home mellett a daemon socketnyitása volt tiltott. A jelenlegi tesztek a cache-ben elérhető függőségekkel futottak, az agentpróbák Spring Boot 3.2.0 / Spring 6.1.1 és a csomagolt indításhoz Boot loader 2.7.18 mellett. A CI-be beállított Boot 3.5.6/JDK 17–21/Verifier mátrixot itt nem futtattam.

Az új fájlválasztó gombokat élő IDEA-felületen nem teszteltem; a Kotlin-fordítás és a mögöttes protokollműveletek tesztje sikeres. Telepítés után IDEA- és alkalmazás-újraindítás szükséges, hogy az új beépített agent fusson. A normál teljes ellenőrzés: `./gradlew check buildPlugin verifyPlugin`.

## Javasolt következő REPL-finomítások

Ezek javaslatok, a 0.9.1-es változás nem implementálja őket:

1. **Cellák (`// %%`)**: Ctrl/Cmd+Enter az aktuális cellát futtassa, Shift+Enter lépjen tovább. A teljes workbook futtatása maradjon külön művelet, mert most kijelölés nélkül minden kód lefut.
2. **Kódkiegészítés a szerkesztőben**: a működő JShell session-completion válaszai natív popupban jelenjenek meg, késleltetett/asynchronous lekéréssel; a `ctx` és a visszatöltött változók tényleges típusa alapján. Kiindulás: [IntelliJ completion API](https://plugins.jetbrains.com/docs/intellij/code-completion.html).
3. **Egyszeri snapshot-trigger**: esetszűrő, „következő egy hívás mentése”, majd automatikus kikapcsolás és név/méret/időtartam visszajelzés. Így a fejlesztői flaget nem kell kézzel állítgatni minden próbánál. A szinkron capture és az esetleges háttérmunka határát meg kell őrizni, hogy a közben mutálódó objektum ne más állapotban kerüljön mentésre.
4. **DTO-vizsgáló és diff**: rekordkomponensek/mezők áttekintése, két DATA snapshot összehasonlítása, kiválasztott érték változóhoz kötése; getterhívás csak explicit műveletként.
