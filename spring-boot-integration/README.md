# Spring Boot nREPL Integration

Ez a mappa tartalmazza a szükséges Java osztályokat, amiket a Spring Boot projektedhez kell adnod, hogy nREPL szerver fusson benne Java kód kiértékeléssel.

## 🚀 Gyors integráció

### 1. Másold be ezeket a fájlokat a Spring Boot projektedbe:

```
src/main/java/com/example/springboot/nrepl/
├── NreplServerComponent.java    # Valódi nREPL szerver indítása + middleware
├── JavaCodeEvaluator.java       # Java kód fordító és futtató (JDK compiler API)
├── JavaMiddleware.java          # nREPL ↔ Java híd (válaszok küldése)
└── EvalEnvironment.java         # ApplicationContext átadás a REPL-nek

# (Opcionális, csak referencia)
# src/main/java/com/example/springboot/nrepl/SimpleNreplServer.java  # minimal nREPL-szerű szerver – alapból kikapcsolt
```

És tedd be ezt a Clojure forrást classpath-ra (resources alá):

```
src/main/resources/com/example/springboot/nrepl/java_middleware.clj
```

### 2. Add hozzá az application.properties-hez:

```properties
# nREPL szerver beállítások (valódi nREPL + middleware)
nrepl.enabled=true
nrepl.port=5557
nrepl.host=127.0.0.1

# A régi, egyszerű (nem-CLJ) szerver külön kapcsolóval – alapból KI
nrepl.simple.enabled=false
```

### 3. Spring Boot alkalmazás indítása

Amikor elindítod a Spring Boot alkalmazásodat, automatikusan elindul az nREPL szerver is:

```bash
mvn spring-boot:run
```

Vagy:

```bash
./gradlew bootRun
```

A konzolon ezt kell látnod:
```
✅ nREPL server started on port 5557
   Java code evaluation enabled (prefix with //!java)
```

## 📝 Példa használat

### IntelliJ IDEA plugin-ból:

1. Indítsd el a Spring Boot alkalmazást
2. Az IntelliJ-ben: Java REPL tool window → Connect
3. Írj Java kódot és futtasd (Ctrl+Enter)

### Példa kódok:

```java
// Egyszerű kifejezés
2 + 2

// Változók
int x = 10;
int y = 20;
x + y

// Metódushívás
String.format("Hello %s!", "World")

// Lista műveletek
List<String> list = Arrays.asList("a", "b", "c");
list.stream().map(String::toUpperCase).collect(Collectors.toList())

// Print to console
System.out.println("Hello from nREPL!");

// Teljes osztály
public class Test {
    public static Object run() {
        return "Custom class result";
    }
}
```

## ⚙️ Részletek

### NreplServerComponent.java

- Valódi nREPL szerver indítása (nrepl:nrepl)
- Saját middleware (Clojure) bekötése `//!java` prefixhez
- ApplicationContext elérhető a REPL kódon belül `ctx` néven

### JavaCodeEvaluator.java

- Java kód dinamikus fordítása (JavaCompiler API)
- Automatikus becsomagolás osztályba
- Import-ok hozzáadása (java.util.*, java.io.*, stb.)
- A Spring `ApplicationContext` automatikusan elérhető: `final ApplicationContext ctx = ...`

## 🔧 Testreszabás

### Port változtatása:

```properties
nrepl.port=7888
```

### Kikapcsolás:

```properties
nrepl.enabled=false           # teljes nREPL kikapcsolása
nrepl.simple.enabled=false    # egyszerű szerver kikapcsolása (default)
```

### Saját middleware hozzáadása:

Módosítsd a `SimpleNreplServer.handleMessage()` metódust.

## 📦 Maven dependency (ha külön projektként használod):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
</dependency>
```

## ⚠️ Biztonsági figyelmeztetés

**FONTOS:** Az nREPL szerver tetszőleges Java kód futtatását teszi lehetővé! 

- Csak fejlesztési környezetben használd
- Produkciós build-ben kapcsold ki (`nrepl.enabled=false`)
- Használj tűzfalat vagy bind-old csak localhost-ra

## 🐛 Hibaelhárítás

### "Java compiler not available"
- Győződj meg róla, hogy JDK-t használsz, nem JRE-t
- Ellenőrizd: `java -version` és `javac -version`

### Port már használatban van
- Változtasd meg a portot az application.properties-ben
- Vagy állítsd le a másik folyamatot: `lsof -i :5557`

### Nem tud csatlakozni az IntelliJ
- Ellenőrizd, hogy fut-e a Spring Boot app
- Nézd meg a konzolt, hogy elindult-e az nREPL szerver
- Próbáld meg telnet-tel: `telnet localhost 5557`
