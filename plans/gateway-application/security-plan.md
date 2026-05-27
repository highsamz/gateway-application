# Plano de Segurança — gateway-application

---

## Situação Atual

O gateway é o único ponto de entrada do ecossistema e atualmente não oferece nenhuma proteção. A `SecurityConfig` atual libera tudo:

```java
// src/main/java/br/com/gateway_apllication/config/SecurityConfig.java
@Configuration
public class SecurityConfig {
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .anyExchange().permitAll()
                )
                .build();
    }
}
```

**Consequência:** qualquer pessoa ou script pode chamar `/members`, `/transactions` ou criar usuários sem autenticação.

---

## [GW-01] Ativar Validação JWT no Gateway

### Por que esse problema existe
A dependência `spring-boot-starter-oauth2-resource-server` foi adicionada ao `pom.xml` mas a `SecurityConfig` nunca foi configurada para usá-la.

### Impacto técnico
Todos os microsserviços estão expostos publicamente. Dados de membros (LGPD) e transações financeiras são acessíveis sem credenciais.

### Solução

**Passo 1: Configurar a chave secreta no `application.properties`**

```properties
# Adicionar ao application.properties
spring.security.oauth2.resourceserver.jwt.secret-key=${JWT_SECRET}
```

**Passo 2: Reescrever o `SecurityConfig`**

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .pathMatchers(HttpMethod.POST, "/auth/refresh").permitAll()
                        .pathMatchers("/actuator/health").permitAll()
                        .pathMatchers(HttpMethod.POST, "/user").hasRole("PASTOR")
                        .pathMatchers("/members/**").hasAnyRole("PASTOR", "SECRETARIO")
                        .pathMatchers("/transactions/**").hasAnyRole("PASTOR", "TESOUREIRO")
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                )
                .build();
    }

    @Bean
    public ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) return Flux.empty();
            return Flux.fromIterable(roles)
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        String allowedOrigins = System.getenv().getOrDefault("CORS_ALLOWED_ORIGINS", "http://localhost:3000");
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

**Passo 3: Variáveis de ambiente necessárias**
```env
JWT_SECRET=<hex string de 256 bits — gerado com: openssl rand -hex 32>
CORS_ALLOWED_ORIGINS=http://localhost:3000,https://noiva-de-cristo.app
```

---

## [GW-02] Configurar CORS

### Por que esse problema existe
Nenhuma configuração de CORS foi implementada. O Spring Security WebFlux, sem configuração de CORS, rejeita requisições cross-origin.

### Impacto técnico
Qualquer frontend web de domínio diferente do gateway recebe erro 403 CORS antes mesmo de chegar à lógica de negócio.

### Solução
A configuração de CORS está incluída na solução do GW-01 acima (`corsConfigurationSource()`). Garantir que:

1. `CORS_ALLOWED_ORIGINS` seja configurado por ambiente (desenvolvimento vs. produção)
2. Em produção, nunca usar `*` — especificar domínios explícitos
3. `allowCredentials = true` é necessário para que o browser envie cookies de refresh token

---

## [GW-03] Propagar Identidade para Downstream

### Por que esse problema existe
O gateway não extrai os claims do JWT validado e não os propaga para os microsserviços.

### Impacto técnico
- `financial-service` usa `UUID.randomUUID()` para `createdBy` por não ter o ID do usuário
- Microsserviços não conseguem aplicar autorização por usuário específico
- Auditoria de quem fez o quê é impossível

### Solução — GatewayFilter de Propagação de Identidade

Criar nova classe `JwtPropagationFilter.java`:

```java
package br.com.gateway_apllication.filter;

@Component
public class JwtPropagationFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(principal -> principal instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .flatMap(token -> {
                    Jwt jwt = token.getToken();
                    String userId = jwt.getSubject();
                    String email = jwt.getClaimAsString("email");
                    List<String> roles = jwt.getClaimAsStringList("roles");

                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                            .header("X-User-Id", userId)
                            .header("X-User-Email", email != null ? email : "")
                            .header("X-User-Roles", roles != null ? String.join(",", roles) : "")
                            .build();

                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
```

