# Spring Boot REPL 0.11.0 – élő adatok, tesztesetek és debugger

A 0.11.0 az eddigi cellákra, kiegészítésre, capture triggerre és 200 MiB-os DATA snapshotokra épül. Az új lapok: **Inspector**, **Tap / Trace**, **Cases / Reload**, **Debugger**.

## Inspector

A Java REPL eredményénél az **Inspect result**, a Variables lapon az **Inspect**, az eseménylistában pedig dupla kattintás nyitja meg az értéket. A táblázatban válassz egy mezőt vagy listaelemet, majd dupla kattintás / **Open selected**. **Back** visszalép, **Previous / Next** lapoz, **Refresh** újraolvassa az aktuális objektumot.

A böngésző közvetlen mezőket, tömböket, listákat, mapértékeket és kollekciókat mutat; nem hív DTO-gettereket vagy tetszőleges `toString()` metódusokat. A JVM által nem megnyitott mezőknél hozzáférési hibát jelez. Oldalanként 50 gyermek, legfeljebb 10 000 gyermek és 32 navigációs szint érhető el. A lapváltás érvényteleníti a korábbi sorok navigációs azonosítóit.

**Bind current** a jelenleg megnyitott objektumot a megadott Java-változóhoz rendeli. A típus automatikusan a hozzáférhető publikus típus, szükség esetén megadható kézzel. **Freeze DATA** tartós JSON-t, **Pin LIVE** sessionhivatkozást ment. Ezek az aktuális objektumot használják, az eredeti kifejezés újrafuttatása nélkül.

Az Inspector élő objektumokat tart: a más szálon történő módosítások láthatók lehetnek, és egy korábban megnyitott gyermek az eredeti hivatkozás marad. Stabil összehasonlításhoz ments DATA snapshotot.

## Tap és metódushívás-követés

Az alkalmazás opcionális bridge függőségének 0.11.0-s verziójával:

```java
import com.baader.sbrepl.bridge.SnapshotHelper;

SnapshotHelper.tap("cv-input", inputDto);
SnapshotHelper.tap("cv-result", resultDto);
```

A **Tap / Trace** lapon a **Start tap** kapcsolja be a fogadást. Üres szűrő minden címkét, kitöltött szűrő pontos egyezést fogad. A hívás agent vagy előfizetés nélkül `false` értékkel tér vissza. A tap nem szerializál és nem ír fájlt. **Stop tap** leállítja a fogadást; a meglévő értékek a lejáratig böngészhetők.

Metóduskövetéshez Java-forrásban állj a metódusra, majd **Code / Spring Boot REPL / Trace Method**. Ugyanez a Tap / Trace lapon teljes osztálynévvel és metódusnévvel is bekapcsolható. Belső osztály megadásakor a JVM-nevet használd, például `com.example.Outer$Inner`.

A követés a konkrét osztályban deklarált, azonos nevű metódusok összes nem szintetikus túlterhelésére vonatkozik. Megőrzi az alkalmazás visszatérési értékét és kivételeit. Az eseményben elérhető:

- `arguments`: a hívás argumentumai, legfeljebb az első 32;
- `result`: a visszatért objektum, `void` esetén `null`;
- `exception`: a kidobott kivétel, siker esetén `null`;
- `thread`, `depth`, valamint az eseménysorban a mért időtartam.

Az egymásba ágyazott követett hívások külön események. Az argumentumok és az eredmények élő hivatkozások; egy menet közben módosított argumentum nem őrzi meg automatikusan a belépéskori állapotát. Az eseményt megnyitva az Inspectorban az `arguments` vagy `result` alatt kiválasztható és menthető a kívánt adat.

**Untrace** egy metódust, **Stop all traces** a session összes követését állítja le. Legfeljebb nyolc metódus követhető sessionönként és 16 osztály JVM-enként. JDK- és agent-infrastruktúra nem választható. Az agent privát Byte Buddy függőségét használja; külön alkalmazásfüggőség nem kell hozzá.

A Tap / Trace és a tesztesetek eredményei közös, sessionönként legfeljebb **128 élő értékből álló, ötperces tárolóba** kerülnek. Túlfutáskor a legrégebbi érték esik ki, a számláló jelzi az eldobott eseményeket. Ez darabszám- és időkorlát, nem bájtkorlát. Reset, lezárás és Spring-contextváltás megszünteti az előfizetéseket és követési szabályokat. A panel látható állapotban 750 ms-onként frissül; az eseménylista kérései megkerülik az eval várakozási sorát.

## Snapshotból futtatható tesztesetek

