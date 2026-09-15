# Spring Boot REPL 0.12.0 – automatikus nézet és futtatás nélküli kódellenőrzés

A Java REPL eredménypanelje és az Inspector most automatikusan strukturált nézetet mutat. A munkafüzet gépelés közben fordítási hibákat és figyelmeztetéseket jelez, a kód futtatása nélkül. A korábbi [Inspector, Tap/Trace, Cases/Reload és debugger](REPL_WORKFLOW_0_11.md) továbbra is elérhető.

## Objektumok és JSON

Futtass egy cellát vagy kijelölést a **Run cell / selection**, **Ctrl+Enter / Cmd+Enter** művelettel. A jobb oldali **Value** panelen az eredmény automatikusan megjelenik:

- **Tree**: összecsukható mezők, tömbök és listák, Java-típusok, `null`, körkörös és ismételt hivatkozások.
- **Formatted**: kétszóközös behúzás; objektumoknál olvasható, JSON-szerű mezőnézet.
- **Raw**: JSON-string esetén az eredeti szöveg; más objektumnál a szöveges eredményösszegzés.

Az **Expand / Collapse** gombok a fát nyitják és csukják. A **Copy formatted** a formázott szöveget másolja. A `stdout`, `stderr` és a hibák az **Output / errors** lapon, valamint a munkamenet naplójában maradnak elérhetők.

Próbáld ki például:

```java
// %% JSON-szöveg
String json = "{\"customer\":{\"name\":\"Árvíztűrő\",\"active\":true},\"items\":[{\"amount\":12345.67},null]}";
json

// %% Java-objektum
record Customer(String name, java.util.List<Integer> scores) {}
new Customer("Anna", java.util.List.of(8, 10, 9))
```

A `{` vagy `[` karakterrel kezdődő érvényes JSON-stringet automatikusan felismeri. A nagy egész számokat és tizedeseket nem alakítja lebegőpontos számmá; az ismételt JSON-kulcsokat is megőrzi a nézetben. A Jackson `ObjectNode` és `ArrayNode` értékei is a logikai JSON-tartalmukkal jelennek meg. Hibás JSON esetén az eredeti szöveg marad látható.

A forráskódból indított **Evaluate at Caret / Run Selection** eredménye is ebbe a panelbe érkezik. A billentyűk az IDEA Keymap alatt kereshetők; az alapértelmezett Evaluate at Caret `⌘⇧E`, a Run Selection `Ctrl+Shift+R`. Ezek továbbra is a REPL változóival dolgoznak. Egy futó metódus lokális változójához a debugger megállított frame-je és **Capture to REPL** szükséges.

Az **Inspect result** az élő objektumot nyitja meg. Az Inspector **Value** lapja ugyanazt az automatikus nézetet használja; a **Fields** lapon mezőt kijelölve, majd dupla kattintással / **Open selected** lehet tovább navigálni. A **Bind current** az így megnyitott valódi objektumot köti változóhoz. A Value-fa kibontása csak a megjelenítést változtatja, a Bind current célját nem.

A megjelenítés nem futtatja újra az eredeti kifejezést, nem hív DTO-gettereket vagy tetszőleges `toString()` metódusokat. A Java-objektum előnézete közvetlen mezőolvasás; nem használja a snapshot Jackson-mixineket, és nem helyettesíti a DATA mentést. Egyedi/lusta kollekciókat és delegáló kollekcióburkolókat automatikusan nem jár be. Nem hozzáférhető mezőnél magyarázat jelenik meg. A változó és a result handle továbbra is az eredeti objektumra hivatkozik.

Az automatikus előnézet korlátai: 500 meglátogatott érték, 6 szint, konténerenként 50 gyermek, összesen 131072 szövegkarakter. A beágyazott szövegek külön 4096 karakterre korlátozottak. A JSON-string fa legfeljebb 5000 csomópontot és 32 szintet tartalmazhat. A formázott szöveg 262144 karakterig készül el; a csonkolást jelzi. Nagy gráfnál **Partial preview** látszik, és az Inspector Fields lapján kisebb részek nyithatók meg. A DATA snapshot **200 MiB** korlátja ettől független.

## Gépelés közbeni kódellenőrzés

A fő **Java REPL munkafüzetben** a **Live check** alapértelmezetten bekapcsolt. Csatlakozás után, 650 ms gépelési szünettel ellenőrzi a teljes munkafüzetet. A **Check code** azonnali ellenőrzést kér. Éppen futó kiértékelés mellett megvárja annak befejezését.

Az ellenőrző ismeri a session importjait, változóit, metódusait, típusait, a `ctx` API-ját és az alkalmazás classpathját. A munkafüzet korábbi, még nem futtatott celláinak deklarációit is figyelembe veszi. A hibákat és figyelmeztetéseket aláhúzás, a szerkesztő alatti összesítés és az aláhúzott rész fölé vitt egérrel megnyitható üzenet mutatja. Szerkesztés vagy sessionváltás után a régi válasz nem rajzolhat vissza elavult jelzést.

Példa – írd be, futtatás nélkül:

```java
var names = new java.util.ArrayList<String>();
names.add(123); // típushiba: String kell
ctx.missingMethod(); // nincs ilyen ApplicationContext-metódus
```

Az ellenőrzés külön fordítói munkateret hoz létre. A végrehajtója nem tölt be generált osztályt, nem hív metódust, konstruktort vagy statikus inicializálót; az annotációfeldolgozás is ki van kapcsolva. Nem módosítja a valódi REPL változóit, a `last1` eredményt vagy az alkalmazásobjektumokat, és nem játssza újra a korábbi inicializáló kódot. A JShell [ExecutionControl felülete](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.jshell/jdk/jshell/spi/ExecutionControl.html) választja el a fordítást a végrehajtástól.

