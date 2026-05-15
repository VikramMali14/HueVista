package com.gridstore.huevista;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HueVistaApplication {

    public static void main(String[] args) {
        SpringApplication.run(HueVistaApplication.class, args);
    }

}
