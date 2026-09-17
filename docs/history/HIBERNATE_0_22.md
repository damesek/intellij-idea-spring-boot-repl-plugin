# Hibernate-integráció - 0.22.0

A Recorded calls nézet a Java-hívásokat, Hibernate-műveleteket és tényleges JDBC-végrehajtásokat közös felvételben mutatja. A változás a 0.21 SQL-megfigyelésére épül; a támogatott adapter Hibernate 6.6, a tesztelt verzió 6.6.29.Final.

## Használat

1. Telepítsd a 0.22.0 csomagot (`build/distributions/sb-repl-0.22.0.zip`). Indítsd újra az IDE-t és a célalkalmazást, hogy az új agent fusson.
2. **Tap / Trace > Recorded calls > New recording...**: a JDBC mellett hagyd bekapcsolva a **Record Hibernate 6.6 entity / session events** opciót.
3. Hajtsd végre a vizsgált kérést, állítsd meg a felvételt, és válaszd ki a **Root** mezőben.
4. Kattints egy ORM-node-ra vagy nyisd meg a **Hibernate** részletezőt. A kereső entity/kapcsolat/forrás szerint szűr; a **Findings** csak a figyelmeztetéshez tartozó eseményeket mutatja.
5. **Open originating call**: rögzített Java-hívó és forrás. **Open entity mapping**: aktuális entity-forrás. **Pin Hibernate baseline**: összehasonlítás a következő felvétellel.

## Elkészült funkciók

- Entity load/insert/update/delete, proxy- és collection-inicializálás, enhanced lazy attribútum betöltése.
- Módosult property-nevek, flush, tényleges auto-flush, dirty checking, tranzakció begin/commit/rollback/completion.
- L2- és query-cache hit/miss/put események. A cache konfigurációját és az alkalmazás StatementInspector/listener beállításait nem cseréli le. Nem használ globális Statistics-különbségeket kérésenkénti mérésként.
- Session/root/szál, Java- és ORM-szülő, forráshely és kapcsolódó SQL-azonosítók. Nincs entity-ID vagy property-érték az ORM-eseményekben.
- SELECT-ekkel összekötött lazy N+1-gyanú, valamint szinkron MVC-válaszkezelés/renderelés közbeni lazy betöltés jelzése. Cache-only lazy inicializálás önmagában nem N+1-jelzés.
- ORM-node-ok a gráfban és timeline-ban; külön kereshető, görgethető részletező, referencia és offline mentés. A felvétel 3-as verziója az 1-es és 2-es formátumot is beolvassa.
- Inspector-metaadatok proxykról, collectionökről és megfigyelt entity-állapotról. Betöltetlen objektumot nem inicializál; enhanced mezőnél az unfetched állapotot külön mutatja.
- Négy CASE-korlát: entity-load, flush, lazy-inicializálás, response-lazy. A mérés a Code és Result expression részekre korlátozódik. Hiányos adatból nem lesz PASSED felsőkorlát.
- JUnit-exportban is megmaradnak az ORM-assertionök. A ZIP mellékeli a matching agentet és használati leírást; az ORM-teszthez a teszt JVM-jében `-javaagent` kell. A csak SQL-t mérő export továbbra is agent nélkül fut.
- Három új MCP-eszköz; összesen 66 eszköz, ebből 17 a felvételekhez. A Hibernate-olvasás és összehasonlítás nem futtat alkalmazáskódot, és megtartja a recording-sharing, allowlist, maszkolás, kvóta és audit szabályait.

| Új MCP-eszköz | Tartalom |
| --- | --- |
| `repl_recording_hibernate` | Lapozott események, metaadatok, ORM-számlálók és SQL-hivatkozások |
| `repl_recording_hibernate_findings` | Lazy/SELECT N+1-gyanú és válaszkezelési lazy események |
| `repl_recording_hibernate_compare` | Két híváság vagy saját kliensreferencia ORM-statisztikája, eltérése, részlegessége |

A `repl_recording_start` új `hibernate` opciója alapból a SQL-kapcsolót követi. A `repl_recording_pin` az ORM-ágat is megőrzi; az új view-változat megakadályozza az eltérő állapotú lapok keverését.

## Ellenőrzés

| Ellenőrzés | Eredmény |
| --- | --- |
| Gradle `:buildPlugin`, JDK 17 | 312 sikeres teszt: 126 plugin, 14 JShell-integráció, 172 runtime; nincs hiba vagy kihagyás |
| Gradle `:buildPlugin`, JDK 21 | Ugyanaz a 312 sikeres teszt; nincs hiba vagy kihagyás |
| Plugin Verifier, IC és IU 2025.2 (`252.23892.409`) | Mindkettő Compatible; 19 meglévő deprecated és 2 scheduled-removal figyelmeztetés kiadásonként |
| Maven agent és bridge `package -Dgpg.skip=true` | Sikeres, a Gradle- és Maven-agent tartalma külön ellenőrizve |
| PDF-kézikönyvek | Magyar és angol kiadás, egyenként 60 oldal, 42 fejezet és 66 MCP-eszköz; renderelt és vizuálisan ellenőrzött |
| IDE-panel | 700 és 1280 pixel szélességen renderelt és ellenőrzött; gráf-node kijelölése, forrásnavigáció és baseline-megőrzés tesztelve |

