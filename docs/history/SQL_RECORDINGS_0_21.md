# SQL-megfigyelés és N+1-jelzések - 0.21.0

A Recorded calls felvétel a kiválasztott alkalmazásosztályok hívásait a tényleges JDBC-műveletekkel kapcsolja össze. Szinkron Spring MVC-kérésnél közös HTTP-gyökér alatt jelenik meg a Java-hívásfa és az SQL. A kérésenkénti ismétlődések N+1-gyanút jelezhetnek; a javítás eredménye új felvétellel, SQL-referenciával és CASE-korlátokkal ellenőrizhető.

## Használat

1. Telepítsd a 0.21.0 ZIP-et (`build/distributions/sb-repl-0.21.0.zip`) az IDEA **Settings > Plugins > Install Plugin from Disk** menüjéből. Indítsd újra az IDEA-t és a célalkalmazást is, hogy az új agent fusson. Szükség esetén töröld a régi egyedi agentútvonalat.
2. **Tap / Trace > Recorded calls > New recording...**: add meg a vizsgált controller/service/repository osztályokat. A **Record JDBC/SQL and synchronous Spring MVC requests** alapból be van kapcsolva.
3. A **Suspected N+1 repetition threshold** alapértéke 5, állítható 2-1000 között. Futtasd le a vizsgált alkalmazáskérést, majd **Stop recording**.
4. A **Root** mezőben válaszd ki a kérést. Nyisd meg az **SQL & N+1** fület vagy a gráf JDBC-node-jait. A csoportok kinyithatók; a részletek kereshetők és görgethetők.
5. **Pin SQL baseline**: rögzítsd a kérés SQL-adatait. Javítás, fordítás/HotSwap és új felvétel után hasonlítsd össze ugyanazt a kérést. A referencia az új felvétel megnyitásakor is megmarad.

## Mit tartalmaz?

- SQL-sablon, végrehajtásszám, JDBC-idő, datasource osztály/instance azonosító, szál, forráshely, szülőhívás és hibafajta. A kapcsolat megszerzésének ideje külön mérés.
- **Group SQL** a gráfban, egyedi JDBC-időszakaszok a Timeline nézetben, kereshető SQL-részletező és **Suspected N+1 only** szűrő. A hívóág érintett Java-node-jai is kapnak jelzést.
- N+1-gyanú az egy roothoz, datasource-hoz, normalizált SELECT-hez és hívási helyhez tartozó ismétlések alapján. Külön kérések nem adódnak össze.
- SQL-darabszám, összes JDBC-idő és legnagyobb ismétlődés összehasonlítása. A részleges bizonyíték külön jelzést kap.
- A felvételfájl 2-es verziója megőrzi az SQL-adatokat. A korábbi, SQL nélküli 1-es verzió továbbra is olvasható.
- A gyors stop/start során talált versenyhelyzet javítása: a régi felvétel háttérben futó takarítása nem telepíti újra szükségtelenül az új felvétel azonos metódusmegfigyelőit.

## CASE és hordozható JUnit

A **Cases / Reload > Options** új mezői: **Maximum SQL executions** és **Maximum SQL repetition**. Üresen nincs SQL-állítás; 0 is megadható, felső határ 1000000. A mérés a Code és Result expression részekre terjed ki, az inputbetöltés, Imports, Setup, Cleanup és JSON-összehasonlítás kívül marad.

Küszöbtúllépéskor FAILED az eredmény. Hiányos mérésből nem lehet sikeres felsőkorlát-ellenőrzés: INCONCLUSIVE vagy ERROR keletkezik. A hibás SQL-végrehajtás is beleszámít, egy batch-hívás egy végrehajtás; a kapcsolatfelvétel külön van.

A **JUnit ZIP** SQL-számlálót és normalizálót is exportál, így megőrzi a beállított korlátokat. A generált teszt agent nélkül fut, Spring által kezelt DataSource beaneken át. Közvetlen DriverManager, natív unwrap, aszinkron hozzáférés vagy konkrét pool-osztály injektálása esetén adaptálás kell; ezt az export README-je is jelzi.

## MCP

A katalógus **63 eszközből** áll, ebből 14 kapcsolódik a felvételekhez. A három új eszköz:

| Eszköz | Eredmény |
| --- | --- |
| `repl_recording_sql` | Lapozott SQL-események, statisztikák, root/híváság szerinti szűrés és SQL-szöveglapozás |
| `repl_recording_findings` | Kérésenkénti N+1-gyanús csoportok, darabszám, idő, forráshely és példaazonosítók |
| `repl_recording_sql_compare` | Korábbi hívás vagy kliensreferencia és új hívás SQL-statisztikáinak különbsége |

A `repl_recording_start` SQL-opciót és küszöböt fogad. A `repl_recording_pin` a hívás SQL-ágát is megőrzi; a normál `repl_recording_compare` SQL-statisztikát is visszaad. A lapozás view-azonosítója SQL-változásra is módosul.

