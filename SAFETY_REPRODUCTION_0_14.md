# 0.14.0 – Safety & Reproduction

Ez a kiadás a kért fejlesztési terv első, használható csomagja. A teljes 0.15–1.0 ütemterv nincs kész; az alábbi státuszlista külön jelzi a működő részeket és a hátralévő munkát. A meglévő 11 fő fül megmarad, új funkciók a munkafüzetben, a Capture, Cases és MCP felületén érhetők el.

## Elkészült működés

- **Tranzakciós futtatás:** LIVE, ROLLBACK, READ_ONLY; PlatformTransactionManager választás; siker és hiba után is rollback; Java és CASE ugyanazt a kiválasztott szabályt használja. A kiválasztást az Apply execution mode gomb érvényesíti. MCP-ben külön, a kliens által felül nem írható mód van.
- **Időkorlát és mellékhatás-jelzés:** alapból 30 s, 100–120000 ms között állítható. Együttműködő megszakítás, a lezárás után nem szakítja meg a következő kiértékelést. A Side-effect hints végrehajtás nélkül keres gyanús forrásmintákat.
- **Reprodukció létrehozás/export/import:** utolsó Java-futás + kiválasztott eredeti DATA → új input, expected, CASE, RECIPE, környezeti metaadat és bundle index. `.sbrepl-bundle` ZIP, manifeszt, méret/SHA-256 ellenőrzés, 200 MiB összes kibontott adat. Importkor új nevek; nincs kódfuttatás és kontextus-visszaállítás.
- **Provenance:** capture időpont/szál, Java, elérhető Spring Boot/Git/build-verzió, profilok, locale/timezone, principalnév, context epoch; explicit tenant/feature flag adatok a bridge új overloadjával. A mentett HTTP-kérés külön választható metaadat. Hiányzó környezeti információt nem talál ki.
- **Hibát elváró CASE:** pontos kivételtípus és üzenet; automatikus kivétel-CASE a reprodukcióban. Üres exception-mezővel a korábbi DATA-összehasonlítás működik.
- **Több capture:** legfeljebb 16 aktív szabály, 1–100 felvétel, 1–10000 mintavételi intervallum, saját azonosító/állapot/visszavonás. Sorszámozott nevek és aktív névütközés-védelem; átfedő szabályok projection supplierje egyszer fut. A mentés továbbra is szinkron.
- **MCP:** alapból csak olvasás/elemzés; külön írás/törlés/CASE/capture/HotSwap engedély, eszközönkénti lista, rögzített végrehajtási mód, sessionkvóta és eredménykorlát. Összesen 39 eszköz, közülük 17 alapmódban érhető el az egyedi szűrőtől függően. Az új eszközök: `repl_capture_list`, `repl_execution_policy`, `repl_execution_preflight`, `repl_audit_events`, `repl_reproduction_create`.
- **Audit és titokminták:** privát JSONL, kezdés/lezárás/korreláció, forrás/hash, session/kliens, runtime epoch, capture mentések. Audit, history és alapból MCP-válasz kitakarja a felismert titkokat. Az AI-panel felismeréskor küldés előtt kitakar és újbóli promptellenőrzést kér.

## Pontos határok

Rollback csak a kiválasztott kezelő szinkron tranzakciójában részt vevő munkára vonatkozik. Belső REQUIRES_NEW, más kezelő, async/reactive kód, HTTP, üzenetek, fájlok és memóriabeli beanváltozások nem vonhatók vissza vele. Read-only driver/kezelő számára adott jelzés; nincs univerzális írástiltás. A timeout nem tudja garantáltan megállítani a megszakítást figyelmen kívül hagyó hívást; a Java-időzítő a tranzakció megszerzése után indul.

A reprodukció az utolsó eredményt a Create pillanatában másolja; nem időgép. A választott DATA-nak valóban a kód bemenetét kell tartalmaznia. Az import nem aktivál profilokat, nem jelentkezik be felhasználóként, nem állít be tenantot, órát vagy feature flag-et. A checksum nem aláírás. Független nevek védik a bemenet másolatát a forrás későbbi felülírásától, de általános immutable snapshot/verziózás még nincs.

Az MCP kapcsolói a tool API-t korlátozzák. Tetszőleges Java-futtatás engedélyezésekor nincs JVM-sandbox és nem garantálható package/bean/metódus korlátozás. A kvóta sessionönkénti, új session új kvótát kap. A titokdetektor heurisztikus; a workbook, DATA/RECIPE, transcript és bundle üzleti adatai nem automatikusan anonimizáltak. Az audit helyi, a host felhasználója módosíthatja; aktuális fájl + öt rotált példány marad, 4 MiB körüli rotációval. Audit nélküli kezdés tiltott; hibás lezárási audit bizonytalan kimenetelt jelez.

## Telepítés és kipróbálás

1. Az elkészült `build/distributions/sb-repl-0.14.0.zip` telepítése: IDEA → Settings → Plugins → Install Plugin from Disk, majd IDE-újraindítás.
2. Az alkalmazást a REPL-checkboxszal indítsd újra, hogy az új agent töltődjön be. Nem kell átírni a Spring profilokat.
3. Java REPL → Refresh managers → ROLLBACK → konkrét kezelő → Apply execution mode. Előbb egy elkülönített fejlesztői adatbázison ellenőrizd a saját tranzakciós konfigurációt.
4. Capture point + count, majd saját alkalmazáshívás; a Saved listából input betöltése. Futtasd az önálló reprodukáló cellát, majd Create reproduction. Export/import után a CASE-et külön indítsd.
5. MCP → szükséges kapcsolók és kezelő → Start MCP → Copy client config. Claude először `repl_execution_policy` és állapotlekérdezés.

