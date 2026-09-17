# Spring Boot REPL 0.17.0 – használhatóság és snapshotpontok

A Java REPL felületén kevesebb állandó gomb maradt, a futtatás tényleges módja látható, és az eredmények közvetlenül a kód mellett használhatók. Java-forrásban egérrel beállítható snapshotpont rögzítheti egy alkalmazáshívás helyi értékét.

## A három felületi változtatás

1. **Kompakt eszköztár.** Connect, Run cell / selection, Interrupt és Save workspace ikon; Run, Workspace, Session és Tools menü. Mind a 42 workbench-parancs megtalálható az IDEA Find Action keresőjében `REPL:` névvel, és saját gyorsbillentyű rendelhető hozzá a Keymapben. A meglévő Ctrl/Cmd+Enter, Shift+Enter és forrásbeli kiértékelési kombinációk megmaradtak.
2. **Visszaigazolt futtatási mód.** Az alkalmazás neve, profilja, kapcsolata és a szerver által visszaigazolt LIVE / DB rollback / DB read-only mód látszik. A módválasztás azonnal kérést küld; nincs külön Apply. Folyamatban levő vagy bizonytalan beállítás alatt Java és CASE nem indítható, gyorsbillentyűvel sem. Sikertelen váltás után visszaolvassa a szerver állapotát. A tranzakciókezelő és időkeret a Session > Execution settings alatt állítható.
3. **CIDER-mintájú helyi eredmények.** A cellák alatt saját futási sorszám, állapot, időtartam, előnézet és elavulásjelzés látszik. Run / Inspect / Snapshot / Output közvetlenül kattintható; a margó menüje ugyanezeket adja. Az Evaluate at Caret és Run Selection eredménye a Java-forrás mellett jelenik meg. Az Inspect és Snapshot az eltárolt eredményreferenciát használja, a forráskifejezést nem futtatja újra. A mentési ablakban név, meglévő cél és opcionális típus választható.

Reset és új kapcsolat után a korábbi élő eredményhez tartozó műveletek letiltódnak. A checkpointban megmaradó cellakimenet korábbi futási bizonyíték; az élő objektumreferencia nem kerül bele a workspace-be. A szerkesztő és eredménypanel közötti osztó húzható; a keskeny ablak kezdeti elrendezése is javult.

## Snapshotpont: kattintástól a mentett adatig

1. A szokásos Spring Boot Run Configurationben legyen bekapcsolva az **Enable Spring Boot REPL**. Indítsd **Debug** módban.
2. Válassz végrehajtható Java-sort, amely előtt a mentendő változó már inicializálva van. Jelöld ki például az `order` változót.
3. Jobb kattintás > Spring Boot REPL > **Snapshot point…**, vagy a margó helyi menüje. Add meg a kifejezést, snapshotnevet, opcionális Java-típust és a kísérletek számát.
4. Lila jel kerül a margóra. Indítsd el az alkalmazás vizsgált hívását: a sor előtt DATA készül, majd a debugger folytatja az alkalmazást. Siker vagy hiba a Debug konzolban látszik.
5. **Snapshots > Saved** alatt a mentés betölthető és inspectálható. A jel helyi menüjében **Configure snapshot / rearm…** nyitja a beállítást; **Save and rearm** újraélesít, **Remove point** csak a pontot törli.

A pont natív Java-breakpointként tárolódik az IDE projektjében, és követi a sor mozgását. A normál breakpoint gyorsbillentyűjét nem veszi át. Meglévő hagyományos breakpoint mellé ugyanarra a sorra a létrehozó művelet nem helyez új snapshotpontot.

### Működési határok

- **Debug kell.** A normál Run nem figyeli a forrássorokat. A CIDER-mintájú kiértékelés Java/JShell-sessionben fut; metóduslokális értékhez debuggerátvétel vagy snapshotpont használható.
- A sor találatakor a debugger a szálon kiértékeli a kifejezést és szerializál. Ez rövid megállást jelent, majd automatikus folytatást; nagy objektum lassíthatja a hívást. Változó vagy mező választása célszerű, mert metódus és egyedi szerializáló is okozhat mellékhatást. Más szálak által módosított objektumról nincs garantált atomi kép.
- Alapból **1 mentési kísérlet**, legfeljebb 100 pontonként/JVM-indulásonként. A szerializációs hiba is elfogyaszt egy kísérletet, így javítás után újraélesítés kell. Egy projekthez legfeljebb 64 pont készíthető; a runtime legfeljebb 1024 külön élesítés számlálóját tartja meg egy JVM-életciklusban.
- A DATA alapkorlát továbbra is **200 MiB**. Azonos név új verziót ad, legfeljebb 100 verzióig. Sikertelen mentés megtartja a korábbi DATA-t. A pont törlése nem törli a snapshotot.
- A pont a debugger beállításaiban él, a workspace-export nem tartalmazza. A kész DATA az eddigi MCP-eszközökkel használható; a pontok kezeléséhez nem készült új MCP-eszköz. Az MCP továbbra is saját sessiont és saját végrehajtási szabályt használ.
- A DB rollback a részt vevő szinkron adatbázis-műveletekre vonatkozik. HTTP, fájl, üzenetküldés és memóriabeli objektumváltozás külön hatás.

