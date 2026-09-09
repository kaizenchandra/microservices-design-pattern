package com.synechis.fulfillment.runtime;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
@Configuration
public class SecurityConfiguration {
 @Bean SecurityFilterChain security(HttpSecurity http)throws Exception{
  return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers("/actuator/health/**","/actuator/prometheus").permitAll().requestMatchers("/admin/**").hasAuthority("SCOPE_ops").anyRequest().authenticated())
   .exceptionHandling(e->e.accessDeniedHandler((request,response,error)->problem(response,403)))
   .oauth2ResourceServer(o->o.jwt(j->{}).authenticationEntryPoint((request,response,error)->{response.setHeader("WWW-Authenticate","Bearer");problem(response,401);})).build();
 }
 private static void problem(jakarta.servlet.http.HttpServletResponse response,int status)throws java.io.IOException{response.setStatus(status);response.setContentType("application/problem+json");response.getWriter().write(com.synechis.fulfillment.contracts.Json.write(java.util.Map.of("type","about:blank","status",status,"title",status==401?"Unauthorized":"Forbidden")));}
}