1. Ments egy bemenetet és egy elvárt eredményt két **DATA** snapshotként.
2. A **Cases / Reload** lapon válaszd ki őket. Add meg a teszteset nevét, az input változónevét és szükség esetén a deklarált Java-típust.
3. Írd be a kódot. Az utolsó előállított érték lesz az összehasonlítás alapja. Például a saját alkalmazásod publikus típusaival:

```java
var processor = ctx.getBean(com.example.CvProcessor.class);
processor.process(input)
```

4. **Save case** csak ment. **Load selected** csak betölti szerkesztésre. A **Run saved cases** a táblázatban kijelölt, elmentett definíciókat futtatja.

Minden eset friss JShellt és a DATA snapshotból újra létrehozott bemenetet kap. A munkafüzet változói nem kerülnek át. A `ctx` ugyanannak az alkalmazásnak a valódi Spring contextje: a service-hívások adatbázis-, hálózati és egyéb mellékhatásai nem lesznek visszagörgetve.

Egy körben 1–20 eset fut, egymás után. Eredmények: **PASSED**, **FAILED**, **ERROR**, **CANCELLED**, **INCONCLUSIVE**. Az utolsó azt jelzi, hogy az összehasonlítás korlát miatt nem tudott teljes egyezést megállapítani. A jelentés legfeljebb 100 eltérést mutat, a meglévő diff mélység- és csomópontkorlátjaival. Az aktuális eredmény a privát snapshot-codecen át, 200 MiB-os korláttal kerül összevetésre; az ideiglenes JSON-fájl a vizsgálat végén törlődik.

**Rerun failed** a legutóbbi hibás vagy nem eldönthető eseteket futtatja. **Inspect actual** a kijelölt eset élő eredményét nyitja meg. **Stop** megszakítást kér, és nem indít további esetet. Bizonytalan kimenetelű kapcsolat-/timeout-hiba után nincs automatikus újrafuttatás.

A definíciók `CASE` típusú bejegyzésként az alkalmazás snapshotkönyvtárában maradnak. Egy CASE nem írhat felül azonos nevű DATA snapshotot vagy RECIPE-t. A normál JSON-import továbbra is adatot importál; importált kód nem válik automatikusan futtatható tesztesetté.

## Kódfrissítés és ellenőrzés

A Cases / Reload lapon **Use open Java editor** vagy **Choose Java file** választja ki a frissítendő forrásfájlt. A **Reload + run selected** minden alkalommal a legfrissebb szerkesztőtartalmat, illetve fájlt olvassa, majd:

1. lefordítja és HotSwap segítségével frissíti az osztályt;
2. kizárólag sikeres frissítés után futtatja a kijelölt, elmentett eseteket;
3. megjeleníti az eredményeket és az eltéréseket.

Fordítási vagy HotSwap-hibánál a tesztek nem indulnak el. A jelenlegi megoldás egy teljes Java-forrásfájlt frissít. A szabványos JVM-ben metódustörzs-módosítások támogatottak; mező, metódus vagy osztályszerkezet változtatásához alkalmazás-újraindítás szükséges. A szokásos **Reload Class** művelet önállóan továbbra is használható.

## Interaktív debugger

Indítsd a szokásos Spring Boot konfigurációt **Debug** módban, bekapcsolt REPL-checkboxszal. Várd meg a REPL csatlakozását, majd állj meg egy IDEA-breakpointnál vagy a **Debugger / Pause** gombbal.

- **Resume**, **Step over**, **Step into**, **Step out** az IDEA aktuális debug-sessionjét vezérli.
- **Show stack / locals** az IDEA debugablakát és végrehajtási pontját mutatja.
- **Evaluate in frame** a kiválasztott stack frame lokális környezetében értékeli ki a beírt Java-kifejezést. Például `input`, `this` vagy egy mező.
- **Capture to REPL** egyszer értékeli ki a kifejezést, és az objektumot egy egyszer használható átadási azonosítóhoz köti az alkalmazásban.

Capture előtt ellenőrzi, hogy a debugger és a REPL ugyanahhoz a PID-hez és aktív sessionhöz tartozik. A helper nem vár nREPL-kérésre és nem foglalja az eseménylista monitorát, amelyet megállított másik szál tarthat. **Resume után** a plugin átveszi az objektumot, létrehozza a megadott REPL-változót, és megnyitja az Inspectort. A capture önmagában nem folytatja a programot. Ugyanaz az objektum marad elérhető; egy későbbi stabil másolathoz Freeze DATA használható.

Az átadási értékek öt percig maradnak meg, legfeljebb 16/JVM-session; reset vagy bontás érvényteleníti őket. A debugger a futó program és a kiválasztott frame tényleges változóit használja, nem korábban mentett teljes JVM-állapotot.

