# Spring Boot REPL 0.18.0: rögzített hívások a forrás mellett

A futó alkalmazás kijelölt Java-osztályainak metódushívásai most kattintható hívásfába kerülnek. A node megnyitja a metódus forrását a belépéskor rögzített bemenettel és a kilépéskor rögzített eredménnyel vagy kivétellel. Az adatnézet kibontása a mentett értékeket olvassa.

## Használat

1. Telepítsd a `build/distributions/sb-repl-0.18.0.zip` fájlt: Settings → Plugins → Install Plugin from Disk. Indítsd újra az IDEA-t és a célalkalmazást, hogy az új agent is betöltődjön.
2. A normál Spring Boot konfigurációban legyen bekapcsolva az **Enable Spring Boot REPL**. A rögzítéshez a **Run** mód is elegendő.
3. A Java-osztályban jobb kattintás → Spring Boot REPL → **Record Class Calls…**. Válaszd ki a megfigyelt osztályokat.
4. Használd az alkalmazást, majd nyisd meg a **Tap / Trace → Recorded calls** alfület.
5. Kattints egy node-ra. A forrásnál megjelenő **Input / result** a kibontható adatnézetet nyitja; a **Call graph** visszavisz a gráfhoz.
6. **Stop recording**, majd **Save recording…**: a `.sbrepl-recording` fájl az **Open recording…** gombbal offline is megnyitható.

A forrás mellett automatikusan megjelenhet a legutóbbi rögzített eredmény. Kézi node-választás kikapcsolja a **Follow latest** követést. A metódus túlterhelését a JVM-paraméterlista azonosítja; ismételt és rekurzív hívások külön azonosítót kapnak. A gráf gyökérhívásonként szűrhető, billentyűzettel is használható, keskeny panelen függőleges elrendezést kap.

## A felvétel jelentése

- A bemenet és az eredmény külön időpontban rögzített megjelenítési adat. Későbbi objektummódosítás nem írja át a felvételt. A rövid összefoglaló is ebből a rögzített adatból készül.
- A node a felvételhez mentett Java-forrást használja. Módosított vagy hiányzó munkapéldány esetén csak olvasható másolatot nyit. A szerkesztő aktuális tartalma megmarad. A forrás és a futó bájtkód egyezése nem ellenőrzött; HotSwap után új felvételt indíts.
- A gráf a kijelölt osztályok megfigyelt metódusait köti össze, ugyanazon a szálon. Más szál külön gyökér; nincs automatikus Reactor/CompletableFuture korreláció.
- A megnyitás nem indít üzleti kódot és nem állít vissza JVM-stackállapotot. Belső sor lokális változójához továbbra is a debuggeres **Snapshot point…** használható.
- Az **Inspect live** külön az esemény aktuális, időkorlátos objektumát nyitja. Ez változhatott a felvétel óta. A történeti adat megmarad akkor is, ha az élő referencia lejárt.

## Korlátok és tárolás

Sessionönként egy aktuális felvétel: legfeljebb 200 hívás, 32 MiB érték-előnézeti adat. Legfeljebb nyolc osztály választható, osztályonként 64 konkrét metódusnév, összesen 16 instrumentált osztály a JVM sessionjeiben. A meglévő metódusszintű trace-szabályok is beleszámítanak. A rögzítés 64 megfigyelt hívásszintig követi a beágyazást; a szülő-node-okat nem dobja ki a korlát elérésekor.

A szokásos értékelőnézeti limitek érvényesek: 500 node/érték, hat mélységszint, 50 gyermek/node, 131072 karakter szövegkeret. Az első 32 argumentum kerül rögzítésre. A böngésző a felvett adatot bontja ki; a teljes, típusos reprodukcióhoz DATA snapshotot használj. A DATA 200 MiB-os kerete különálló.

A mezők olvasása a hívó szálon történik, alkalmazásbeli getter és egyedi szerializáló meghívása nélkül. Ez többletmunkát jelent, és konkurens objektummódosításnál nem atomi. A mért idő nem CPU-profil. A korlát után letiltott további hívások nem kerülnek teljes körű számlálásra.

