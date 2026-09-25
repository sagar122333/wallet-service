package com.slice.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point.
 *
 * <p>{@code @EnableRetry} activates the {@code @Retryable} annotations on the write services,
 * which recover from InnoDB deadlocks and lock-wait timeouts. {@code @EnableScheduling} drives
 * the idempotency-record sweeper. Both are here rather than scattered across
 * {@code @Configuration} classes so that everything the application turns on is visible in one
 * place.
 */
@SpringBootApplication
@EnableRetry
@EnableScheduling
public class WalletServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }
}
