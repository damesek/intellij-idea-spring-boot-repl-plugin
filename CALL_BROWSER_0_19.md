# Spring Boot REPL 0.19.0: a rögzített hívások böngészése

Mind a hat kért fejlesztés a **Tap / Trace → Recorded calls** nézetbe került. A meglévő .sbrepl-recording fájlok továbbra is megnyithatók, alkalmazáskapcsolat nélkül is. A böngészés a rögzített megjelenítési adatokat használja.

## Használat

1. Telepítsd a **build/distributions/sb-repl-0.19.0.zip** fájlt az IDEA **Install Plugin from Disk** menüjével. Indítsd újra az IDEA-t és a célalkalmazást.
2. A Java-forrás helyi menüjében válaszd a **Spring Boot REPL → Record Class Calls…** műveletet, vagy nyiss meg egy mentett felvételt a **Recorded calls → Open recording…** gombbal.
3. A gráf **+ / −**, **Fit graph**, **Show selected** gombjai és az egérrel húzás segítik a navigációt. Ctrl/Cmd + görgő a mutató körül nagyít, a sima görgő függőlegesen, Shift + görgő vízszintesen görget.
4. A kártyák sarkában levő jel összecsukja az ágat, és megmutatja a rejtett hívások/hibák számát. A **Search**, **Root**, **Thread**, **Errors only**, **Min ms**, **Focus branch** szűrők megtartják a találathoz vezető hívókat. A szaggatott kártyák a hívási út részei.
5. A **Previous / Next / Next error / Caller** és a kattintható hívási útvonal együtt mozgatja a gráf, az adatnézet és a forrás kijelölését. A léptetés a szűrésnek megfelelő, összecsukott ágban levő találatokat is eléri.
6. A **Pin reference** után másik node-ot választva a **Compare calls** fül mutatja a bemenet, az eredmény, a kivétel és a státusz eltéréseit. A referencia változatlan marad, amíg lecseréled vagy törlöd.
7. A **Timeline** szálanként mutatja a hívásokat. Egy sáv megnyitja a hívást; vízszintes húzással kijelölt időszak szűri a hívásfát. A **Clear time range** csak az időszakot törli.
8. A **Detach window…** külön, átméretezhető ablakba helyezi ugyanazt a böngészőt. Bezáráskor vagy a **Return to tool window** gombbal visszatér az eredeti panelre.

A **Values** oldalon a megszokott Tree / Formatted / Raw nézet és az Input at entry / Result at exit / Exception fülek érhetők el. A kártya rövid bemenetet és eredményt mutat; rámutatáskor teljes metódusszignatúra és nagyobb előnézet látható. A hiányos vagy befejezetlen adatot külön jelöli.

## A nézet viselkedése

- A kézi gráfnavigáció és a szűrés kikapcsolja a **Follow latest** módot. Frissítéskor megmarad a nagyítás és a nézett terület. A követés külön újra bekapcsolható.
- A keresés a dekódolt, már letöltött értékeken működik, kis- és nagybetűtől függetlenül. A találatokhoz vezető összecsukott ágakat automatikusan megmutatja.
- Fel/le: látható kártyák; bal/jobb: ág/hívó; Enter: forrás; egyenlőségjel/mínusz: zoom; Home: teljes látható gráf. Alt+bal/jobb: előző/következő hívás; Alt+le/fel: következő hiba/hívó. Ezek helyi billentyűk, a forrás és a REPL gyorsbillentyűit nem írják át.
- A léptetés a hívásba belépéskor kiosztott azonosítók sorrendjét használja. A lista végén nem ugrik automatikusan az elejére.
- Az összehasonlítás JSON-stringeknél is mezőnként működik. Tömböknél az index és a sorrend számít; ismétlődő JSON-mezőnevek külön előfordulások.
- A részleges vagy nem rögzített érték **UNKNOWN** lehet. Az egyező megjelenítési adatok nem bizonyítják a teljes objektumok egyezését. A nézet legfeljebb 2000 eltérést, 4096 karakteres mezőutat és értékenként 8192 karaktert mutat.
- Az idővonal a legkorábbi rögzített időhöz viszonyított milliszekundumokat mutatja. A kezdőidő rendszeróra, az időtartam külön mért eltelt idő; ez instrumentált futás, nem CPU-profil. Az átfedő hívások külön sorokat kapnak.
- A 200 hívásos, 32 MiB előnézeti rögzítési keret megmaradt. Továbbra is csak a kiválasztott osztályok és azonos szálon megfigyelt szülőkapcsolatok szerepelnek. Aszinkron szálak közötti kapcsolatot nem talál ki.
- A külön ablak ugyanazt a panelt használja, ezért a felvétel, a szűrés és a referenciakijelölés nem duplikálódik. Új felvétel a szűrést és a referenciát alaphelyzetbe állítja.

## Ellenőrzések

A regressziós tesztek az alábbiakat ellenőrzik:

- 200 hívás nagyítása, teljes nézetbe igazítása, skálázott kattintás, húzás és frissítés utáni görgetési pozíció.
- Hívási utak, egymásba ágyazott és ismételt hívások, ágösszecsukás, Unicode-értékkeresés és kombinált szűrők.
- Szálankénti idővonal, átfedő hívások, ezredmásodpercnél rövidebb időtartam és egérrel kijelölt időszak.
- Strukturális JSON/mező-összehasonlítás, ismétlődő kulcsok, részleges és hiányzó adatok.
- Valós IDEA-fixture-ben node-kijelölés, forrás melletti értékek, léptetés, referenciakijelölés és keskeny ablakban elérhető vezérlők.
- Valós nREPL-kapcsolaton késve érkező értékek: ugyanazon revízióhoz tartozó adat frissíti a panelt, és a forrásmegnyitási kérés megőrződik.

A tesztképek mintaadatokkal a **build/ui-safety/CallBrowser-*.png** fájlokban találhatók. A külön natív ablakot a headless teszt nem jeleníti meg; annak API-kompatibilitását a pluginverifikátor ellenőrzi.

A teljes futtatások eredményei és a csomag ellenőrzőösszegei a **build/call-browser-0.19/validation.json** fájlban találhatók. A magyar kézikönyv 39. fejezete tartalmazza az új gombok és billentyűk leírását.

## Végleges eredmények, 2026. szeptember 15.

| Ellenőrzés | Eredmény |
| --- | --- |
| Java 17: check + buildPlugin | 282 teszt, nulla hiba és kihagyás. |
| Java 21: check + buildPlugin + verifyPlugin | 282 teszt, nulla hiba és kihagyás. |
| IDEA 2025.2 Community és Ultimate | Compatible, mindkét kiadáson. |
| Csomag | 0.19.0 descriptor és osztályok; egyező agent és PDF; az előző 0.18.0 ZIP változatlan. |
| PDF | 51 oldal, 39 fejezet és 133 link; kirajzolva és átnézve. |

A verifikátor a meglévő API-k közül 19 elavult és két később eltávolítandó használatot jelez. Kompatibilitási hiba nincs.

Telepítő mérete: **8,539,175 bájt**. SHA-256: **2a18c1c88725052bac4ea12dba367671455ef5aaece550896f2b730366502412**. Az ellenőrzőösszeg a ZIP melletti .sha256 fájlban is megtalálható.