A felvételfájl maximum 64 MiB, UTF-8 JSON; a Java-forrás fájlonként legfeljebb egymillió, összesen négymillió karakter. Megnyitás előtt validálja a hívásfát, az értékfákat, a forrás ellenőrzőösszegét és a méretkorlátokat. A fájl alkalmazásadatot és forrást tartalmazhat, titkosítás nélkül. Csak a már letöltött értékek tarthatók meg kapcsolatvesztés után. A befejezetlen hívások mentéskor `INCOMPLETE` jelölést kapnak. A workspace-export nem tartalmazza ezt a külön fájlt.

## Megvalósítás és ellenőrzés

A közös `RecordedCall` formátumot a runtime `ExecutionHistory` tölti ki. A Byte Buddy advice a pontos túlterhelést, hívásazonosítót és szülőt rögzíti. A `trace/history`, `trace/call` és `trace/stop` kérések futó kiértékelés közben is elérhetők. A részletes [protokoll](../../PROTOCOL.md) leírja a `call-id` mezőt és a formátumot. A 49 MCP-eszköz száma változatlan; a felvétel kezelése a natív IDE-felületen érhető el.

Az automatikus tesztek tényleges csomagolt agenttel induló Spring Boot alkalmazást, futtatható Boot JAR-t, hálózati vezérlést, mutált bemeneteket, hívásfát, túlterheléseket, kivételeket, több szálat, rögzítési korlátokat és a rögzítés közbeni leállítást ellenőriznek. Natív IDEA-fixture ellenőrzi a node-kattintást, forrásnavigációt, az el nem mentett forrás megőrzését, az offline adatnézetet, a kapcsolatszakadást és a késői válaszok eldobását. A UI 700 és 1200 pixel szélességen is kirajzolva és átnézve.

Az aktuális kiadási ellenőrzés eredményeit a `build/live-trace-0.18` könyvtár tartalmazza. A végleges buildadatok és SHA-256 értékek a `validation.json` fájlban szerepelnek. A kézikönyv 49 oldalas, 39 fejezettel és 131 PDF-linkkel; minden oldala kirajzolva és átnézve. Az új funkció a 39. fejezetben található.

**Végleges eredmények, 2026. szeptember 15.:**

| Ellenőrzés | Eredmény |
| --- | --- |
| `./gradlew :check :buildPlugin` - Java 17 | Sikeres; 269 teszt: 92 plugin, 14 JShell-integráció, 163 runtime. Nulla hiba és kihagyás. |
| `./gradlew :check :buildPlugin :verifyPlugin -PtestJdk=21` | Sikeres; ugyanaz a 269 teszt, nulla hiba és kihagyás. |
| IDEA Community 2025.2, IC-252.23892.409 | Compatible. |
| IDEA Ultimate 2025.2, IU-252.23892.409 | Compatible. |
| ZIP-tartalom | 0.18.0 descriptor, új action/osztályok/protokoll, egyező agent és PDF ellenőrizve. |

A verifikátor 19 elavult API-használatot és két később eltávolítandó API-használatot is jelez; kompatibilitási hiba nincs. Az új fájlmentő ugyanolyan, régebbi IDEA-val is működő API-t használ, mint a meglévő exportok. A fordítás továbbra is a támogatott 2024.1.4 SDK-ra épül.

Telepítő: **8 414 340 bájt**. SHA-256:

```text
444a14b27bff57de35d3e0607b0d1e00fe59bb0151f1c86bb36cfa2ce475321d  sb-repl-0.18.0.zip
```

Az ellenőrzőösszeg a ZIP melletti `.sha256` fájlban is megtalálható, ellenőrzése sikeres.

A felhasználó üzleti alkalmazását és telepített IDEA-példányát ez a munkamenet nem indította újra. A csomagot külön kell telepíteni és az új agenttel indítani a célalkalmazást.

A munkafolyamat tervezéséhez a [CIDER tracing dokumentációja](https://docs.cider.mx/cider/2.0/debugging/tracing.html) és a [Cursive inline megjelenítésének leírása](https://cursive-ide.com/blog/cursive-1.14.0.html) adott mintát. Az itt megvalósított Java-felvétel határait a fenti leírás rögzíti.
