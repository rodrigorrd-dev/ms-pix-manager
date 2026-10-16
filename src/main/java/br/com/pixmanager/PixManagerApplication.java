package br.com.pixmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PixManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PixManagerApplication.class, args);
    }
}
