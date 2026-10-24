package it.ristomanager.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RistoManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RistoManagerApplication.class, args);
    }
}
