package com.example.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Példa Spring Boot alkalmazás nREPL szerverrel.
 * 
 * Az nREPL szerver automatikusan elindul az alkalmazással együtt,
 * ha az nrepl.enabled=true az application.properties-ben.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.example.springboot", "com.example.springboot.nrepl"})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        
        System.out.println("\n========================================");
        System.out.println("Spring Boot application started!");
        System.out.println("You can now connect to the nREPL server");
        System.out.println("from IntelliJ IDEA's Java REPL tool.");
        System.out.println("========================================\n");
    }
}