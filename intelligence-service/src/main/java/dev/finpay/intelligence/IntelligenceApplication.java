package dev.finpay.intelligence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"dev.finpay.intelligence", "dev.finpay.platform"})
@EnableScheduling
public class IntelligenceApplication {
    public static void main(String[] args) { SpringApplication.run(IntelligenceApplication.class, args); }
}