A csomagolt agentet külön JVM-ben futtató teszt valódi Spring MVC-t, Hibernate 6.6.29.Finalt és H2-t használ. Az 1+5 SELECT-es lazy példa 10 entity-loadot és 5 lazy inicializálást ad, `Purchase.customer` kapcsolattal. Fetch join után azonos eredményhez egy SELECT tartozik. Vizsgálja a collection betöltését, L2/query cache hideg-meleg állapotát, a nulla SQL-es query-cache találatot, flush/dirty/update/insert/delete/commit/rollback eseményeket, lazy kivételt, a párhuzamos HTTP-kérések izolációját és a válaszírás közbeni lazy betöltést.

Az Inspector-próba attached és detached proxy/collection mellett is ellenőrzi, hogy a megtekintés nem futtat plusz SQL-t. A Hibernate saját enhancerével módosított entity lazy LOB-mezőjét először unfetchedként jeleníti meg; a kifejezett olvasás egy LAZY_ATTRIBUTE eseményt hoz létre. A túlcsordulási próba eléri az ORM-napló 1000 eseményes korlátját. A nem támogatott StatelessSession használata az érintett felvételt hiányosnak jelöli; nem ront el egy későbbi stateful felvételt.

A CASE-próba külön ellenőrzi a sikeres és túllépett lazy-korlátot. A generált JUnit-forrást lefordítja, majd valódi Spring/JUnit környezetben a matching agenttel futtatja: a helyes korlát sikeres, a szigorúbb korlát assertionnel megbukik. Agent nélkül a Hibernate-mérés a kód előtt hibát ad. A JDBC-observer Mockito/Byte Buddy belső osztályainak betöltése miatt nem teszi hibássá a mérést.

Az MCP-tesztek ellenőrzik a lapozást, root szerinti szűrést, ORM-SQL kapcsolatokat, view-érvényesítést, külön kliensreferenciát, részleges összehasonlítást és a régi felvételformátumokat. A csomagellenőrzés a Hibernate-adapter osztályait és a hordozható JUnit-helper forrásokat is megköveteli; Hibernate-könyvtárat nem csomagolunk az agentbe.

Ezek elkülönített tesztalkalmazások és IDE-fixture-ök. A felhasználó üzleti alkalmazását és külső Claude-kliensét nem indítottuk el. Az IDEA 2025.2 utáni verziói és más Hibernate-főverziók nincsenek igazoltan lefedve.

## Határok

Szinkron Hibernate Session / Spring MVC / JDBC útvonalak figyelhetők meg. Async/reactive terjedés, StatelessSession-események teljes feltárása, R2DBC, L1-cache hit/miss, DB execution plan és lockprofil nem része ennek a kiadásnak. A forrás és a kapcsolat neve ismeretlen marad, ha nem azonosítható egyértelműen. A késői attach előtti események nem állíthatók vissza.

Az ORM-napló kerete 1000 esemény / 1,5 millió kódolt karakter / 64 ORM-szülőszint; külön a Java- és SQL-korláttól. Az entity-, lazy- és SQL-darabszám külön fogalom. Az egymásba ágyazott ORM/JDBC-időket nem szabad összeadni. Részleges vagy folyamatban lévő mérés nem bizonyít felső korlátot vagy megszűnt N+1-et.

## Dokumentáció és buildbizonyíték

- [Magyar kézikönyv](../../output/pdf/spring-boot-repl-guide-hu.pdf) és [English manual](../../output/pdf/spring-boot-repl-guide-en.pdf), 42. fejezet
- [Claude-kapcsolódás](../claude-repl-guide-hu.md) és [agent-instrukciók](../claude-repl-instructions.md)
- [Protokoll](../../PROTOCOL.md)
- Helyi ellenőrzési adatok (`build/hibernate-0.22/validation.json`)
- ZIP SHA-256 (`build/distributions/sb-repl-0.22.0.zip.sha256`)

A 2026-09-16-i dokumentációfrissítés mindkét PDF-et a repóba és a pluginba csomagolja. **Help (PDF) → English / Magyar** nyitja meg őket; a munkafüzet Help gombja nyelvválasztót kínál. A `verifyBundledHelp` buildlépés ellenőrzi a források naprakészségét és a repó-/pluginpéldányok egyezését. A frissítés build-, PDF- és csomagellenőrzése: `build/bilingual-manuals/validation.json`; a korábbi Hibernate-jelentés az ORM-funkciók tesztbizonyítéka marad.
