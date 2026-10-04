package dev.finpay.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"dev.finpay.wallet", "dev.finpay.platform"})
@EnableScheduling
public class WalletApplication {
    public static void main(String[] args) { SpringApplication.run(WalletApplication.class, args); }
}
