> Archived experiment. This code is excluded from the supported build and does not implement the authenticated 0.9 protocol. Use the bundled agent and optional bridge described in [the main README](../README.md). The examples below are historical.

# REPL használati példák Spring Boot környezetben

Ez a dokumentum bemutatja, hogy hogyan használhatod az nREPL-t a Spring Boot alkalmazásodban.

## 📚 Alapvető használat

### 1. Egyszerű Java kifejezések

```java
// Matematikai műveletek
2 + 2
Math.PI * Math.pow(5, 2)

// String műveletek
"Hello".toUpperCase()
String.join(", ", "a", "b", "c")

// Dátum és idő
new Date()
LocalDateTime.now()
```

### 2. Collections és Stream API

```java
// Lista létrehozása és feldolgozása
List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5);
numbers.stream().map(n -> n * 2).collect(Collectors.toList())

// Map műveletek
Map<String, Integer> scores = new HashMap<>();
scores.put("Alice", 95);
scores.put("Bob", 87);
scores.entrySet().stream()
    .filter(e -> e.getValue() > 90)
    .map(Map.Entry::getKey)
    .collect(Collectors.toList())
```

### 3. HTTP kérések küldése (a példa controller-re)

```java
// URL connection használata
URL url = new URL("http://localhost:8080/api/hello");
HttpURLConnection conn = (HttpURLConnection) url.openConnection();
conn.setRequestMethod("GET");
BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
String line;
while ((line = reader.readLine()) != null) {
    System.out.println(line);
}
reader.close()
```

## 🌱 Spring Context elérése

Az ApplicationContext elérhető `ctx` néven minden Java REPL kódban:

```java
// Példa: Spring bean lekérése típus alapján
com.example.springboot.controller.ExampleController c = ctx.getBean(com.example.springboot.controller.ExampleController.class);
c.hello();

// Bean név alapján
Object dataSource = ctx.getBean("dataSource");
```

## 🔍 Debugging és információgyűjtés

### System properties

```java
// Összes system property
System.getProperties().forEach((k, v) -> 
    System.out.println(k + " = " + v))

// Specifikus property
System.getProperty("java.version")
System.getProperty("user.home")
```

### Memory információk

```java
Runtime rt = Runtime.getRuntime();
long totalMem = rt.totalMemory() / 1024 / 1024;
long freeMem = rt.freeMemory() / 1024 / 1024;
System.out.println("Total memory: " + totalMem + " MB");
System.out.println("Free memory: " + freeMem + " MB");
System.out.println("Used memory: " + (totalMem - freeMem) + " MB")
```

### Thread információk

```java
Thread.getAllStackTraces().keySet().forEach(thread -> 
    System.out.println(thread.getName() + " - " + thread.getState()))
```

## 📊 Adatbázis műveletek (ha van JPA)

### Példa: JDBC direkt használata

```java
// H2 in-memory database példa
Class.forName("org.h2.Driver");
Connection conn = DriverManager.getConnection("jdbc:h2:mem:testdb", "sa", "");
Statement stmt = conn.createStatement();

// Tábla létrehozása
stmt.execute("CREATE TABLE IF NOT EXISTS users (id INT PRIMARY KEY, n VARCHAR(50))");

// Adatok beszúrása
stmt.execute("INSERT INTO users VALUES (1, 'Alice')");
stmt.execute("INSERT INTO users VALUES (2, 'Bob')");

// Lekérdezés
ResultSet rs = stmt.executeQuery("SELECT * FROM users");
while (rs.next()) {
    System.out.println(rs.getInt("id") + ": " + rs.getString("name"));
}

// Kapcsolat lezárása
conn.close()
```

## 🎨 Hasznos utility függvények

### JSON pretty print (ha van Jackson)

```java
public class JsonUtils {
    public static Object run() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("n", "Test");
        data.put("values", Arrays.asList(1, 2, 3));
        
        com.fasterxml.jackson.databind.ObjectMapper mapper = 
            new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        
        return mapper.writeValueAsString(data);
    }
}
```

### File műveletek

```java
// Fájl olvasása
Files.readAllLines(Paths.get("application.properties"))
    .forEach(System.out::println)

// Fájl írása
Files.write(Paths.get("test.txt"), 
    Arrays.asList("Line 1", "Line 2", "Line 3"))

// Directory tartalmának listázása
Files.list(Paths.get("."))
    .map(Path::getFileName)
    .forEach(System.out::println)
```

## 🚀 Haladó példák

### Async műveletek

```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
    try {
        Thread.sleep(2000);
        return "Async result!";
    } catch (InterruptedException e) {
        return "Error: " + e.getMessage();
    }
});

// Várakozás az eredményre
future.get()
```

### Reflection használata

```java
// Osztály információk
Class<?> clazz = String.class;
Arrays.stream(clazz.getMethods())
    .filter(m -> m.getName().startsWith("to"))
    .map(Method::getName)
    .forEach(System.out::println)
```

## 📝 Tippek és trükkök

1. **Multi-line kód**: Használj pontosvesszőt a sorok végén
2. **Import-ok**: A gyakori csomagok automatikusan importálva vannak
3. **Output**: System.out.println() működik és visszaküldi a kimenetet
4. **Exceptions**: A stack trace megjelenik a konzolon
5. **Return érték**: Az utolsó kifejezés értéke automatikusan visszatér

## ⚠️ Limitációk

- Nem lehet új dependency-t hozzáadni runtime-ban
- A Spring context közvetlenül nem elérhető (workaround szükséges)
- Nem perzisztensek a változók a session-ök között
- Nagy objektumok printelése lassú lehet

## 🔗 Hasznos linkek

- [Java 17 API Dokumentáció](https://docs.oracle.com/en/java/javase/17/docs/api/)
- [Spring Boot Dokumentáció](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [IntelliJ IDEA Tippek](https://www.jetbrains.com/idea/guide/)
