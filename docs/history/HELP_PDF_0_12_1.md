# Beépített PDF-súgó, 0.12.1

Ez a korábbi kiadás történeti jelentése. Az aktuális, 30 oldalas kézikönyv és ellenőrzése: [Kibővített magyar PDF-kézikönyv, 0.13.1](HELP_UI_0_13_1.md).

A magyar használati útmutató hét oldalon tartalmazza az indítást és profilkezelést, a kódban regisztrált gyorsbillentyűket, a cellákat, kiegészítést, Live check-et, objektum- és JSON-nézetet, snapshot/capture műveleteket, Inspectort, Tap/Trace-t, teszteseteket, HotSwapot, debuggert, HTTP/AI használatot és hibakeresést. Kattintható tartalommutató és PDF-könyvjelzők segítik a navigációt.

Elérhető a **Code → Spring Boot REPL → Help (PDF)** menüből, a Java-szerkesztő helyi menüjének ugyanilyen csoportjából és a fő REPL-munkafüzet **Help (PDF)** gombjáról. A súgó nem igényel REPL-kapcsolatot, internetet vagy befejezett indexelést. Saját billentyűt az IDEA **Settings / Preferences → Keymap** alatt lehet rendelni a Help (PDF) művelethez.

A PDF a plugin JAR-jába van csomagolva. Megnyitáskor az IDE system/cache könyvtárán belül, tartalmi SHA-256 alapján elnevezett fájlba kerül; a rendszer PDF-olvasója nyitja meg, szükség esetén böngészős megnyitással. A fájl kiírása és megnyitása háttérszálon történik. A változatlan példány újrahasználható, a sérült gyorsítótári fájl újra kinyerhető. Projektfájlt vagy Run Configurationt nem módosít.

## Telepítés

Spring-Boot-REPL-0.12.1-local.zip (`build/distributions/Spring-Boot-REPL-0.12.1-local.zip`): **Settings → Plugins → fogaskerék → Install Plugin from Disk**, majd IDEA-újraindítás. A PDF a csomag része; nem kell mellé kézzel bemásolni. A futó alkalmazást is indítsd újra a csomagolt agent frissítéséhez. A 0.12.0-s runtime-funkciók viselkedése ebben a kiadásban nem változott.

A közvetlenül olvasható PDF: `output/pdf/spring-boot-repl-guide-hu.pdf`. Szerkeszthető forrás: `docs/repl-help-hu.md`. Generálás: `python3 scripts/build-help-pdf.py`, ReportLab és a dokumentumhoz szükséges Arial/Courier New vagy DejaVu Sans/Mono TTF betűkkel (`--font-dir` is megadható). A script a kiadási verziót a Gradle-fájlból olvassa, ellenőrzi az ékezeteket és az oldaltöréseket, majd frissíti a beépített PDF-erőforrást. A szokásos plugin-buildhez nem kell Python vagy PDF-generálás: a kész PDF a források között szerepel.

## Ellenőrzés, 2026-09-14

- Teljes Java- és Kotlin-fordítás sikeres, Java 17 bájtkódcéllal. Négy korábbról megmaradt IDEA API deprecation warning van.
- **42 plugin-teszt sikeres Java 17 és Java 21 alatt is**, köztük három új próba: offline erőforrás-kinyerés és cache-újrahasználat, sérült cache helyreállítása más fájlok megőrzésével, valamint a meglévő menücsoportba regisztrált és indexelés alatt is használható action ellenőrzése.
- A kiadott agent **három csomagtesztje mindkét JDK-n sikeres**, beleértve a külön JVM-es Spring Boot indítást és a végrehajtható Boot JAR próbát. A valódi nREPL-socketes teszt a környezet sockettiltása miatt kihagyva. A változatlan runtime teljes regressziós körének korábbi eredménye a [0.12.0 jelentésben](REPL_WORKFLOW_0_12.md) található; azt ehhez a súgómódosításhoz nem futtattam újra.
- A kész plugin ZIP osztályai betöltődtek külön platform-, Java-plugin- és REPL-classloaderrel **IDEA Community 2024.1.4 / JDK 17**, illetve **IDEA Ultimate 2025.1.7 / JDK 21** mellett. A PDF az izolált plugin JAR-jából mindkét esetben bájtpontosan kinyerhető.
- A ZIP-ben ellenőrzött a PDF tartalma, az action regisztrációja és osztálya, a Java 17-es bájtkód és a beépített agent egyezése. Az agent csomagszerkezete is érvényes.
- A PDF mind a hét oldalát Popplerrel PNG-re rendereltem és vizuálisan ellenőriztem. A magyar ékezetek, a szöveg margón belüli elhelyezése, a hét könyvjelző és a hat belső céloldal ellenőrzése sikeres. A tartalmi hash a dokumentum aktuális forrásával és verziójával egyezik.

A csomag a `scripts/package-local.py` segítségével, közvetlen Java/Kotlin-fordításból készült. A teljes Gradle-build és Plugin Verifier ebben a korlátozott környezetben továbbra sem igazolt: a korábbi Gradle-próbát az írhatatlan wrapper-zárolófájl, az írható Gradle-home-os próbát a helyi socket tiltása akadályozta. A menü tényleges megkattintását és az operációs rendszer PDF-olvasójának elindítását nem teszteltem élő IDEA-ban.

Naplók: `build/help-pdf/*-compile.log`, `plugin-tests-*.log`, `agent-tests-*.log`, `classloader-*.log`, `pdf-qa.json`. A kiadott ZIP, agent és opcionális bridge mellett SHA-256 ellenőrzőösszeg található. A korábbi csomagok megmaradtak.
