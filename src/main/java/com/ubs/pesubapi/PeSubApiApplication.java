package com.ubs.pesubapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

// @ConfigurationPropertiesScan picks up every @ConfigurationProperties class under this package,
// so tuning values live in application.yml rather than as constants scattered through the code.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableScheduling
public class PeSubApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(PeSubApiApplication.class, args);
    }
}
