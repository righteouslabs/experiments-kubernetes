package com.demo.lattice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LatticeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LatticeApplication.class, args);
    }
}