## Ellenőrzés

A végleges forrásból, helyben végrehajtva:

```sh
./gradlew :check :buildPlugin
./gradlew :check :buildPlugin :verifyPlugin -PtestJdk=21
python3 scripts/check-agent-package.py dev-runtime/build/libs/dev-runtime-agent-0.17.0.jar
```

| Ellenőrzés | Java 17 | Java 21 |
| --- | ---: | ---: |
| Plugin, UI és állapotmodellek | 81 sikeres | 81 sikeres |
| Plugin–JShell/MCP integráció | 14 sikeres | 14 sikeres |
| Runtime és JVM-integráció | 154 sikeres | 154 sikeres |
| Összesen | **249 sikeres** | **249 sikeres** |

Mindkét futásban nulla hiba, nulla kihagyott teszt. A buildPlugin a teljes regressziós ellenőrzéstől függ.

A célzott ellenőrzések lefedik a pending/hibás futtatásimód-választ, az elavult válaszok elutasítását, a futáshoz tartozó eredményreferenciák érvényességét, a keskeny nézet kattintható műveleteit, a natív IDEA action/breakpoint-regisztrációt és a valódi editor inlay-életciklusát. A teljes tool window 700 és 1100 pixel szélességen headless IDEA-tesztben is betöltődött és kirajzolódott.

A snapshotpont generált reflection-kifejezését valódi JShell értékelte ki. Külön JDI-integrációs teszt induló agentes JVM-ben állított Java-sorbreakpointot: elmentette a helyi értéket, majd folytatta az alkalmazást. A későbbi objektummutáció nem változtatta meg a DATA-t. Párhuzamos találatoknál a mentési kísérletek száma korlátozott; hibás szerializáció nem cseréli le a régi mentést.

**IDEA 2025.2:** IC és IU `252.23892.409` Plugin Verifier eredménye egyaránt **Compatible**. Nincs jelentett bináris kompatibilitási hiba. A jelentés 18 deprecated és 2 későbbi eltávolításra jelölt API-használatot tartalmaz; a támogatás nem terjed ki automatikusan a 252 utáni IDE-verziókra. A fordítás a támogatott tartomány alsó, 2024.1.4 SDK-jával történik.

Ez automatizált, elkülönített környezetben végzett ellenőrzés. A felhasználó üzleti Spring Boot alkalmazásával, személyes IDEA-konfigurációjával és interaktív Claude-kliensével teljes kézi végponttól végpontig próbát ez a kör nem végzett.

A csomag ellenőrzése igazolta a 0.17.0 pluginverziót, a Java-pluginfüggőséget, a 42 parancsot, az új osztályokat, az agent és PDF pontos egyezését, valamint az IDEA-osztályok hiányát a plugin könyvtáraiból. A korábbi 0.16.0 ZIP változatlanul megmaradt.

## Csomag és dokumentáció

- Telepítő: `build/distributions/sb-repl-0.17.0.zip`, SHA-256 a mellette levő `.sha256` fájlban.
- Magyar PDF: `output/pdf/spring-boot-repl-guide-hu.pdf`; **46 oldal, 38 fejezet**, ellenőrzött rendereléssel és kattintható tartalomjegyzékkel. Ugyanez a PDF az IDE Help menüjében is elérhető.
- A 3., 24., 28., 35. és új 38. fejezet mutatja az eszköztárat, billentyűket, futtatási módot, helyi eredményeket és snapshotpontot. A Claude-útmutatók is frissültek.
- Gépi ellenőrzési összesítő: `build/ux-0.17/validation.json`; teszteredmények, logok és képi ellenőrzés ugyanitt.

Telepítés: Settings > Plugins > Install Plugin from Disk, majd IDEA-újraindítás. A futó célalkalmazást is indítsd újra az új beépített agenthez. Ez a kör nem telepítette vagy indította újra helyetted az IDE-t/üzleti alkalmazást, és nem publikált Maven-csomagot.
