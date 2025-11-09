# Spring Boot REPL Használati Útmutató

## 🚀 Gyors Start

### 1. Alkalmazás indítása
```bash
cd ~/Documents/Codes/vernyomas-app
mvn -DskipTests -Dspring-boot.run.jvmArguments="-Dspring.liveBeansView.mbeanDomain=devrepl" spring-boot:run
```

### 2. REPL indítása és kapcsolódás
```bash
cd ~/Documents/Codes/sb-repl
./gradlew runIde
```

1. Java REPL tool window megnyitása
2. **Attach & Inject Dev Runtime** gomb
3. Válaszd ki a vernyomas-app JVM-et
4. **Bind Spring Context** gomb → beírod: `hu.vernyomas.app.AppCtxHolder.get()`

## ⚠️ Fontos: Spring Bean-ek Használata

### Megoldás a ClassLoader problémára
**Ha ClassCastException-t kapsz**, akkor a Spring Boot DevTools okozza a problémát.
- **Megoldás**: Kapcsold ki a DevTools-t a `pom.xml`-ben (comment-eld ki a spring-boot-devtools dependency-t)

### ✅ Két működő megoldás

#### 1. Reflection (MINDIG működik - DevTools-szal is)

**Paraméter nélküli metódus:**
```java
var service = applicationContext.getBean("bloodPressureService");
return service.getClass().getMethod("getStatistics").invoke(service);
```

**Paraméteres metódus:**
```java
var service = applicationContext.getBean("bloodPressureService");
var method = service.getClass().getMethod("getRecentReadings", int.class);
return method.invoke(service, 10);
```

#### 2. Direct Cast (DevTools NÉLKÜL működik)

**Ha nincs DevTools a projektben:**
```java
var service = (hu.vernyomas.app.service.BloodPressureService) applicationContext.getBean("bloodPressureService");
return service.getStatistics();
```

**Vagy típussal:**
```java
var service = applicationContext.getBean("bloodPressureService", hu.vernyomas.app.service.BloodPressureService.class);
return service.getStatistics();
```

## 📊 Hasznos Példák

## ♻️ HotSwap módosított osztályok

1. Illeszd be a **teljes osztálykódot** (package + class) a REPL szerkesztőbe
2. Jelöld ki a kódot (vagy hagyd az egész fájlt kijelöletlenül), majd kattints a **Hot Swap** gombra
3. Az agent lefordítja a forrást, és `Instrumentation.redefineClasses` segítségével **újratölti a futó JVM-ben**

> Tipp: csak olyan osztály működik, amit a JVM már betöltött. Ha "class not loaded" hiba jön, futtasd le a szolgáltatást a régi kóddal (hogy ténylegesen betöltődjön), majd próbáld újra a Hot Swap-et.

## 🫘 Bean Getter gyors beszúrás

- A **Insert Bean Getter** (szerviz ikon) gombra kattintva a REPL lekéri az aktuális Spring bean listát
- Gépelj rá a bean nevére vagy típusára, enter → automatikusan beszúrja a `var myService = applicationContext.getBean(FooService.class);` sort a kurzorhoz
- A gomb csak akkor aktív, ha a Spring context már be van bind-olva

### Repository használat
```java
// Repository metódus hívása reflection-nel
var repo = applicationContext.getBean("bloodPressureRepository");
return repo.getClass().getMethod("findAll").invoke(repo);
```

### Service használat - statisztikák
```java
// Service metódus hívása
var service = applicationContext.getBean("bloodPressureService");
return service.getClass().getMethod("getStatistics").invoke(service);
```

### Mai mérések
```java
var service = applicationContext.getBean("bloodPressureService");
return service.getClass().getMethod("getTodayReadings").invoke(service);
```

### Legutóbbi N mérés
```java
var service = applicationContext.getBean("bloodPressureService");
var method = service.getClass().getMethod("getRecentReadings", int.class);
return method.invoke(service, 5);  // Utolsó 5 mérés
```

### Új mérés hozzáadása
```java
// Entity létrehozása - ez működik, mert új objektum
var reading = new hu.vernyomas.app.entity.BloodPressureReading();
reading.setSystole(125);
reading.setDiastole(82);
reading.setPulse(72);
reading.setMeasuredAt(java.time.LocalDateTime.now());
reading.setNotes("REPL tesztből");

// Repository save metódus hívása reflection-nel
var repo = applicationContext.getBean("bloodPressureRepository");
var saveMethod = repo.getClass().getMethod("save", Object.class);
return saveMethod.invoke(repo, reading);
```

### Bean-ek listázása
```java
// Összes bean név
return java.util.Arrays.asList(applicationContext.getBeanDefinitionNames());
```

### Repository-k keresése
```java
// Összes repository bean
var repos = applicationContext.getBeansOfType(org.springframework.data.repository.Repository.class);
return repos.keySet();
```

### Service-ek keresése
```java
// Összes @Service annotált bean
var services = applicationContext.getBeansWithAnnotation(org.springframework.stereotype.Service.class);
return services.keySet();
```

## 💾 Snapshot használat

### Mentés
1. Futtasd le a kódot
2. **Snapshots** fül → **Save** gomb
3. Adj nevet (pl. `stats1`)

### Betöltés
1. **Snapshots** fül → válaszd ki → **Load**
2. Ez beszúrja:
```java
Object stats1;
try {
  Class<?> ss = Class.forName("com.baader.devrt.SnapshotStore");
  java.lang.reflect.Method get = ss.getMethod("get", String.class);
  stats1 = get.invoke(null, "stats1");
} catch (Exception e) {
  stats1 = null;
  System.err.println("Failed to load: " + e);
}
return stats1;
```

### JSON Import
1. **Import JSON** gomb
2. Illeszd be a JSON-t
3. Add meg a típust: `hu.vernyomas.app.entity.BloodPressureReading`
4. Adj nevet

## 🔧 Debug Tippek

### Ha "cannot find symbol" hibát kapsz
- Ellenőrizd, hogy cast-oltad-e a bean-t
- Használj teljes osztálynevet (package-gel együtt)

### Ha "false" értéket kapsz a Bind Spring Context-nél
- Ellenőrizd az AppCtxHolder.get() kifejezést
- Győződj meg róla, hogy az alkalmazás fut

### Ha nem találja a bean-t
```java
// Ellenőrizd, hogy létezik-e
return applicationContext.containsBean("bloodPressureService");

// Listázd az összes bean-t
return java.util.Arrays.asList(applicationContext.getBeanDefinitionNames());
```

## 📌 Gyors Referencia

| Bean név | Típus | Példa használat |
|----------|-------|-----------------|
| `bloodPressureRepository` | `BloodPressureRepository` | `repo.findAll()` |
| `bloodPressureService` | `BloodPressureService` | `service.getStatistics()` |
| `dataInitializer` | `DataInitializer` | (csak dev profilban) |

## 🎯 Best Practices

1. **Mindig cast-olj vagy használj típust** a getBean()-nél
2. **Használd a teljes package nevet** az első alkalommal
3. **Import után var-t használhatsz** rövidítésnek
4. **Snapshot-olj gyakran** hogy ne veszíts adatot
5. **Használd a Spring button-t** bulk mentéshez
