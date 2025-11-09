# Python Debug Test Scripts

Ez a mappa Python szkripteket tartalmaz, amelyek folyamatosan frissülő adatokkal dolgoznak - ideálisak debugger teszteléshez.

## 📊 monitoring_system.py

Egy komplex monitoring rendszer, ami valós idejű szenzor adatokat szimulál.

### Futtatás
```bash
python3 monitoring_system.py
```

### Főbb komponensek
- **5 szenzor** folyamatos adatgenerálással:
  - Hőmérséklet (napi ciklus szimulációval)
  - Nyomás
  - Áramlási sebesség
  - CPU használat (spike-okkal)
  - Memória

- **Háttérszál** másodpercenként frissíti az értékeket

- **Alert rendszer** figyelmeztetésekkel és kritikus állapotokkal

- **Statisztikák és analitika** valós időben

### Debuggoláshoz hasznos változók
```python
# Aktuális értékek
system.current_readings  # Dict minden szenzor legfrissebb értékével

# Előzmények (utolsó 100 mérés/szenzor)
system.history           # Deque objektumok szenzor adatokkal

# Figyelmeztetések
system.alerts           # Utolsó 50 alert

# Statisztikák
system.statistics       # Összesített metrikák
system.calculate_analytics()  # Valós idejű elemzés
```

### Breakpoint javaslatok
- **Line 195** (`update_cycle`) - Minden másodperces frissítés
- **Line 157** (`process_reading`) - Új mérés feldolgozása
- **Line 251** (`trigger_anomaly`) - Anomália generálás
- **Line 89** (`generate_reading`) - Érték generálás logika

### Interaktív parancsok
- `s` - Státusz megjelenítése
- `a` - Analitika
- `h <sensor_id>` - Szenzor előzmények
- `t` - Anomália trigger
- `e` - Export JSON-ba
- `q` - Kilépés

## 🔄 data_processor.py

Egyszerűbb, kontrolláltabb debug célpont különböző feldolgozási forgatókönyvekkel.

### Futtatás
```bash
python3 data_processor.py
```

### Forgatókönyvek

#### 1. Batch Processing
- 3 batch feldolgozása, egyenként 5 elemmel
- Transzformációk és szűrések
- 5% random hiba szimuláció
```python
processor.current_batch  # Aktuális feldolgozás alatt álló batch
processor.results        # Összes feldolgozott elem
processor.get_summary()  # Összesített statisztikák
```

#### 2. Stream Processing
- Buffer alapú stream feldolgozás
- Automatikus feldolgozás 70%-os telítettségnél
- Checkpoint rendszer
```python
stream.buffer           # Aktuális buffer tartalom
stream.metrics         # Feldolgozási metrikák
stream.checkpoints     # Mentett állapotok
```

#### 3. Complex Data Structures
- Nested dictionary műveletek
- Számítások több szinten
```python
nested_data['level1']['level2']['values']
nested_data['calculations']
```

#### 4. Error Handling
- Különböző exception típusok
- Hibakezelési lánc
```python
results  # Minden kísérlet eredménye
```

### Breakpoint javaslatok
- **Line 67** - Batch feldolgozás előtt
- **Line 165** - Stream buffer feltöltődés
- **Line 230** - Nested struktúra vizsgálat
- **Line 247** - Hibakezelés lépésről lépésre

## 🐛 Debug tippek

### VS Code
```json
// launch.json konfiguráció
{
    "name": "Python: Current File",
    "type": "python",
    "request": "launch",
    "program": "${file}",
    "console": "integratedTerminal",
    "justMyCode": false
}
```

### PyCharm
1. Jobb klikk a fájlon → Debug
2. Breakpoint: klikk a sor számra
3. Debug panel: változók, call stack, watches

### Hasznos debug parancsok
```python
# Debug közben a console-ban:
import json
print(json.dumps(system.get_status(), indent=2))  # Monitoring

print(processor.get_summary())  # Data processor

# Változó módosítás runtime:
system.anomaly_chance = 0.5  # Több anomália
processor.processing_rules['threshold'] = 10  # Alacsonyabb küszöb
```

## 🎯 Használati példák

### 1. Hosszú futású folyamat debug
```bash
# Indítsd el a monitoring_system.py-t
# Állíts be breakpoint-ot az update_cycle-ben
# Figyeld hogyan változnak az értékek idővel
```

### 2. Hiba szimuláció
```python
# data_processor.py-ban növeld a hibaarányt:
if random.random() < 0.3:  # 30% hiba
    raise ValueError(...)
```

### 3. Memory leak teszt
```python
# monitoring_system.py-ban ne limitáld a history-t:
self.history = {sensor_id: [] for sensor_id in self.sensors}  # maxlen nélkül
```

### 4. Performance profiling
```bash
python3 -m cProfile monitoring_system.py
# vagy
python3 -m cProfile -o profile.stats monitoring_system.py
```

## 📝 Megjegyzések

- Mindkét szkript **thread-safe** és hosszú futásra optimalizált
- A `monitoring_system.py` daemon thread-et használ, így Ctrl+C-vel leállítható
- A `data_processor.py` determinisztikus, ugyanazokat az eredményeket adja újrafuttatáskor
- JSON export lehetőség későbbi elemzéshez

Jó debuggolást! 🐞