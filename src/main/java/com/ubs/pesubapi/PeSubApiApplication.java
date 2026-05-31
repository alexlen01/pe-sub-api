package com.ubs.pesubapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PeSubApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(PeSubApiApplication.class, args);
    }
}
