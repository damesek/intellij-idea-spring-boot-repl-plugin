# Kibővített magyar PDF-kézikönyv, 0.13.1

A korábbi rövid súgót 30 oldalas, 27 fejezetes kézikönyv váltja fel. A 11 fő fül és az alfülek leírása a jelenleg bekötött felület forráskódjára épül. Az eredeti gombfeliratok mellett szerepel a művelet célja, előfeltétele, eredménye és a használatát befolyásoló korlátja.

- A Java REPL, Variables, Snapshots, Inspector, Tap / Trace, Cases / Reload, Debugger, HTTP, AI, Imports és MCP fülek gombjai és mezői.
- Indítás, Spring profilok, cellák, előzetes kódellenőrzés, JSON-nézet, gyorsbillentyűk és menük.
- DATA/LIVE/RECIPE/CASE snapshotok, Capture next, összehasonlítás, debugger és kézi HotSwap, végigvezetett munkamenettel.
- Claude Code és Claude Desktop bekötése, másik gépen használat és mind a 34 MCP-eszköz referenciája.
- Szemléltető felülettérkép, kattintható tartalomjegyzék, 29 könyvjelző és az oldalakról visszaugrás a tartalomjegyzékhez. Az ábra a kódban található elrendezést szemlélteti; nem IDEA-képernyőkép.

A dokumentáció ismert felületi korlátokat is jelez, például a Variables lapozógombjainak jelenlegi Inspector-megnyitását. Ez a dokumentációs frissítés nem változtatja meg a plugin futási viselkedését.

## Fájlok és megnyitás

- [Olvasható PDF](../../output/pdf/spring-boot-repl-guide-hu.pdf)
- [Szerkeszthető forrás](../repl-help-hu.md)
- [PDF-generátor](../../scripts/build-help-pdf.py)
- Telepíthető plugin (`build/distributions/sb-repl-0.13.1.zip`)

A pluginba csomagolt PDF is frissült. Telepítés után a **Code → Spring Boot REPL → Help (PDF)** menü vagy a Java REPL fül **Help (PDF)** gombja nyitja meg. A menü regisztrációja változatlan.

## Ellenőrzés, 2026-09-14

Mind a 30 oldal Popplerrel renderelve és vizuálisan ellenőrizve. A magyar ékezetek megmaradtak; nincs kilógó törzsszöveg vagy feloldatlan Markdown-jelölés. A 82 belső PDF-hivatkozás létező céloldalra mutat, a tartalomjegyzék és a könyvjelzők oldalszámai egyeznek.

A forráskód gombfeliratai és MCP-eszköznevei összevetve a leírással. Az önálló PDF, a forrás-erőforrás és a telepítő ZIP plugin JAR-jában lévő PDF bájtpontosan egyezik. A forrás SHA-256 értéke is ellenőrzött.

```sh
./gradlew :test --tests hu.baader.repl.help.ReplHelpTest -x :jshellIntegrationTest :buildPlugin --console=plain
```

**BUILD SUCCESSFUL; 3 súgóteszt sikeres, 0 hiba, 0 kihagyás.** A teljes integrációs tesztkört és a Plugin Verifiert ez a dokumentációs frissítés nem futtatta újra. Élő IDEA-kattintásos próbát és tényleges Claude-kapcsolódást ezen a körön nem végeztem. A korábbi teljes kompatibilitásvizsgálat külön jelentése: [IDEA 2025.2](IDEA_2025_2_0_13_1.md).

Gépi ellenőrzési eredmény: `build/help-pdf-ui/pdf-qa.json`; oldaltérkép: `build/help-pdf-ui/layout.json`; tesztjelentés: `build/test-results/test/TEST-hu.baader.repl.help.ReplHelpTest.xml`.

Az új PDF-et tartalmazó ZIP SHA-256 értéke:

```text
ce2ca17675d1d9e062744ddf6b8fdc4365fe2ee0c6950004235a43bab5c7877e
```

Az ellenőrzőösszeg a `build/distributions/sb-repl-0.13.1.zip.sha256` fájlban is frissült.
