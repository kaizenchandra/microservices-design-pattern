package com.synechis.fulfillment.runtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import java.util.List;

/** Customer identity must exist before rate limiting, persistence or ownership checks. */
@Configuration
public class JwtConfiguration {
    @Bean
    JwtDecoder decoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>("aud", audiences -> audiences != null && audiences.contains("shop")),
                new JwtClaimValidator<String>("sub", subject -> subject != null && !subject.isBlank())));
        return decoder;
    }
}