**Headers propagados para cada microsserviço:**
- `X-User-Id` — UUID do usuário autenticado (sub do JWT)
- `X-User-Email` — email do usuário
- `X-User-Roles` — roles separadas por vírgula (ex.: `PASTOR,TESOUREIRO`)

---

## [GW-04] Circuit Breaker por Rota

### Por que esse problema existe
A dependência `resilience4j-spring-boot3` está no `pom.xml` mas nenhum `CircuitBreakerFilter` foi configurado nas rotas.

### Impacto técnico
Falha em qualquer microsserviço causa acúmulo de threads bloqueadas no gateway (efeito cascata), podendo derrubar o próprio gateway.

### Solução

**Adicionar `spring-boot-starter-webflux` como dependência do Resilience4j (WebFlux precisa do AOP reativo):**

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-reactor</artifactId>
</dependency>
```

**Configuração de Circuit Breaker em `application.yml`** (migrar de `.properties`):

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: lb://auth-service
          predicates:
            - Path=/user/**,/auth/**
          filters:
            - name: CircuitBreaker
              args:
                name: auth-service-cb
                fallbackUri: forward:/fallback/auth
                statusCodes:
                  - SERVICE_UNAVAILABLE
                  - GATEWAY_TIMEOUT

        - id: member-service
          uri: lb://member-service
          predicates:
            - Path=/members/**
          filters:
            - name: CircuitBreaker
              args:
                name: member-service-cb
                fallbackUri: forward:/fallback/members

        - id: financial-service
          uri: lb://financial-service
          predicates:
            - Path=/transactions/**
          filters:
            - name: CircuitBreaker
              args:
                name: financial-service-cb
                fallbackUri: forward:/fallback/financial

resilience4j:
  circuitbreaker:
    instances:
      auth-service-cb:
        registerHealthIndicator: true
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
      member-service-cb:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
      financial-service-cb:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
```

**Criar `FallbackController.java`:**

```java
package br.com.gateway_apllication.controller;

@RestController
public class FallbackController {

    @RequestMapping("/fallback/auth")
    public ResponseEntity<Map<String, String>> authFallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                    "error", "Auth service temporarily unavailable",
                    "message", "Please try again in a few moments"
                ));
    }

    @RequestMapping("/fallback/members")
    public ResponseEntity<Map<String, String>> memberFallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Member service temporarily unavailable"));
    }

    @RequestMapping("/fallback/financial")
    public ResponseEntity<Map<String, String>> financialFallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Financial service temporarily unavailable"));
    }
}
```

---

## [GW-05] Rate Limiting

### Solução para MVP (sem Redis — RequestRateLimiter simples)

Para o MVP, aplicar rate limiting apenas no endpoint de login (mais sensível a brute force):

```yaml
# application.yml
spring:
  cloud:
    gateway:
      routes:
        - id: auth-login-rate-limited
          uri: lb://auth-service
          predicates:
            - Path=/auth/login
            - Method=POST
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 5
                redis-rate-limiter.burstCapacity: 10
                key-resolver: "#{@ipKeyResolver}"
```

```java
@Bean
public KeyResolver ipKeyResolver() {
    return exchange -> Mono.just(
        exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
    );
}
```

> Nota: `RequestRateLimiter` com Redis requer `spring-boot-starter-data-redis-reactive` adicionado ao `pom.xml`.

---

## Remover spring-boot-devtools

A dependência atual no `pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
</dependency>
```

Deve ser alterada para:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
    <scope>runtime</scope>
    <optional>true</optional>
</dependency>
```

O Maven não inclui dependências `optional` no JAR final. Em produção, o `devtools` não estará presente.