Ez Java-fordítói szintaktikai és típusellenőrzés. Nem teljes linter-szabálygyűjtemény, és nem tudja futtatás nélkül megállapítani, hogy egy bean létezik-e, egy érték `null`-e, vagy egy szolgáltatáshívás sikerülni fog-e. A normál Java-forrásfájlokon az IDEA saját inspections rendszere működik; a Cases és Debugger szövegmezőire ez a munkafüzet-ellenőrző nem terjed ki.

Korlátok: 100000 forráskarakter, 200 sessiondeklaráció / 100000 kontextuskarakter, 200 munkafüzetsnippet, 100 diagnosztika. A fordítói műveletek között ötmásodperces időkeretet figyel; egy folyamatban lévő javac-műveletet nem tud erőszakkal leállítani. Korlát elérésekor **Partial code check** jelenik meg. Az **Interrupt** megszakítást kér, a **Live check** kikapcsolható. A futtatáshoz továbbra is külön Run művelet kell.

## Telepítés és ellenőrzés

Telepíthető csomag: [Spring-Boot-REPL-0.12.0-local.zip](build/distributions/Spring-Boot-REPL-0.12.0-local.zip). Az IDEA-ban **Settings → Plugins → fogaskerék → Install Plugin from Disk** alatt válaszd ki, majd indítsd újra az IDE-t és a fejlesztői alkalmazást, hogy a beépített agent is frissüljön. Ha egyéni agent JAR-t állítottál be, használj hozzá 0.12.0-s példányt vagy térj vissza a beépített agenthez. Ehhez a két új funkcióhoz külön bridge függőség nem kell.

### Ellenőrzés, 2026-09-14

- A runtime, a protokoll és a bridge teljes Java-forrása sikeresen lefordult `javac --release 17` használatával. A plugin és tesztjei Kotlin 1.9.21-gyel, az IDEA 2024.1.4 Java-plugin API-ja ellen lefordultak. Négy, korábban is használt elavult IDEA API-ra figyelmeztetés maradt.
- **Java 17 és Java 21 alatt egyaránt 141 sikeres teszt**: 98 runtime, 39 plugin, egy nagy snapshot-próba, három agentcsomag-teszt. Az új tesztek ellenőrzik az objektumazonosságot, a körkörös és lusta objektumokat, a formázási korlátokat, a JSON számjegyeinek és Unicode-karaktereinek megőrzését, valamint a diagnosztikák dokumentumpozícióit. Az ellenőrző nem hívta meg a tesztmetódusokat, konstruktorokat, statikus inicializálókat vagy annotációfeldolgozókat, nem írt tesztfájlt és nem módosította a session értékeit. A hiányzó metódusfüggőségeket jelzi, a későbbi deklaráció által feloldott hivatkozásokat elfogadja.
- A kész **0.12.0 agenttel** külön JVM-ben elindult Spring. A bean automatikus mezőnézete és az alkalmazástípusokat ismerő kódellenőrzés hagyományos classpathról és végrehajtható Boot JAR-ból is sikeres volt. A korábbi profil-, trace-, HotSwap- és contextéletciklus-próbák is sikeresek. A helyi függőségek Spring Boot 3.2.0 / Spring 6.1.1 és Boot loader 2.7.18; a Gradle/CI deklarált Spring Boot célja 3.5.6.
- Külön platform-, Java-plugin- és REPL-classloaderrel sikeres az osztálybetöltés **IDEA Community 2024.1.4 / JDK 17** és **IDEA Ultimate 2025.1.7 / JDK 21** alatt. Az új JSON-parser és formázó ezekkel az IDEA-függőségekkel is lefutott. A ZIP Java 17-es bájtkódot tartalmaz, nem csomagol saját IDEA/Kotlin API-osztályokat, és a beépített agent bájtról bájtra egyezik a külön ellenőrzött JAR-ral.
- A Swing-panelek IDEA nélkül kirajzolhatók; az új JSON-fa Expand/Collapse működését teszt ellenőrzi. A strukturált nézet képét vizuálisan is ellenőriztem: `build/presentation/ui-17/StructuredValuePanel-json.png`.
- A valódi nREPL-socketes és JDI-átadási teszt mindkét JDK-n **kihagyva** a helyi sockettiltás miatt. Az IDEA-ban történő kattintásos végigjárás és a teljes Plugin Verifier-futtatás nem történt meg.

Az itt kiadott ZIP a `scripts/package-local.py` segítségével, közvetlen Java/Kotlin-fordításból készült. **A teljes Gradle-build nem futott sikeresen**: a wrapper zárolófájlja ebben a környezetben nem írható; a korábbi írható Gradle-home-os próbát a daemon helyi socketjének tiltása állította meg. Normál fejlesztői környezetben a teljes ellenőrzés parancsa `./gradlew check buildPlugin verifyPlugin`.

A naplók a `build/presentation/` könyvtárban vannak: `*-compile.log`, `runtime-tests-*.log`, `plugin-tests-*.log`, `agent-tests-*.log`, `large-tests-*.log`, `debugger-tests-*.log`, `gradle-build.log`. A [plugin ZIP](build/distributions/Spring-Boot-REPL-0.12.0-local.zip), a [külön agent](build/distributions/dev-runtime-agent-0.12.0.jar) és az [opcionális bridge](build/distributions/sb-repl-bridge-0.12.0-local.jar) mellett SHA-256 ellenőrzőösszeg is található. A korábbi verziók csomagjai megmaradtak; Maven-tárolóba nem történt publikálás.
