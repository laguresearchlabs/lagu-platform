package com.lagu.platform.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
@Slf4j
public class ServiceSecurityConfig {

    /**
     * Well-known placeholder shipped in dev configs. Never a valid production secret: startup
     * fails if it is still in effect unless {@code platform.gateway.allow-insecure-default=true}
     * (set only in docker-compose and the "loc" profile).
     */
    public static final String INSECURE_DEFAULT_SECRET =
            "CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD";

    @Bean
    public GatewayHeaderFilter gatewayHeaderFilter(
            @Value("${platform.gateway.shared-secret:" + INSECURE_DEFAULT_SECRET + "}")
            String gatewaySharedSecret,
            @Value("${platform.gateway.allow-insecure-default:false}")
            boolean allowInsecureDefault) {

        boolean insecure = gatewaySharedSecret == null
                || gatewaySharedSecret.isBlank()
                || INSECURE_DEFAULT_SECRET.equals(gatewaySharedSecret);
        if (insecure) {
            if (!allowInsecureDefault) {
                throw new IllegalStateException(
                        "platform.gateway.shared-secret is unset or still the well-known default. " +
                        "Anyone who can reach this service directly could forge identity headers " +
                        "with it. Set PLATFORM_GATEWAY_SHARED_SECRET to a strong random value, or " +
                        "set platform.gateway.allow-insecure-default=true for local development only.");
            }
            log.warn("Running with the INSECURE default gateway shared secret " +
                    "(platform.gateway.allow-insecure-default=true). Never enable this in production.");
        }
        return new GatewayHeaderFilter(gatewaySharedSecret);
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
