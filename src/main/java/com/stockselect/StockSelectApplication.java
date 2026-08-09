package com.stockselect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StockSelectApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockSelectApplication.class, args);
    }
}
