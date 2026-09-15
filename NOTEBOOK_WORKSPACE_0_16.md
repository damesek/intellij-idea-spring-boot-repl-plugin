# 0.16.0 - Notebook, workspace és snapshot-történet

A három kért funkció elkészült a natív UI-ban és a hozzájuk tartozó runtime API-val. A meglévő `.jsh`, DATA, RECIPE és CASE mentések tovább használhatók.

- **Notebook:** saját cellaazonosító, futási sorszám, időpont, időtartam, kimenet, modified/stale jelzés és konzervatív függőségi lista a Java REPL > Cells lapon. Run above / Run from here / Run affected / Restart + run all; soros végrehajtás, hibánál vagy szerkesztésnél megáll. Helyi automatikus checkpoint, korábbi eredmények egyértelmű stale jelölésével.
- **Workspace:** `.sbrepl-workspace` export/import munkafüzettel, cellabizonyítékkal, importokkal, DATA-eredetkötésekkel, CASE/RECIPE/DATA-fájlokkal, HTTP-kérésekkel, Inspector-bookmarkokkal, környezeti metaadattal és választható transcripttel. SHA-256/ZIP-ellenőrzés és méretkorlát; import új neveket használ, a CASE input/expected és paraméterhivatkozásait átírja. Importkor sem Java-, sem HTTP-futtatás nincs. DATA materializálása és importok alkalmazása külön felhasználói művelet.
- **Snapshot-történet:** névenként legfeljebb 100 változat, tartalmi SHA-256, eredeti capture-környezet és visszaállítás/import származása. Történeti DATA betöltése az aktuális snapshot cseréje nélkül; Restore as latest új verziót hoz létre. Régi v1 mentés archiválása az első újramentéskor. Explicit Delete a történetet is törli.

MCP: 49 eszköz; új a `repl_snapshot_versions`, `repl_snapshot_provenance`, `repl_snapshot_restore_version`, `repl_notebook_symbols`, `repl_workspace_export`, `repl_workspace_import`. Snapshot-load opcionális `version` argumentumot kapott. Workspace-fájlműveletek execution és snapshot-write jogosultságot kérnek; az MCP az IDEA editorállapotát nem gyűjti ki automatikusan.

## Pontos korlátok

A függőségek Java deklarációkból és lehetséges azonosítóhivatkozásokból készülnek; metódushívások, utasítások, tömbök és ismeretlen cellák konzervatív függőséget jelentenek. Nincs teljes alias/bean/async állapotkövetés vagy automatikus topologikus átrendezés. Egy korábbi DATA-kötés fagyasztott eredet; a később módosult élő változó nem feltétlenül egyezik vele.

Workspace: 200 MiB ZIP és összes tömörítetlen tartalom; 8 MiB metaadat; maximum 200 cella, 16 384 karakter kimenet/cella, 1000 futási bejegyzés, 200 HTTP-kérés és 100 bookmark. A teljes alkalmazás aktuális tartós mentéseit és a kötéseknél szükséges régi DATA-verziókat csomagolja; az összes történeti verzió tömeges exportja nincs. Offline mentés helyi forrás/metaadat. Fájlműveletekhez közös IDEA/JVM fájlrendszer szükséges.

A JVM objektumai, LIVE-pinek, hitelesítés, profilok, beanállapot, adatbázis és külső mellékhatások nem állnak vissza automatikusan. Az importált CASE adat-hivatkozásai átíródnak; tetszőleges Java/RECIPE forrásliterálok nem. A repository-batch import előkészít minden fájlt, névütközésnél nem ír; szokásos I/O-hibánál visszavonja az addigi új fájlokat. Folyamatösszeomlásra kiterjedő többfájlos tranzakciót nem állítunk.

A HTTP/transcript felismert titkait az export kitakarja; a forrás, cellakimenet és DATA alkalmazásadatot tartalmazhat. Nincs csomagtitkosítás vagy digitális aláírás. A gyökértípus Java-mezőséma-lenyomata nem teljes Jackson-séma és nem DTO-migráció.

## Ellenőrzés

- Java 17-en és Java 21-en egyaránt **233 teszt sikeres**: 70 plugin-, 13 JShell/MCP-integrációs és 150 runtime-teszt; nincs hibás vagy kihagyott teszt. A 200 MiB-os streaming határteszt 96 MiB heap mellett, külön JVM-ben történő visszatöltéssel is sikeres.
- A `:check :buildPlugin` sikeres; Java 21-en a `:verifyPlugin` is lefutott. Az IDEA Community és Ultimate 2025.2 (252.23892.409) eredménye **Compatible**. Mindkettő 1 eltávolításra jelölt és 17 elavult API-használatot jelez; kompatibilitási hibát nem talált.
- Az agent és a bridge Maven-csomagolása izolált másolatban sikeres, publikálás nélkül. A ZIP és a beágyazott JAR-ok integritását, verzióját, runtime agentjét és kézikönyvét ellenőriztem.
- A dokumentáció 49 MCP-eszközének neve és argumentumai egyeznek a sémával; 11 JSON-blokk, 30 argumentumpélda és 16 valódi JShell/Spring-futtatási példa ellenőrzött. A PDF **44 oldal**, 37 tartalomjegyzék-bejegyzés és 122 hivatkozás; mind a 44 oldal renderelése és a tördelés vizuális ellenőrzése megtörtént. A pluginba csomagolt példány azonos.
- A Cells, Snapshot Versions és Inspector panelek 700 és 900 pixel szélességű renderelési ellenőrzése sikeres.

Letölthető csomag: [sb-repl-0.16.0.zip](build/distributions/sb-repl-0.16.0.zip), **8 109 534 bájt**. SHA-256:

```text
87ca52483f1f10c55186f157f8ff1345bda54542acb518375f1646c04b6bc2de
```

A géppel olvasható eredmények és lenyomatok: [validation.json](build/workspace-0.16/validation.json). A teszt-XML-ek, buildnaplók, kompatibilitási jelentések és dokumentációs ellenőrzések a `build/workspace-0.16/` könyvtárban találhatók. A korábbi 0.15.0, 0.14.0 és 0.13.2 kiadási ZIP-ek változatlanok.

A tesztek izolált alkalmazással futottak; a saját üzleti alkalmazásodban és egy külső Claude-klienssel végzett kézi próba még nem történt meg. Telepítés: IDEA **Settings → Plugins → fogaskerék → Install Plugin from Disk**, majd válaszd a ZIP-et. Az IDEA és a célalkalmazás újraindítása szükséges, hogy az új plugin és runtime agent töltődjön be.

A további roadmapből még hátravan többek között a SQL-/külsőhívás-megfigyelés, Bean Explorer/stub, hívásfa/async trace, snapshot-sémamigráció, távoli tunnel/Kubernetes, külön headless runner és csapatszintű registry.
