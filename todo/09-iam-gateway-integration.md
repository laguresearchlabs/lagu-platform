# IAM & Gateway Integration

## How Identity Flows into lagu-platform

```
Client → Bearer JWT → gateway-service
                          │
                          ├── Validates JWT signature (public key from iam-services/authservice)
                          ├── Extracts: userId, orgId, roles, email
                          ├── Adds trusted headers to downstream request:
                          │       X-User-Id:    <userId>
                          │       X-Org-Id:     <orgId>
                          │       X-User-Roles: <comma-separated roles>
                          │       X-User-Email: <email>
                          └── Strips Authorization header (platform services don't see the JWT)
```

**Platform services trust these headers unconditionally.** They never re-validate the JWT.
This is the standard pattern for a gateway-authenticated microservice mesh.

---

## Gateway Routes to Add (gateway-service config)

In `gateway-service/src/main/resources/application.yml`, add routes for lagu-platform services:

```yaml
spring:
  cloud:
    gateway:
      routes:

        # ── Metadata Service ────────────────────────────────────────────────
        - id: metadata-service
          uri: lb://metadata-service
          predicates:
            - Path=/platform/metadata/**
          filters:
            - StripPrefix=2
            - name: CircuitBreaker
              args:
                name: metadataServiceCB
                fallbackUri: forward:/fallback/service-unavailable
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 100
                redis-rate-limiter.burstCapacity: 200

        # ── Record Service ──────────────────────────────────────────────────
        - id: record-service
          uri: lb://record-service
          predicates:
            - Path=/platform/records/**
          filters:
            - StripPrefix=2
            - name: CircuitBreaker
              args:
                name: recordServiceCB
                fallbackUri: forward:/fallback/service-unavailable

        # ── Workflow Service ────────────────────────────────────────────────
        - id: workflow-service
          uri: lb://workflow-service
          predicates:
            - Path=/platform/workflow/**
          filters:
            - StripPrefix=2

        # ── Search Service ──────────────────────────────────────────────────
        - id: search-service
          uri: lb://search-service
          predicates:
            - Path=/platform/search/**
          filters:
            - StripPrefix=2

        # ── Automation Service ──────────────────────────────────────────────
        - id: automation-service
          uri: lb://automation-service
          predicates:
            - Path=/platform/automation/**
          filters:
            - StripPrefix=2
```

`lb://metadata-service` uses Eureka load balancing — gateway resolves via the existing
registry-service.

---

## libs/security: PlatformSecurityContext

```java
// PlatformSecurityContext.java
@Data
@Builder
public class PlatformSecurityContext {
    private UUID        userId;
    private UUID        orgId;
    private Set<String> roles;
    private String      userEmail;

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public boolean hasAnyRole(String... checkRoles) {
        if (roles == null) return false;
        return Arrays.stream(checkRoles).anyMatch(roles::contains);
    }

    public boolean isPlatformAdmin() {
        return hasRole("PLATFORM_ADMIN");
    }

    public boolean isConfigAdmin() {
        return hasRole("CONFIG_ADMIN") || isPlatformAdmin();
    }
}
```

```java
// GatewayHeaderFilter.java — OncePerRequestFilter in libs/security
@Component
public class GatewayHeaderFilter extends OncePerRequestFilter {

    private static final String HEADER_USER_ID    = "X-User-Id";
    private static final String HEADER_ORG_ID     = "X-Org-Id";
    private static final String HEADER_USER_ROLES = "X-User-Roles";
    private static final String HEADER_USER_EMAIL = "X-User-Email";

    private static final ThreadLocal<PlatformSecurityContext> CONTEXT_HOLDER = new ThreadLocal<>();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        try {
            String userIdHeader = req.getHeader(HEADER_USER_ID);
            String orgIdHeader  = req.getHeader(HEADER_ORG_ID);
            String rolesHeader  = req.getHeader(HEADER_USER_ROLES);
            String emailHeader  = req.getHeader(HEADER_USER_EMAIL);

            if (userIdHeader != null) {
                PlatformSecurityContext ctx = PlatformSecurityContext.builder()
                    .userId(UUID.fromString(userIdHeader))
                    .orgId(orgIdHeader != null ? UUID.fromString(orgIdHeader) : null)
                    .roles(rolesHeader != null
                        ? new HashSet<>(Arrays.asList(rolesHeader.split(",")))
                        : Collections.emptySet())
                    .userEmail(emailHeader)
                    .build();
                CONTEXT_HOLDER.set(ctx);

                // Also wire into Spring Security context as an Authentication
                UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(ctx, null,
                        ctx.getRoles().stream()
                           .map(SimpleGrantedAuthority::new)
                           .toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(req, res);
        } finally {
            CONTEXT_HOLDER.remove();
        }
    }

    public static PlatformSecurityContext current() {
        return CONTEXT_HOLDER.get();
    }
}
```

