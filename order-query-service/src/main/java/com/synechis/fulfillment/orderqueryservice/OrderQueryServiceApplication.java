package com.synechis.fulfillment.orderqueryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.synechis.fulfillment.orderqueryservice", "com.synechis.fulfillment.runtime"})
public class OrderQueryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderQueryServiceApplication.class, args);
    }
}