A **Share IDE recordings with MCP** és az eszközönkénti engedélyezés szükséges. Az olvasás/összehasonlítás nem értékel ki Java-kódot. Indításhoz/leállításhoz a meglévő futtatási és capture-módosítási engedély is kell. A referenciák kliensenként különállók; részleges, kiesett vagy folyamatban lévő adatokból az agent nem állíthatja, hogy a hiba biztosan megszűnt.

## Megfigyelési határok

- **Szinkron Spring MVC + JDBC/JPA** támogatott. Az aktuális kiválasztott osztályt elérő HTTP-kérés kap gyökeret; a dispatcher szálán később lefutó SQL is hozzá kapcsolódik. A nem kiválasztott Java-metódusokhoz nem készülnek mesterséges node-ok.
- Async/Reactor/CompletableFuture, R2DBC és az adatbázison belüli tervek/lockok követése nem része ennek a kiadásnak. A JDBC-idő kliensoldali mérés, a megfigyelésnek is van futási költsége.
- Az N+1-jelzés ismétlődési heurisztika, nem bizonyíték hibás lekérdezésre. Szándékos ismétlés is kiválthatja.
- Külön SQL-keret: 1000 JDBC-esemény és 1,5 millió kódolt karakter. A Java-hívások 200-as és az értékelőnézetek 32 MiB korlátja megmarad. Hiányzó, kiesett vagy folyamatban lévő megfigyelés részlegesnek számít.
- Nincs paraméter- vagy ResultSet-értékgyűjtés. Az SQL string-/számliteráljai és megjegyzései kikerülnek a normalizált szövegből. Datasource URL/jelszó helyett osztály/instance azonosító szerepel; SQL-hibánál csak a kivételosztály. A felvétel más alkalmazásadatot tartalmazhat, nem általánosan anonimizált.
- A HTTP-gyökér metódust és útvonalat tartalmazhat, query stringet, headert és bodyt nem. Késői attach előtt létrehozott PreparedStatement SQL-je nem feltétlenül áll rendelkezésre.

## Ellenőrzés

| Ellenőrzés | Eredmény |
| --- | --- |
| `./gradlew :check :buildPlugin -PtestJdk=17` | 305 sikeres teszt: 122 plugin, 14 JShell-integráció, 169 runtime; 0 hiba/kihagyás |
| `./gradlew :check :buildPlugin -PtestJdk=21` | Ugyanaz a 305 sikeres teszt; 0 hiba/kihagyás |
| `./gradlew :buildPlugin :verifyPlugin -PtestJdk=21` | Végleges ZIP, IC és IU 2025.2 (`252.23892.409`): **Compatible** |
| Maven agent és bridge `package -Dgpg.skip=true` | Mindkettő sikeres; az agent csomagellenőrzése is sikeres |
| Beépített PDF | 57 oldal; az új 41. fejezet és a gombmagyarázatok renderelve, vizuálisan ellenőrizve |

A verifier mindkét IDE-n 19 deprecated és 2 scheduled-for-removal API-használatot jelez, ugyanannyit, mint 0.20-nál. Ezek meglévő figyelmeztetések, nem kompatibilitási hibák.

Az új integrációs teszt külön JVM-ben a csomagolt `-javaagent` fájlt használja. Valódi DispatcherServlet/MockMvc, Spring JDBC, DataSource-proxy és H2 mellett ellenőrzi a párhuzamos kérések szétválasztását, hibás SQL-t, batch-et, CASE-korlátokat, túlcsordulást és 12 gyors stop/start ciklust. A Hibernate-példa azonos eredménnyel **6 SELECT / 1 N+1-gyanús csoport** helyett fetch join után **1 SELECT / 0 gyanús csoport** eredményt ad.

A JUnit-export tesztje lefordítja és agent nélkül futtatja a generált Spring-tesztet, SQL-korlátokkal is. Az IDE-tesztek a csoportválasztást, forrásnavigációt, referencia megőrzését és keskeny/széles panelt ellenőrzik. Az MCP-tesztek lapozást, root szerinti statisztikát, változó view-t, külön kliensreferenciát és régi felvételfájlok olvasását vizsgálják.

Ezek elkülönített tesztalkalmazásokon végzett ellenőrzések. A felhasználó üzleti alkalmazását és külső Claude-kliensét nem indítottuk el. Az IDEA 2025.2 utáni verziói nem részei ennek a kompatibilitásvizsgálatnak.

A helyi build bizonyítékai: validation.json (`build/sql-recordings-0.21/validation.json`), JDK 17 log (`build/sql-release-jdk17.log`), JDK 21 log (`build/sql-release-jdk21.log`), IDE-verifier log (`build/sql-verify-2025.2.log`), ZIP SHA-256 (`build/distributions/sb-repl-0.21.0.zip.sha256`).

## Dokumentáció

- [57 oldalas PDF-kézikönyv](../../output/pdf/spring-boot-repl-guide-hu.pdf), 41. fejezet
- [Claude kapcsolódás és SQL-munkafolyamat](../claude-repl-guide-hu.md)
- [Önálló agentutasítás és a 63 eszköz referenciája](../claude-repl-instructions.md)
- [Protokoll](../../PROTOCOL.md)
- [Korábbi recording MCP-funkciók](MCP_RECORDINGS_0_20.md)
