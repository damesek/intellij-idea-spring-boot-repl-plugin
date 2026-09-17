# MCP-hozzáférés a hívásgráfhoz - 0.20.0

A kiadás 11 új MCP-eszközzel teszi elérhetővé az IDEA-ban látható Recorded calls felvételt. A katalógus összesen 60 eszközből áll. A Java-kiértékelés továbbra is kliensenként külön sessionben fut; a megosztott hívásfa a projekt RecordingController szolgáltatásának rögzített adatait használja.

## Bekapcsolás

1. Telepítsd a 0.20.0 ZIP-et (`build/distributions/sb-repl-0.20.0.zip`) az IDEA **Settings > Plugins > Install Plugin from Disk** menüjéből, majd indítsd újra az IDE-t.
2. Csatlakoztasd a REPL-t az alkalmazáshoz. Az **MCP** fülön, leállított kiszolgálónál kapcsold be a **Share IDE recordings with MCP** jelölőt.
3. Olvasáshoz nem kell Java-futtatást engedélyezni. Ha az agent indíthat/leállíthat felvételt, az **Allow Java execution / state changes** és az **Allow capture / trace changes** is szükséges. IDE-kijelöléshez a fő állapotmódosítási engedély kell.
4. **Start MCP > Copy client config**, majd frissítsd a kliens URL-jét/tokenjét és eszközlistáját. Minden MCP-indítás új tokent generál. A pontos Claude-beállítás a [kapcsolódási útmutatóban](../claude-repl-guide-hu.md) található.

A **Choose allowed tools** a recording eszközökre is érvényes. A megosztás alapból ki van kapcsolva. Egy már csatlakoztatott kliens külön engedély nélkül nem kap hozzáférést az IDE-felvételhez.

## Az új eszközök

| Eszköz | Feladat |
| --- | --- |
| `repl_recording_status` | Aktuális felvétel, azonosító, view verzió, állapot és letöltési készültség |
| `repl_recording_calls` | Lapozott hívásfa, keresés, szál/hiba/időtartam/időablak-szűrés, fókusz és ágak összecsukása |
| `repl_recording_call` | Pontos metódus/túlterhelés, hívóút és előző/következő/hibás hívás |
| `repl_recording_values` | Bemenet, eredmény vagy kivétel rögzített fája; gyerek- és szöveglapozás |
| `repl_recording_source` | A felvételhez mentett Java-forrás, fájlnév, SHA-256 és metódussor |
| `repl_recording_timeline` | Szálankénti időadatok és tényleges szülőkapcsolatok |
| `repl_recording_pin` | Egy letöltött hívás rögzítése a kliens saját összehasonlítási referenciájaként |
| `repl_recording_compare` | Rögzített bemenet/eredmény/kivétel/állapot mezőszintű összehasonlítása |
| `repl_recording_select` | Node kijelölése az IDE-ben, forrás és rögzített értékek megnyitása |
| `repl_recording_start` | Megadott 1-8 osztály jövőbeli tényleges hívásainak rögzítése |
| `repl_recording_stop` | A pontos azonosítóval megadott közös felvétel leállítása |

Az agent egy korábbi hívást pinelhet, majd kódjavítás és új IDE-felvétel után összehasonlíthatja az új hívással. A pin kliensenként különálló, nem LIVE referencia, és a session lezárásával megszűnik.

## Adatkezelés és korlátok

