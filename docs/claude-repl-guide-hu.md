# Spring Boot REPL használata Claude-dal

Ez az útmutató a **0.20.0** verzióhoz készült, 2026. szeptember 15-én. A plugin egy futó Spring Boot alkalmazásban értékel ki Java-kódot. Claude MCP-n keresztül ugyanennek az alkalmazásnak a beanjeivel, objektumaival és snapshotjaival dolgozhat.

**Gyors kezdés:** indítsd az alkalmazást bekapcsolt REPL-lel, nyomd meg a **Start MCP** gombot, add hozzá a kapcsolatot Claude Code-hoz, majd add át neki a [Claude munkautasítását](claude-repl-instructions.md).

## 1. Az alkalmazás és a REPL indítása

1. Telepítsd a `build/distributions/sb-repl-0.20.0.zip` csomagot az IDEA **Settings → Plugins → Install Plugin from Disk** menüjében. Frissítés után indítsd újra az IDE-t. A támogatott IDE-k és az ellenőrzések a [verifikációs jelentésben](../IDEA_2025_2_0_13_1.md) szerepelnek.
2. A szokásos **Spring Boot** Run Configurationben kapcsold be az **Enable Spring Boot REPL** opciót. A saját alkalmazásod main classát és beállításait használd.
3. A profilokat a **Spring Boot → Active profiles** mezőben add meg, például `dev,llm-openai`. Sima **Application** konfigurációnál a programargumentum legyen `--spring.profiles.active=dev,llm-openai`. Az önmagában beírt `dev,llm-openai` nem aktivál profilokat.
4. Indítsd el az alkalmazást, majd nyisd meg a **Spring Boot REPL** tool window-t. Várd meg a **READY** állapotot.
5. A Java REPL munkafüzetben futtasd ezt **Cmd+Enter / Ctrl+Enter** segítségével:

```java
ctx.getBeanDefinitionCount()
```

A `ctx` a futó alkalmazás Spring contextje. A checkboxos indításhoz az agentet a plugin biztosítja. Az alkalmazáskódba írt `SnapshotHelper` hívásokhoz külön bridge-függőség kell; ezt a 6. pont mutatja be.

### Használat közvetlenül az IDE-ben

| Művelet | Használat |
| --- | --- |
| Aktuális cella vagy kijelölés futtatása | **Cmd+Enter / Ctrl+Enter**; a kijelölés elsőbbséget élvez |
| Futtatás és következő cella | **Shift+Enter** |
| Cellák elválasztása | Önálló `// %% Cella neve` sor |
| Kiegészítés | **Ctrl+Space / Complete**, illetve gépelés közben |
| Futtatás nélküli ellenőrzés | **Live check**, illetve **Check code**; fordítási és típushibákat keres |
| Kifejezés kiértékelése a Java-forrásban | **Evaluate at Caret**, alapértelmezetten **Cmd+Shift+E** a macOS keymapben |
| Kijelölt forrás futtatása | **Run Selection**, regisztrált alapbillentyű **Ctrl+Shift+R** |
| Objektum és JSON megtekintése | **Value → Tree / Formatted / Raw**, részletesen **Inspector** |
| Kódfrissítés | **Reload Class**, regisztrált macOS billentyű **Cmd+Shift+R** |
| Beépített útmutató | **Code → Spring Boot REPL → Help (PDF)** |

A keymap felülírhatja a billentyűket; az IDEA **Settings → Keymap** alatt a művelet nevére keress. Az **Evaluate at Caret** a REPL-sessionben fut: egy metódus lokális változói ettől még nem lesznek elérhetők. Felfüggesztett stack frame vizsgálatához a **Debugger** lapot használd, vagy készíts snapshotot a szükséges értékről.

## 2. MCP bekapcsolása a pluginban

1. Nyisd meg a **Spring Boot REPL → MCP** lapot; a munkafüzet **MCP** gombja is ide vezet.
2. A **Port (0 = free)** mező maradhat `0`, vagy válassz egy szabad állandó portot.
3. A **Allow Java execution / state changes** legyen bekapcsolva, ha Claude kódot futtathat, objektumot böngészhet vagy snapshotot menthet. Alapból ki van kapcsolva. A snapshot/CASE-írás, törlés, CASE-futtatás és capture külön kapcsolót is igényel; a Choose allowed tools tovább szűkítheti a listát.
4. A **Allow HotSwap** csak akkor szükséges, ha Claude a futó JVM kódját is frissítheti. Alapból ki van kapcsolva.
5. Kattints a **Start MCP**, majd a **Copy client config** gombra.