Each service includes `libs/security` and registers `GatewayHeaderFilter` as a bean.

---

## Spring Security Config (each platform service)

```java
@Configuration
@EnableMethodSecurity
public class ServiceSecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           GatewayHeaderFilter gatewayHeaderFilter) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .addFilterBefore(gatewayHeaderFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .build();
    }
}
```

No OAuth2 resource server configuration — the gateway handles JWT. Platform services only
check for the presence of the `X-User-Id` header (set by GatewayHeaderFilter).

---

## Service-to-Service Communication

Some services call others internally (record-service → metadata-service, etc.).

**Internal calls do not go through the gateway.** They use Eureka (`lb://service-name`)
directly. For these calls, pass a service identity header instead of user context:

```java
@Bean
public RestClient metadataRestClient(LoadBalancerClient lbClient) {
    return RestClient.builder()
        .baseUrl("http://metadata-service")   // Eureka resolves
        .defaultHeader("X-Internal-Service", "record-service")
        .requestFactory(clientHttpRequestFactory())
        .build();
}
```

In metadata-service, allow `X-Internal-Service` header to bypass auth for schema lookups:

```java
.requestMatchers(r -> "record-service".equals(r.getHeader("X-Internal-Service")))
    .permitAll()
```

Alternatively (more secure): use mTLS or a shared internal API key from environment config.

---

## Eureka Registration (each platform service)

```yaml
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}
    register-with-eureka: true
    fetch-registry: true
  instance:
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${server.port}
    health-check-url-path: /actuator/health
```

The existing `registry-service` (port 8761) is the Eureka server.

---

## Security Rules Matrix

| Endpoint prefix           | Platform roles     | Notes                                  |
|---------------------------|--------------------|----------------------------------------|
| `POST /attributes`        | CONFIG_ADMIN+      | Create attribute definition            |
| `GET /attributes`         | ORG_USER+          | Read-only, any org member              |
| `POST /object-types`      | CONFIG_ADMIN+      | Schema definition                      |
| `POST /records`           | ORG_USER+          | Any org member can create records      |
| `DELETE /records/{id}`    | ORG_MANAGER+       | Manager can delete                     |
| `POST /records/{id}/status` | depends on workflow | Checked per-transition allowed_roles|
| `POST /approvals/{id}/decide` | approver role only | Checked per approval step         |
| `POST /triggers`          | CONFIG_ADMIN+      | Automation setup                       |
| `POST /admin/reindex`     | PLATFORM_ADMIN     | Search admin                           |

---

## CORS (gateway-service handles all CORS)

Do not configure CORS in platform services. The gateway-service already handles CORS with
its existing configuration. Platform services are internal — clients never call them directly.

---

## Integration Checklist

- [ ] Add lagu-platform routes to gateway-service `application.yml`
- [ ] Implement `PlatformSecurityContext` in `libs/security`
- [ ] Implement `GatewayHeaderFilter` in `libs/security`
- [ ] Implement `ServiceSecurityConfig` template (wire into each service)
- [ ] Configure Eureka client in each service's `application.yml`
- [ ] Configure internal service-to-service RestClient
- [ ] Verify gateway routes after starting metadata-service with Eureka
- [ ] Test end-to-end: client JWT → gateway → metadata-service → record returned with org scoping
- [ ] Verify `PLATFORM_ADMIN` role bypasses org-scoping for admin operations
