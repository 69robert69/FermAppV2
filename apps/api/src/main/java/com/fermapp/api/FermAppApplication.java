package com.fermapp.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point of the FermApp API. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FermAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(FermAppApplication.class, args);
    }
}