A másolt JSON-ból az `url` és a `headers.Authorization` érték szükséges. Az alábbi `PORT` és `GENERATED_TOKEN` helyére ezeket helyettesítsd. A fejlécben pontosan egyszer szerepeljen a `Bearer ` előtag.

**Az MCP-port külön port:** nem a Spring webalkalmazás portja, és nem az nREPL-port. Mindig az **MCP URL** mezőt használd, a `/mcp` végződéssel.

Minden **Start MCP** új tokent generál; `0` portnál az URL is változhat. A jogosultságok az indításkor rögzülnek. Módosításuk menete: **Stop MCP → kapcsolók beállítása → Start MCP → Copy client config → kliens frissítése**.

## 3. Claude Code csatlakoztatása

Az alkalmazás projektkönyvtárában futtasd, a helykitöltők cseréje után:

```sh
claude mcp add --transport http --scope local spring-boot-repl \
  'http://127.0.0.1:PORT/mcp' \
  --header 'Authorization: Bearer GENERATED_TOKEN'
```

Indítsd Claude Code-ot ugyanebben a könyvtárban, és a **`/mcp`** paranccsal ellenőrizd a kapcsolatot. A `--scope local` a projekthez tartozó személyes beállítást hozza létre. A kapcsolati parancs szintaxisát a helyi **Claude Code 2.1.76** `--help` kimenetével is ellenőriztük. A HTTP-kapcsolatot és a hatóköröket a [Claude Code MCP dokumentációja](https://code.claude.com/docs/en/mcp) írja le.

Ha már létezik ez a bejegyzés, például egy új MCP-indítás után:

```sh
claude mcp remove --scope local spring-boot-repl
```

Ezután add hozzá újra az aktuális URL-lel és tokennel, majd indítsd újra a Claude Code-sessiont. Az alkalmazást ehhez nem kell újraindítani. A tokent kezeld helyi hitelesítési adatként; ne tedd repositoryba vagy beszélgetésbe.

### JSON-konfiguráció, ha fájlból szeretnéd kezelni

Claude Code-nál a HTTP-bejegyzéshez **`"type": "http"` szükséges**. A plugin **Copy client config** kimenete jelenleg ezt nem tartalmazza; a fenti CLI-parancs beállítja. Fájlos megoldáshoz a projekt `.mcp.json` fájljában ilyen bejegyzést használhatsz, a már létező szervereket megtartva:

```json
{
  "mcpServers": {
    "spring-boot-repl": {
      "type": "http",
      "url": "${SB_REPL_MCP_URL}",
      "headers": {
        "Authorization": "Bearer ${SB_REPL_MCP_TOKEN}"
      }
    }
  }
}
```

A `SB_REPL_MCP_URL` és `SB_REPL_MCP_TOKEN` környezeti változókat a Claude Code-ot indító terminálban állítsd be; a token változója csak a tokent tartalmazza. Ez a CLI-s beállítás alternatívája; ugyanazt a szervert egy helyen konfiguráld. A változók URL-ben és fejlécben történő feloldását a [hivatalos konfigurációs leírás](https://code.claude.com/docs/en/mcp#environment-variable-expansion-in-mcpjson) támogatja.

## 4. Claude Desktop, illetve böngészős Claude

**Claude Desktop helyi MCP-konfigurációjához** használható a külső `mcp-remote` adapter. Node.js/npm szükséges. A lent rögzített `0.14.2` csomag README-jében ellenőriztük a kapcsolókat; a teljes Desktop-kapcsolatot nem próbáltuk ki.

macOS alatt a fájl: `~/Library/Application Support/Claude/claude_desktop_config.json`. A meglévő `mcpServers` bejegyzéseket őrizd meg. Cseréld ki a `PORT` és `GENERATED_TOKEN` helykitöltőket:

```json
{
  "mcpServers": {
    "spring-boot-repl": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote@0.14.2",
        "http://127.0.0.1:PORT/mcp",
        "--allow-http",
        "--transport",
        "http-only",
        "--header",
        "Authorization:${SB_REPL_AUTH_HEADER}"
      ],
      "env": {
        "SB_REPL_AUTH_HEADER": "Bearer GENERATED_TOKEN"
      }
    }
  }
}
```

Indítsd újra Claude Desktopot. Ha nem találja az `npx` programot, a `command` mezőbe a terminálbeli `command -v npx` eredményét írd. Az adapter első indításkor letöltődik. A `http-only` a plugin HTTP-végpontját használja. Forrás: [mcp-remote: konfiguráció és kapcsolók](https://github.com/punkpeye/mcp-remote).

**A claude.ai / Cowork távoli „Custom connector” mezőjébe ez a localhost URL nem használható.** Az a kapcsolat az Anthropic felhőjéből indul, míg a plugin a saját gépeden, `127.0.0.1` címen figyel. A Desktop helyi MCP-konfigurációja ettől külön működik. Ehhez a pluginhoz az azonos gépen futó Claude Code vagy Desktop helyi MCP-kapcsolatát használd. [Claude: a távoli connectorok hálózati követelményei](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp).

Másik gépen telepítsd oda is a plugint, indítsd ott az alkalmazást és az MCP-t, majd az ott generált URL-t/tokent használd. Egy másik gép `127.0.0.1` címe nem ezt a gépet jelenti.

## 5. Mit adj át Claude-nak?

Add át a **[claude-repl-instructions.md](claude-repl-instructions.md)** fájlt. Claude Code-ban megkérheted, hogy olvassa el a helyi fájlt; Desktopban csatolhatod vagy bemásolhatod. A fájl önmagában is tartalmazza az eszközlistát és a munkamenet szabályait. Egy meglévő projekt-`CLAUDE.md` tartalmát ne cseréld le vele; az oda illesztett hivatkozással kérheted az útmutató elolvasását.

Első kérésnek ezt másolhatod be:

> Olvasd el a mellékelt Spring Boot REPL munkautasítást. A spring-boot-repl MCP-szerveren kérd le az állapotot, majd a beanek első 50 sorát. Ellenőrizd a `ctx.getBeanDefinitionCount()` kódot futtatás nélkül, és ha a context kész és az elemzés hibamentes, értékeld ki. Írd le a tényleges eredményt.

Claude a `repl_status → repl_list_beans → repl_analyze → repl_eval` eszközökkel végzi el ezt. A konkrét JSON-paraméterek a [munkautasításban](claude-repl-instructions.md) szerepelnek.

### Az IDE és Claude közötti adatátadás

Mindkét kliens ugyanazokat a Spring beaneket érheti el, de **saját Java-változókat és objektumhandle-öket kap**. Az IDE-ben létrehozott `inputDto` változót Claude nem éri el közvetlenül.

1. Az IDE-ben mentsd az értéket **Freeze DATA** segítségével, például `cv-input-42` néven.
2. Mondd Claude-nak: „Keresd meg a `cv-input-42` DATA snapshotot, töltsd be `input` néven, és vizsgáld meg.”
3. Claude a saját sessionjében `repl_snapshot_load` segítségével új objektumot hoz létre belőle.
4. Az eredményt Claude ismét DATA-ként mentheti, amit az IDE-ből visszatölthetsz.

| Tárolás | Mire való? | Mi történik újracsatlakozáskor? |
| --- | --- | --- |
| Session-változó / handle | Gyors kísérletezés és böngészés | Elvész |
| LIVE pin | Az eredeti, változó objektumreferencia megtartása az adott sessionben | Elvész; egyébként is legfeljebb 30 percig él |
| DATA snapshot | Rögzített adat átadása, reprodukció, későbbi visszatöltés | Megmarad az alkalmazás snapshot-tárában |
| RECIPE / CASE | Kód, illetve bemenettel és elvárt eredménnyel mentett eset | Tartós; betöltésük önmagában nem futtat kódot |

A DATA nem teljes JVM- vagy adatbázismentés. Betöltése új értéket készít; nem állítja vissza a Spring beaneket vagy egy korábbi tranzakciót. Az alkalmazásban elérhető DTO-típusokat használj, gyűjteménynél például `java.util.List<com.example.CvInputDto>` típust adj meg. A `com.example.*` neveket minden példában a saját osztályaidra kell cserélni.

## 6. Snapshot trigger az alkalmazáskódban

Így Claude egy valódi kérés bemenetéből tud kiindulni, kézi adatmásolás nélkül.

A plugin forráskönyvtárából telepítsd a jelenlegi bridge-et a helyi Maven-tárba:

```sh
mvn -f sb-repl-bridge/pom.xml install -Dgpg.skip=true
```

Majd az alkalmazásod fejlesztési konfigurációjához add hozzá; a forrásfordításkor is elérhető legyen, ne kizárólag `runtime` scope-ban:

```xml
<dependency>
    <groupId>hu.baader</groupId>
    <artifactId>sb-repl-bridge</artifactId>
    <version>0.20.0</version>
</dependency>
```

Ez a helyi build telepítése; nem feltételezünk hozzá Maven Centralon publikált `0.20.0` verziót. További részletek: [bridge](../sb-repl-bridge/README.md).

A feldolgozó metódusban, ahol az érték már rendelkezésre áll:

```java
import com.baader.sbrepl.bridge.SnapshotHelper;

// requestId: a kérés String azonosítója; inputDto: a mentendő DTO.
SnapshotHelper.capture("cv-input", requestId, inputDto);
```

Claude-nak ezt kérheted:

> Élesíts egy egyszeri capture-t a `cv-input` pontra, `cv-input-42` snapshotnévvel, `request-42` kérésazonosítóra. Utána én elküldöm ezt a kérést az alkalmazásnak. A sikeres mentés után töltsd vissza a snapshotot `input` néven, és vizsgáld meg.

A hozzá tartozó eszközhívás:

```json
{
  "tool": "repl_capture_arm",
  "arguments": {
    "point": "cv-input",
    "name": "cv-input-42",
    "case": "request-42",
    "ttl-ms": 300000
  }
}
```

A `case` itt a `capture` hívás második argumentumára illeszkedő **pontos kérésazonosító-szűrő**, nem mentett CASE neve. Elhagyva a következő, bármilyen azonosítójú találatot fogadja. Az élesítés önmagában nem indítja el az alkalmazás üzleti folyamatát.

Claude a `repl_capture_status` eredményében a `phase` mezőt figyeli: például `ARMED`, `CAPTURING`, `SAVED`, `FAILED`, `EXPIRED`. Egy JVM-ben 16 független szabály lehet aktív. A `repl_capture_list` adja a saját szabályokat, a státusz és visszavonás `rule-id` paraméterrel címezhető. Nincs saját szabálynál a más sessionben aktív szabályokra `OTHER_SESSION` utal. A futtatókörnyezet 1–1 800 000 ms lejáratot fogad, az alapérték 5 perc.

Költséges DTO-előállításnál:

```java
SnapshotHelper.captureLazy("cv-input", requestId, () -> projectToDto(input));
```

A `projectToDto` a saját leképező metódusod. A supplier csak az élesített, illeszkedő találatnál fut egyszer. A mentés a hívó alkalmazásszálon történik, ezért nagy adatmennyiségnél lassíthatja az adott kérést. Élesítés nélkül vagy agent hiányában a capture `false` értékkel tér vissza; a sima `SnapshotHelper.save(...)` ezzel szemben feltétel nélkül ment.

## 7. Példák mindennapi Claude-feladatokra

**Objektum megértése:**

> Töltsd be a `cv-input-42` snapshotot `input` néven. Mutasd meg a típusát és a feldolgozás szempontjából lényeges mezőket. Használd az objektumböngészőt és a lapozást; ne kérd le egyben a teljes dokumentumtartalmat.

**Reprodukció egy beanen:**

> A projekt forrásából keresd meg a megfelelő feldolgozó bean publikus interfészét és metódusát. A snapshotból visszatöltött bemenetre készíts rövid Java-kódot, ellenőrizd `repl_analyze` segítségével, majd futtasd a fejlesztői alkalmazásban. Mentsd az eredményt új DATA snapshotként, és foglald össze, mit tapasztaltál.

Forrásfájlokat ehhez Claude Code a saját fájleszközeivel olvashat, vagy te adhatod át a releváns kódot. A REPL MCP nem tartalmaz forrásfájl-keresőt.

**Javítás ellenőrzése:**

> A `cv-input-42` bemenet és a `cv-expected-42` elvárt DATA eredmény alapján ments egy CASE-t. Futtasd, javítsd a metódust a forrásban, majd engedélyezett HotSwap mellett töltsd újra az osztályt és futtasd ismét a CASE-t. Mindkét tényleges kimenetelt mutasd meg.

A `repl_reload` teljes Java-forrást vár a `code` paraméterben; nincs `file`, `path` vagy `className` argumentuma. A szokásos JVM a támogatott metódustörzs-változásokat tudja átvenni. Mezők, metódusok vagy osztályszerkezet módosítása újraindítást igényelhet. A fájl mentése nem kapcsol be automatikus HotSwapot. A CASE eredménye lehet `PASSED`, `FAILED`, `ERROR`, `CANCELLED` vagy `INCONCLUSIVE`; az utolsó nem sikeres teszt.

**Futás közbeni érték megfigyelése:**

> Iratkozz fel a `cv-input` címkéjű tap eseményekre. A következő kérés után listázd az eseményeket, és a kapott eseményazonosítóval vizsgáld meg a bemenetet. A végén állítsd le a feliratkozást.

Ehhez az alkalmazásban `SnapshotHelper.tap("cv-input", inputDto)` hívás kell. Az MCP eseményeket listáz; debuggerléptetést és új metódustrace telepítését az IDEA felületéről végezd.

## 8. Korlátok és hibaelhárítás

| Jelenség | Teendő / jelentés |
| --- | --- |
| Nem indítható az MCP | Előbb legyen élő REPL-kapcsolat a futó alkalmazáshoz. Nézd meg az alkalmazás indítási hibáját is. |
| `Connection refused`, nem kapcsolódik | Ellenőrizd a **Start MCP** állapotot és az aktuális **MCP URL**-t. |
| HTTP 401 | Másold újra az aktuális tokent; ellenőrizd a `Bearer ` előtagot és a felesleges sortörést. |
| `command` hiányzik / HTTP-szerver kimarad | Claude Code JSON-ban szerepeljen `"type": "http"`. |
| `ctx` nem elérhető, `context-ready` hamis | Várd meg az alkalmazás sikeres indulását; korán nyitott sessionben `repl_bind_spring`. Ez nem javítja meg az alkalmazás konfigurációját. |
| Spring context megváltozott vagy bezárult | `repl_status`, majd az új contexthez `repl_reset`; a korábbi Java-változók és LIVE pinjeik elvesznek. |
| IDE-változó nem található Claude-ban | Mentsd DATA-ként, majd töltsd be Claude saját sessionjébe. |
| Eszköz, például `repl_eval` vagy `repl_reload` hiányzik | Ellenőrizd a plugin indításkor rögzített jogosultságait, majd a kliens eszközlistáját. |
| Böngészőben `GET /mcp` → 405 | A plugin nem weboldal és nem SSE-végpont. MCP-kliens HTTP POST kérésekkel használja. |
| `Session busy` | Egy sessionben sorban végezd a hívásokat. Folyó futtatás mellett megszakítás kérhető. |
| Lejárt vagy megszakadt session | Kapcsolódj újra, kérj állapotot, majd DATA-ból állítsd vissza a szükséges értékeket. |
| Timeout egy üzleti művelet után | Az eredmény bizonytalan lehet. Vizsgáld meg az alkalmazás tényleges állapotát; ne ismételd meg automatikusan ugyanazt a műveletet. |
| Csonkolt objektum / JSON | Használj lapozást vagy szűkebb projekciót. Teljes nagy snapshothoz az IDE fájlexportját használd. |

A DATA snapshot alapkorlátja **200 MiB**, a szerializált metaadatokkal együtt. Az MCP-kérés felső határa **256 KiB**, a válaszé **512 KiB**; Java-forrás és inline JSON-import legfeljebb **100 000 karakter**. A 200 MiB-os adatot nem kell és nem is lehet egy MCP-válaszban átadni: a runtime-ban töltsd be, és csak a szükséges részeit vizsgáld. A nagy adat betöltésének memóriaigénye meghaladhatja a fájlméretet.

Legfeljebb 4 MCP-session lehet nyitva; 30 perc inaktivitás után lejárnak. **Stop MCP**, alkalmazáskapcsolat-váltás és projektbezárás lezárja az elérést. Az interrupt együttműködő megszakítás. ROLLBACK/READ_ONLY módban visszatéréskor a részt vevő szinkron DB-munka visszagördül; a HTTP, üzenetek, fájlok, memóriabeli beanváltozások, más kezelő és önálló tranzakciók hatásai megmaradhatnak.

## Az útmutató ellenőrzése

A működést és a paramétereket a [MCP-eszközök](../src/main/kotlin/hu/baader/repl/mcp/McpTools.kt), a [panel](../src/main/kotlin/hu/baader/repl/mcp/McpPanel.kt), a [router](../src/main/kotlin/hu/baader/repl/mcp/McpRouter.kt), a [capture runtime](../dev-runtime/src/main/java/com/baader/devrt/SnapshotTriggers.java) és a [bridge](../sb-repl-bridge/src/main/java/com/baader/sbrepl/bridge/SnapshotHelper.java) kódjához igazítottuk.

A 0.20-as eszközreferencia 60 eszközt tartalmaz. A kiadás tényleges build- és regressziós ellenőrzéseit a [kiadási jelentés](../MCP_RECORDINGS_0_20.md) rögzíti. A tesztek elkülönített Spring/H2-környezetben futnak; ez nem a felhasználó üzleti alkalmazásának vagy a külső Claude-kliensnek teljes körű tesztje.

## 0.14: javasolt kezdő beállítás

Az MCP fülön induláskor minden végrehajtási kategória tiltott. Elemzéshez hagyd így. Engedélyezett Java-feladathoz a fő kapcsoló mellett válaszd a szükséges kategóriákat és eszközöket, a **Java / CASE mode** értéke pedig legyen **ROLLBACK** vagy **READ_ONLY**. Adj pontos **Transaction manager** bean-nevet, ha több van. A natív munkafüzet Execution sora az MCP-től független.

A 30 s alap timeout, 1000 hívás/session és 65536 karakteres eredménykorlát a panelen állítható. Ezek nem teszik a Java-kódot sandboxoltá. Külső hívásokat, üzeneteket, memóriabeli állapotot és külön tranzakciókat a rollback nem von vissza. **Stop MCP → beállítás → Start MCP → Copy client config** után frissítsd a klienst.

Claude első hívásai között kérje le a `repl_execution_policy` értékét. A natív **Audit events** gomb vagy Claude `repl_audit_events` eszköze a saját runtime-auditot mutatja. A titokkitakarás heurisztikus; az üzleti DATA és a workbook export előtti átnézést igényel.

A **Java REPL → Create reproduction** az utolsó eredményt/hibát egy kiválasztott input DATA-val, CASE-szel, RECIPE-pel és környezeti metaadatokkal csomagolja. Claude ugyanezt a `repl_reproduction_create` eszközzel indíthatja. Export/import az IDE munkafüzetgombjaival történik; a csomag nem fut automatikusan. Részletes lépések a PDF 28–31. fejezetében és a [Claude-munkautasításban](claude-repl-instructions.md).

## CASE 2.0 és JUnit-export (0.15)

A **Cases / Reload** fül új **Assertions**, **Parameters**, **Setup / cleanup** és **Options** alfülei a meglévő munkafolyamatot bővítik. Az assertionök mezőszűrést, rendezetlen listákat, numerikus/időbeli toleranciát, regexet és tartalom/méret/feltétel-ellenőrzéseket támogatnak. A külön **Result expression** mező a Code utáni eredményt adja. A **Save case** ment, a **Run saved cases** a mentett változatot futtatja.

Az **Export JUnit ZIP** egy kijelölt CASE-ből önálló JUnit 5/AssertJ forrást és DATA JSON-erőforrásokat készít; nem futtatja a tesztet. Teljes inputtípus és külön result-expression szükséges. A ZIP az alkalmazás tesztkönyvtáraiba másolható, majd Maven/Gradle alatt futtatható agent nélkül. ROLLBACK az exportpárbeszédablak alapmódja; a tesztkörnyezetet és a DTO-hoz szükséges Jackson-beállításokat ellenőrizd.

Claude számára új eszközök: `repl_case_list`, `repl_case_run_batch`, `repl_case_result`, `repl_case_export_junit`. Paraméterezett CASE-nél minden sor külön sessionben és tranzakcióban fut. A batch közös határidőt használ; részletes eredményt futtatásismétlés nélkül lehet kérni. Az export eszköz csak fájlmanifesztet vagy egy fájl előnézetét adja; csonkolt/kitakart válaszból ne építs fixture-t. A [munkautasítás](claude-repl-instructions.md) teljes argumentumlistát és példát ad; a [kézikönyv](repl-help-hu.md#case-assertions) a gombokat és korlátokat részletezi.


## Notebook, workspace és verziózott snapshot (0.16)

A **Java REPL > Cells** lapon futási sorszámot, időpontot, időtartamot, cellakimenetet és modified/stale jelzést látsz. **Analyze dependencies**, **Run above**, **Run from here**, **Run affected**, **Restart + run all** a munkafüzet gombsorán. A függőségek konzervatívak; a sorozat hibánál vagy szerkesztésnél megáll. A korábbi Java-hatásokat a jelzések nem vonják vissza.

**Save workspace** `.sbrepl-workspace` fájlt készít: munkafüzet, cellabizonyíték, importok, DATA-eredetkötések, CASE/RECIPE/DATA, HTTP-kérések, Inspector-bookmarkok és opcionális transcript. **Open workspace** nem futtat kódot; a snapshotok új nevek alá kerülnek. **Workspace info**, **Restore DATA binding**, **Insert saved imports** segít a tudatos folytatásban. LIVE-objektumok, hitelesítés és Spring-profilok nem élednek újra; a korábbi cellabizonyíték stale.

A **Snapshots > Saved > Versions** gomb mutatja a történetet. **Provenance** környezetet és lenyomatot, **Load version** régi DATA-t, **Restore as latest** új aktuális verziót ad. A visszaállítás megtartja a történetet. Legfeljebb 100 verzió/név, explicit Delete az összeset törli.

Claude számára új a `repl_snapshot_versions`, `repl_snapshot_provenance`, `repl_snapshot_restore_version`, `repl_notebook_symbols`, `repl_workspace_export` és `repl_workspace_import`. Az utóbbi kettő a JVM helyi fájljain dolgozik; a teljes editorállapotot a natív Save workspace gyűjti össze. A részletes sémákat és korlátokat az [agentutasítás](claude-repl-instructions.md) tartalmazza.


A 0.17-es IDE-felületen a Java REPL műveletei a Run, Workspace, Session és Tools menüben találhatók. A Create reproduction a Tools menüben van. A forrásbeli Snapshot point Java-debuggerrel, a REPL-agentet használva készít DATA-t; Claude a kész snapshotot a meglévő MCP-eszközökkel olvashatja. A breakpoint létrehozása nem MCP-művelet. Az MCP saját sessiont és futtatási szabályt használ, az IDE módkapcsolója ezt nem módosítja.


A 0.18-as **Tap / Trace > Recorded calls** nézet osztályok tényleges hívásfáját és bemenet/eredmény-előnézeteit rögzíti. A node megnyitja a hozzá mentett forrást az értékekkel; a `.sbrepl-recording` offline is visszanézhető. A 0.20-as kiadás 11 recording MCP-eszközzel elérhetővé teszi az aktuális IDE-felvételt. Ez nem futtat újra kódot, és nem állít vissza élő objektumot. A részletes PDF 39. fejezete mutatja be.


## IDE-felvételek és gráf MCP-n (0.20)

Minden itt felsorolt eszközhöz szükséges az **MCP > Share IDE recordings with MCP** kapcsoló; alapból ki van kapcsolva. Olvasáshoz a Java-futtatást nem kell engedélyezni. A **Choose allowed tools** tovább szűkítheti az elérést, például a forrás megosztását. A pin csak a kliens saját rögzített előnézetét módosítja. A select az IDE-kijelölést is módosítja; ehhez a fő állapotmódosítási kapcsoló szükséges. Start/stop esetén a capture/trace kapcsoló is kell.

| Eszköz | Argumentum és cél |
| --- | --- |
| repl_recording_status | Nincs; az IDE aktuális felvételének azonosítója, view verziója, állapota és letöltött hívásszáma. |
| repl_recording_calls | **recording**, `view`, `query`, `errors-only`, `root`, `focus`, `thread`, `min-duration-ms`, `from-ms`, `to-ms`, `collapsed`, `offset`, `limit`; szűrt hívásfa és hívóút. |
| repl_recording_call | **recording**, **call**, `view`; pontos overload, breadcrumb, gyerekek, previous/next/nextError és forráselérhetőség. |
| repl_recording_values | **recording**, **call**, **part**, `view`, `path`, `offset`, `limit`, `text-offset`, `text-limit`; input/result/exception rögzített mezői. |
| repl_recording_source | **recording**, **call**, `view`, `offset`, `limit`; rögzített forrás lapozva, fájlnév, eredeti SHA-256 és metódussor. |
| repl_recording_timeline | **recording**, `view` és a calls szűrői, `offset`, `limit`; szálankénti időadatok. Nincs collapsed argumentum. |
| repl_recording_pin | **recording**, **call**, `view`; egy letöltött hívás rögzítése a kliens saját összehasonlítási referenciájaként. |
| repl_recording_compare | **recording**, **after**, valamint **egy**: `before` / `reference`; opcionális `view`, `offset`, `limit`. Mezőszintű eltérések és részlegesség. |
| repl_recording_select | **recording**, **call**; a node kijelölése az IDE-ben, forrás és rögzített értékek megnyitása. Állapotmódosítási engedély kell. |
| repl_recording_start | **expected**, **classes**; az aktuális felvétel azonosítója vagy none, 1-8 különböző pontos osztálynév új sorral elválasztva. Capture/trace és állapotmódosítási engedély is kell. |
| repl_recording_stop | **recording**; az adott megosztott felvétel leállítása. Capture/trace és állapotmódosítási engedély is kell. |

Az alábbi szabályok a felvételi eszközökre vonatkoznak:

- Először `repl_recording_status`. Ha `available=false`, még nincs megosztható felvétel. Indításhoz ekkor `expected="none"`; egyébként az aktuális `recording` azonosító szükséges. Aktív felvételt előbb állíts le. A start a runtime visszaigazolását várja meg; az értékek letöltése ezután folytatódhat.
- Az olvasási válasz egyetlen EDT-állapotból készül; `scope="ide-recording"`. A gráf és az IDE-ben megnyitott mentett felvétel közös a kliensekkel, a Java-session továbbra is különálló. A `call` azonosító nem használható `event`, `handle` vagy `var` helyett.
- A `recording` és a lapozáskor visszaadott `view` együtt őrzi az olvasott állapotot. Ha közben hívás fejeződik be vagy előnézet töltődik le, a régi view elutasítható; az olvasást új view-val, 0. oldaltól kezdd. Felvételcsere után a régi azonosítóval sem kijelölés, sem leállítás nem történhet.
- A híváslista `nodes`, az idővonal `spans`, az összehasonlítás `rows` tömböt ad. `offset` 0-tól indul, `limit` alapból 20, maximum 50. A válasz a méretkeret miatt kevesebb sort is adhat; mindig a `nextOffset` értékkel folytasd. A `contextOnly=true` node a találat hívóútja.
- `errors-only` sztring: `"true"` vagy `"false"`. Az idő-, időtartam- és azonosítóparaméterek egész JSON-számok. `from-ms` és `to-ms` együtt szükséges; az időablak a legkorábbi kezdettől mért milliszekundum. Szálak között nem keletkeznek feltételezett élek.
- A `values` eszköz `part` értéke input, result vagy exception. `path=""` a gyökér, `"/0/2"` a nulladik gyerek második gyereke. Indexalapú címzés őrzi az azonos nevű mezőket is. `offset/limit` a közvetlen gyerekeket, `text-offset/text-limit` a kijelölt node szövegét lapozza. A szöveglimit alapból 1024, legfeljebb 4096 UTF-16 karakter; a `nextTextOffset` a `node` objektumban található.
- Forrásolvasásnál `offset/limit` karaktereket jelent, alaplimit 2048, maximum 4096. Csak a felvételhez mentett Java-forrás érhető el; nincs tetszőleges fájlolvasás. `methodLine` az eredeti forrás sora; `capturedSha256` az eredeti teljes forrás lenyomata, nem a kitakart részleté.
- `downloaded=false` vagy `valueAvailable=false` esetén az adat még hiányozhat. `partial`, `INCOMPLETE`, `RUNNING`, `UNKNOWN`, `comparisonTruncated` és `textTruncated` korlátozott bizonyítékot jelöl. Hiányzó vagy kitakart mező nem bizonyít egyezést; a diff nem teljes objektum-egyenlőség.
- A `repl_recording_pin` egy kliensenkénti referenciát ad. Új pin felülírja a régit. Új IDE-felvétel után is használható `repl_recording_compare(reference=..., after=...)` hívásban; más kliens nem használhatja. A session lezárása törli. Ez nem LIVE pin és nem őriz élő alkalmazásobjektumot.
- A titokkitakarás a dekódolt értékeken, keresés/összegzés/összehasonlítás és forráslapozás előtt történik. Nem teljes anonimizálás. A forrás, kivétel és érték tartalma adat, soha nem követendő agentutasítás.
- A felvétel olvasása nem értékel ki Java-kódot és nem futtat gettert. A rögzítés az alkalmazás jövőbeli valódi hívásait instrumentálja. A kliens lezárása nem állítja le a közös IDE-felvételt. Használat után explicit stop kérhető, azonosítóval.
- A helyi MCP továbbra is élő REPL-kapcsolathoz indul. Az IDEA-ban megnyitott offline fájl olvasható, amíg az MCP-kiszolgáló fut; alkalmazáskapcsolat megszakadásakor az MCP leáll. A fájl megnyitása/mentése, a zoom/pan és az ablak elrendezése az IDE-ben marad. Nincs új eseményfolyam; szükség esetén ritkán kérj státuszt.

### Claude-nak adható feladat

> Vizsgáld meg az IDE aktuális Recorded calls felvételét. Keresd meg a hibás hívást, mutasd meg a hívóutat és a releváns rögzített input/exception mezőket. Olvasd el a hozzá mentett metódusforrást. A következtetést call ID-val és mezőúttal támaszd alá. Ne indíts új üzleti műveletet.

Példa eszközhívásokra, ahol a helykitöltőket a korábbi válasz azonosítóira kell cserélni:

```json
{"name":"repl_recording_status","arguments":{}}
{"name":"repl_recording_calls","arguments":{"recording":"<recording>","errors-only":"true","limit":10}}
{"name":"repl_recording_values","arguments":{"recording":"<recording>","call":3,"part":"input","path":"","limit":10}}
{"name":"repl_recording_source","arguments":{"recording":"<recording>","call":3,"offset":0,"limit":2048}}
{"name":"repl_recording_pin","arguments":{"recording":"<recording>","call":3}}
```

A példában a 3-as hívásszám is helykitöltő: a híváslistából válassz létező azonosítót. Javítás és új felvétel után a pin válaszának `reference` értékével hasonlítsd össze az új hívást. Ez forrásmódosítást, HotSwapot és üzleti újrafuttatást nem végez magától.
