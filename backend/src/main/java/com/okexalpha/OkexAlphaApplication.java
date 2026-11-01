package com.okexalpha;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OkexAlphaApplication {

    public static void main(String[] args) {
        SpringApplication.run(OkexAlphaApplication.class, args);
    }
}