## Telepítés és ellenőrzés

A 0.11.0-s plugin mellé az alkalmazást is újra kell indítani, hogy a beépített agent frissüljön. A `SnapshotHelper.tap` használatához az opcionális bridge függőséget is 0.11.0-ra kell frissíteni. A meglévő DATA és RECIPE fájlok formátuma változatlan.

Normál fejlesztői környezetben a teljes parancs: `./gradlew check buildPlugin verifyPlugin`.

A helyi csomagok: plugin ZIP (`build/distributions/Spring-Boot-REPL-0.11.0-local.zip`), opcionális bridge JAR (`build/distributions/sb-repl-bridge-0.11.0-local.jar`). Az IDEA-ban **Settings → Plugins → fogaskerék → Install Plugin from Disk** alatt válaszd ki a ZIP-et, majd indítsd újra az IDE-t és az alkalmazást. Az agent a pluginba van csomagolva. A bridge helyi JAR; távoli Maven-tárolóba nem lett publikálva.

### Ellenőrzés, 2026-09-14

- A runtime, protokoll és bridge teljes Java-forrása `javac --release 17` fordítással elkészült. A plugin és tesztjei Kotlin 1.9.21-gyel, az IDEA 2024.1.4 Java-plugin API-ja ellen sikeresen lefordultak. Négy korábban is használt, elavult IDEA API-ra figyelmeztetés maradt.
- **Java 17 és Java 21 alatt egyaránt 114 sikeres teszt**: 81 runtime, 29 plugin, egy nagy snapshot-próba és három agentcsomag-teszt. A runtime-kör ellenőrzi az Inspector objektumazonosságát, lapozását, elavult hivatkozásait, a tap/ticket lejáratát és sessionhatárait, a tesztesetek friss bemeneteit és elkülönített munkafüzetét, valamint a megszakítást.
- A kész `0.11.0` agenttel külön JVM-ben elindult Spring, a profilok megmaradtak, a követés kezelte a túlterheléseket, a beágyazott és `void` hívásokat, valamint a kivételeket. A HotSwap utáni követés is működött; szerkezeti változtatás elutasítása után az eredeti osztály használható maradt. A csomagolt Boot JAR osztályazonosság-próbája is sikeres. A helyben elérhető Spring Boot 3.2.0 / Spring 6.1.1 és Boot loader 2.7.18 szolgált a közvetlen tesztekhez; a Gradle/CI deklarált célja Boot 3.5.6.
- A plugin osztályai külön platform-, Java-plugin- és REPL-classloaderrel betöltődtek **IDEA Community 2024.1.4 / JDK 17**, illetve **IDEA Ultimate 2025.1.7 / JDK 21** mellett. Ez célzott osztálybetöltési próba, nem teljes Plugin Verifier-futtatás vagy az IDE minden műveletének ellenőrzése.
- Mind a négy új panel 700 és 900 pixel szélességgel kirajzolható és lezárható; a gombok az ablakon belül maradtak. A keskeny változatok képeit vizuálisan is ellenőriztem. Ez IDE nélküli Swing-próba; az IDEA-ban történő kattintásos végigjárás nem futott le.
- Két hálózatot igénylő teszt mindkét JDK-n **kihagyva**: hitelesített nREPL-kapcsolat valódi socketen, illetve megállított JVM-ből JDI-vel történő értékátadás. A tesztek megtalálhatók a projektben és a normál Gradle/CI-körben bekapcsoltak. A debugger helper PID/session ellenőrzése, egyszer használatos átadása és más szál által fogott eseménymonitor melletti működése a helyi runtime-tesztekben sikeres volt.

Az itt kiadott ZIP közvetlen Java/Kotlin-fordításból készült a `scripts/package-local.py` segítségével. **A teljes Gradle-build és a Plugin Verifier nem futott sikeresen**: a wrapper zárolófájlja itt nem írható; a korábbi, írható Gradle-home-mal végzett próba a daemon helyi socketjének tiltásán állt meg. A fordítás és a fenti tesztek sikeresek, de a teljes IDE-s debuggerfolyamat ellenőrzése normál helyi környezetben vagy CI-ben még szükséges.

A fordítási és tesztnaplók a `build/interactive/` könyvtárban vannak (`runtime-tests-17.log`, `runtime-tests-21.log`, `plugin-tests-*.log`, `agent-tests-*.log`, `large-tests-*.log`, `custom-tests-*.log`). A kiadott fájlok mellett SHA-256 ellenőrzőösszeg is található. Az agent csomagszerkezetét a `scripts/check-agent-package.py` ellenőrizte.