- Az olvasás nem értékel ki Java-kódot, nem futtat gettert és nem elevenít fel JVM-frame-et. A node-ból visszaadott call ID nem runtime event/handle.
- Az EDT-n egyetlen konzisztens állapotmásolat készül; a dekódolás, keresés és diff háttérszálon történik. Egyetlen forrás/mező olvasása nem dekódolja a többi hívás teljes adatait.
- Felvételazonosító védi az olvasást, kijelölést és leállítást. Start előtt expected=current-ID vagy none szükséges. Lapozáskor a view verzióval kérhető változatlan adat; letöltési/futási változáskor elutasítás jelzi az újrakezdendő olvasást.
- A start/stop a runtime válaszát megvárja. Klienslezárás után a még sorban álló IDE-művelet nem hajtódik végre. A már futó közös felvételt a kliens lezárása nem törli és nem állítja le.
- A ValueTree és RecordedCall Base64-adatfolyam helyett strukturált JSON érkezik. A titokkitakarás keresés, összefoglalás, diff és forráslapozás előtt történik; heurisztikus. A kitakart/hiányzó/részleges adat nem bizonyít egyenlőséget.
- Az értékfa indexalapú útvonalai az azonos nevű mezőket is megkülönböztetik. Sorlimit alapból 20, maximum 50; skalárszöveg és forrás maximum 4096 UTF-16 karakter oldalanként. A nextOffset/nextTextOffset jelzi a folytatást.
- A meglévő localhost-hitelesítés, engedélyek, eszközlista, audit, kvóta és válaszméret-korlát változatlanul érvényes.
- Az offline fájlt az IDEA-ban kell megnyitni. A helyi MCP indítása továbbra is élő REPL-kapcsolatot igényel; kapcsolatvesztéskor leáll. Nincs tetszőleges forrásfájl-olvasás, új fájlimport/export, zoom/pan vezérlés vagy eseményfolyam.
- A hívásfa továbbra is legfeljebb 200 hívás és 32 MiB rögzített előnézet. Szálak között nem talál ki aszinkron éleket.

## Ellenőrzés

- JDK 17: `./gradlew :check :buildPlugin` - 295 sikeres teszt: 118 plugin, 14 külön JShell-integráció, 163 runtime.
- JDK 21: `./gradlew :check :buildPlugin :verifyPlugin -PtestJdk=21` - ugyanaz a 295 sikeres teszt; nincs kihagyott teszt.
- IntelliJ 2025.2, IC és IU `252.23892.409`: **Compatible**. Mindkettő 19 deprecated és 2 scheduled-for-removal API-használatot jelez; ezek meglévő kompatibilitási figyelmeztetések.
- Az új regressziók a megosztás és módosítás tiltását, lapozást/szűrést, Unicode/JSON/azonos nevű mezőket, secret redactiont, régi view elutasítását, külön kliensreferenciát és méretkorlátot vizsgálják.
- Az IDEA-tesztek az igazi projektservice-en keresztül olvasnak/kijelölnek, a felvételindítás és -leállítás pedig a valódi NreplService/transport útvonalon kap visszaigazolást egy kontrollált tesztpeertől. A késői/lezárt kérés elleni védelem is tesztelt.
- A működőképes csomag nem jelent a felhasználó üzleti alkalmazásán végzett próbát. A telepített IDEA-példányt és a külső Claude-klienst ez a feladat nem módosította vagy indította újra.

A géppel ellenőrizhető eredmények és SHA-256 lenyomatok: validation.json (`build/mcp-recordings-0.20/validation.json`). Buildlogok, mindkét JDK XML-teszteredményei és a PDF renderelése ugyanebben a könyvtárban vannak. A ZIP ellenőrzőösszege (`build/distributions/sb-repl-0.20.0.zip.sha256`) külön is elérhető.

## Dokumentáció

- [Claude-kapcsolódás és példák](../claude-repl-guide-hu.md)
- [Önálló agentutasítás és a teljes 60 eszköz referenciája](../claude-repl-instructions.md)
- [54 oldalas beépített PDF-kézikönyv](../../output/pdf/spring-boot-repl-guide-hu.pdf), 40. fejezet: AI-hozzáférés a hívásgráfhoz
- [Protokoll és a helyi recording adapter](../../PROTOCOL.md#mcp-recording-access-020)
- [Korábbi gráffunkciók](CALL_BROWSER_0_19.md)

A PDF borítóján az eszközszám a regiszterből származik; minden új eszköz szerepel a kézikönyvben és az agentutasításban. A strukturált és szöveges MCP-eredmény egyezését teszt ellenőrzi, a [hivatalos MCP tools szerződésnek](https://modelcontextprotocol.io/specification/2025-11-25/server/tools) megfelelően.
