package com.example.springboot.controller;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Példa REST controller, amit a REPL-ből is el lehet érni.
 * 
 * REPL-ben próbáld ki:
 * - HTTP kérések küldése
 * - Spring bean-ek elérése
 * - Adatbázis műveletek
 */
@RestController
@RequestMapping("/api")
public class ExampleController {

    private final List<String> items = new ArrayList<>();

    @GetMapping("/hello")
    public Map<String, String> hello() {
        Map<String, String> response = new HashMap<>();
        response.put("message", "Hello from Spring Boot!");
        response.put("timestamp", new Date().toString());
        return response;
    }

    @GetMapping("/items")
    public List<String> getItems() {
        return items;
    }

    @PostMapping("/items")
    public Map<String, Object> addItem(@RequestBody String item) {
        items.add(item);
        Map<String, Object> response = new HashMap<>();
        response.put("added", item);
        response.put("total", items.size());
        return response;
    }

    @GetMapping("/system")
    public Map<String, Object> systemInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("java.version", System.getProperty("java.version"));
        info.put("os.name", System.getProperty("os.name"));
        info.put("user.dir", System.getProperty("user.dir"));
        info.put("free.memory", Runtime.getRuntime().freeMemory());
        info.put("total.memory", Runtime.getRuntime().totalMemory());
        return info;
    }
}