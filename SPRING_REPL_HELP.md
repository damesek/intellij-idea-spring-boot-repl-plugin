# Spring Boot REPL használat, 0.12.1

**Beépített PDF:** Code → Spring Boot REPL → **Help (PDF)**, a Java-szerkesztő helyi menüje, vagy a munkafüzet **Help (PDF)** gombja. A magyar útmutató kapcsolat nélkül is megnyílik; gyorsbillentyűket, példákat, snapshot/capture és debugger munkafolyamatot is tartalmaz. Forrása: [repl-help-hu.md](docs/repl-help-hu.md).

A részletes, aktuális útmutató a [README-ben](README.md) található.

A navigálható Inspector, Tap / Trace, snapshotból futtatható tesztesetek, kódfrissítés és debugger használata: [0.11.0 munkafolyamat és ellenőrzés](REPL_WORKFLOW_0_11.md).

Az eredmény és az Inspector **Value** lapja automatikus **Tree / Formatted / Raw** nézetet mutat, JSON-felismeréssel és behúzással. A fő REPL-munkafüzetben a **Live check** gépelés után 650 ms-mal, futtatás nélkül jelez szintaktikai és típushibákat; a **Check code** azonnali ellenőrzést kér. Részletek, korlátok és az aktuális csomag: [0.12.0 útmutató](REPL_WORKFLOW_0_12.md).

1. A meglévő **Spring Boot** futtatási konfigurációban kapcsold be az **Enable Spring Boot REPL** jelölőnégyzetet. A profilokat annak **Active profiles** mezőjében add meg.
2. Indítsd el az alkalmazást, majd a **Spring Boot REPL** ablakban várd meg a **READY** állapotot. A `ctx` az elindult alkalmazás Spring-contextje.
3. Írj rövid Java snippeteket a munkafüzetbe. A cellákat `// %%` sor választja el. **Ctrl+Enter / Cmd+Enter** a kijelölést vagy az aktuális cellát futtatja; **Shift+Enter** siker után a következő cellára lép. A teljes munkafüzetet a **Run all** futtatja. A változók, importok és metódusok megmaradnak a sessionben. **Ctrl+Space / Complete** a szerkesztőben mutat sessionből érkező kódkiegészítést; gépelés közben automatikusan is megjelenik.
4. Az **Insert bean** típusokat/neveket listáz; a bean lekérése csak a beszúrt kód futtatásakor történik. Az **Inspect result** a már előállított eredményt vizsgálja.
5. **Save workbook** csak ment. **Reset** eldobja a sessiont. Contextváltás után reset szükséges; korábbi kód nem fut újra automatikusan.
6. **Pin LIVE**: élő objektumhivatkozás az aktuális sessionben. **Freeze DATA**: tartós JSON egy DTO-ról. **Save RECIPE**: később kézzel futtatható kód. A DATA/RECIPE fájlok exportálhatók; a LIVE nem teljes JVM-mentés.

A DATA snapshot korlátja **200 MiB**, a JSON-fejléccel együtt. Nagy fájlhoz az **Import file / Export file** gombot használd; ehhez az IDEA-nak és az alkalmazásnak ugyanazt a helyi fájlrendszert kell elérnie. A **Paste JSON** továbbra is 2 MiB-ig használható. Az **Info** mutatja a fájlméretet. Betöltéskor az objektumok helyet foglalnak az alkalmazás memóriájában, ami a fájlméret többszöröse is lehet.

Alkalmazáskódban a 0.10.0 bridge `SnapshotHelper.capture("cv-input", requestId, inputDto)` hívása használható. A **Snapshots → Capture next** panelen add meg a pont nevét, a snapshot nevét és opcionálisan az esetazonosítót, majd **Arm once**. Az első megfelelő hívás szinkron módon ment, és kikapcsolja a triggert. A panel mutatja a méretet, időt és hibát. Élesítés nélkül a hívás nem ment; drága projekcióhoz `captureLazy` használható. A REPL-ben `SnapshotManager.load("cv-input", CvInputDto.class)` tölti vissza új objektumként.

Snapshothoz előbb futtasd le a kifejezést, majd annak változóját vagy eredményét mentsd. Jackson mix-innel érzékeny mezők kizárhatók a snapshotból az alkalmazás saját mapperének módosítása nélkül. Az elhagyott mezők visszatöltéskor sem állnak helyre.

A **Not connected** kapcsolati állapot; a **NoClassDefFoundError / ClassCastException** korábban pluginintegrációs hiba volt. A Spring saját konfigurációs hibáját (például üres boolean propertyt) továbbra is az alkalmazásban kell rendezni. A javításhoz nem szükséges Run Configurationöket vagy IDE-cache-t törölni.

A [javítási jelentés](REPAIR_REPORT_2026-09-13.md) felsorolja, mely ellenőrzések futottak le ténylegesen.

A 200 MiB-os módosítás részletes ellenőrzése: [snapshot-jelentés](SNAPSHOT_200MIB_REPORT.md).

Két DATA snapshotot Ctrl/Cmd-kattintással jelölj ki a **Saved** lapon, majd **Compare**. A mezőnkénti eltérések lapozhatók, a Before/After irány felcserélhető. A részletes új útmutató és ellenőrzések: [0.10.0 workflow](REPL_WORKFLOW_0_10.md).
