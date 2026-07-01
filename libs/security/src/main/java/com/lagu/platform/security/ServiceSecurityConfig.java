package com.lagu.platform.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class ServiceSecurityConfig {

    @Bean
    public GatewayHeaderFilter gatewayHeaderFilter() {
        return new GatewayHeaderFilter();
    }

    /** Prevents Spring Boot from also registering the filter as a raw servlet filter. */
    @Bean
    public FilterRegistrationBean<GatewayHeaderFilter> gatewayHeaderFilterRegistration(
            GatewayHeaderFilter filter) {
        FilterRegistrationBean<GatewayHeaderFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    GatewayHeaderFilter gatewayHeaderFilter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(gatewayHeaderFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(req -> {
                            String p = req.getServletPath();
                            return p.startsWith("/swagger-ui") ||
                                   p.startsWith("/v3/api-docs") ||
                                   p.equals("/actuator/health") ||
                                   p.equals("/actuator/info") ||
                                   p.equals("/actuator/prometheus");
                        }).permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}
