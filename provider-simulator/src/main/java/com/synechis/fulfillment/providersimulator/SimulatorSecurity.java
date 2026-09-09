package com.synechis.fulfillment.providersimulator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SimulatorSecurity {
    @Bean
    @Order(0)
    SecurityFilterChain simulatorFilterChain(HttpSecurity h) throws Exception {
        return h.securityMatcher("/sim/**").csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }
}