A bridge új metaadat-overloadjához `hu.baader:sb-repl-bridge:0.14.0` kell. A helyi kiadás nem jelent Maven Central-publikálást; a bridge POM helyileg telepíthető a kézikönyv szerint.

## Ellenőrzés

A `check buildPlugin` teljes ellenőrzése JDK 17-en és 21-en is sikeres: **55 plugin + 11 JShell-integráció + 120 runtime = 186 teszt/JDK**, nulla hiba és kihagyás. Valódi H2-adatbázis igazolja a sikeres/hibás futtatás és a timeout utáni rollbacket, a manager-választást, a read-only jelzést és a CASE tranzakcióját. A reprodukciót export/import/újrafuttatás, kivétel-CASE, sérült ZIP és protokollsession tesztek fedik. Audit, kvóta, MCP-engedélyek, csonkolás és több capture szabály szintén regressziós tesztet kapott. A végső UI-módosítás után a panelek JDK 17-es próbája külön is sikeres.

**Plugin Verifier:** IDEA Community és Ultimate 2025.2, build `252.23892.409`: mindkettő **Compatible**. A jelentés 1 eltávolításra jelölt és 13 deprecated API-használatot jelez; ezek nem kompatibilitási hibák, a következő IDE-frissítésnél figyelmet igényelnek. Korábbi IDE-k újraellenőrzése ebben a kiadásban nem történt; a korábbi eredmények külön jelentésben vannak.

**Maven:** az agent és bridge `package -Dgpg.skip=true` buildje elkülönített forrásmásolatban sikeres, az agent csomagellenőrzője is átment. Nem történt Maven install/deploy vagy IDE-be telepítés.

**Dokumentáció:** 8 JSON-blokk, 22 paraméterezett példahívás és mind a 39 tool sémája egyezik a lefordított kóddal. A példákból 16 tényleges runtime/JShell hívás Java 21-en, ideiglenes Spring contextben és snapshot-tárban sikeres; a CASE eredménye PASSED. A PDF **37 oldalas**, 101 hivatkozással, vizuálisan ellenőrzött oldalakkal és kereten kívüli karakter nélkül. A végleges ZIP-ben a PDF, agent és Claude-fájlok bájtra egyeznek a build kimenetével. Spring/H2 tesztfüggőség nincs becsomagolva az agentbe. A korábbi 0.13.2 ZIP változatlan.

A felhasználó üzleti alkalmazása és külső Claude-kliense nem része az automatikus tesztkörnek. A saját adatforrás és tranzakciós konfiguráció viselkedését fejlesztői környezetben kell kipróbálni.

Bizonyítékok: `build/safety-0.14/validation.json`, a `jdk17`/`jdk21` XML-eredmények, `final-build-verifier.log`, `docs-check.log`, `maven-agent.log`, `maven-bridge.log` és `pdf-validation.json`.

Telepíthető ZIP: **7 836 967 bájt**. SHA-256:

```text
45e0d4dca0558d68e4c04cf6ca53817aaa81b2285ed13c7b5ddb4a1415a224ae
```

A checksum a ZIP melletti `sb-repl-0.14.0.zip.sha256` fájlban is szerepel.

## Hátralévő ütemterv

| Kért terület | A 0.14-en túli munka |
| --- | --- |
| Biztonságos futtatás | SQL-módosítások előnézete, outbound blokkolás/stub, valódi korlátozott végrehajtási környezet |
| Reprodukció | Teljes request/security/tenant/clock/flag adapterek, állapot-visszaállítás, érintett bean/osztály hash-ek |
| CASE 2.0 | Mezőszűrés, unordered/tolerancia/JSONPath/predicate, viselkedési assertionök, setup/teardown, paraméterezés, címkék, JUnit-import/export |
| Notebook/workspace | Cellánkénti output/sorszám/stale állapot, függőségi gráf, affected cells, teljes workspace mentés/visszaállítás |
| Jogosultság | Külön írási jóváhagyási folyamat, bean/metódus jogosultsági modell, napikvóta, teljes PII-felismerés, külső auditgyűjtő |
| Capture | Predicate/mezőfilter, ring buffer, aszinkron szerializáció konzisztenciaszabállyal, projektbe mentett szabályok |
| Bean Explorer | Scope/qualifier/dependency/condition report, sessionhez kötött spy/stub |
| Inspector | Keresés, breadcrumb/projekció, JSON Patch, élő diff, könyvjelzők, speciális rendererek |
| Trace | Signature-választás, feltétel/sampling, hívásfa/flame chart, async/reactive/MDC/SQL hozzárendelés, JFR/OTel export |
| Snapshot | Verziózás, immutabilitás, schema-hash/migráció, JsonNode betöltés, titkosítás és registry |
| Remote | SSH/Docker/Kubernetes port-forward, instance-választás/fingerprint, reconnect, TLS/mTLS |
| Headless/CI | Bundle CLI, JUnit XML/HTML riport, CI annotáció, megosztott csapatmunkafolyamat |
| MCP bővítés | CASE batch/result/JUnit, trace install/list/events, HTTP, snapshot patch/version/migrate, workspace, bean info és eseményértesítések |

Kapcsolódó leírások: [kézikönyv forrása](docs/repl-help-hu.md), [Claude beállítása](docs/claude-repl-guide-hu.md), [Claude-munkautasítás](docs/claude-repl-instructions.md), [protokoll](PROTOCOL.md). A tranzakciós működés alapja a [Spring programozott tranzakciókezelése](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html) és [propagációs szemantikája](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).
