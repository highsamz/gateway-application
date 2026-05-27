# Plano de Implementação — gateway-application

> Sequência ordenada de todas as implementações. Respeitar a ordem — algumas tasks dependem de anteriores.

---

## Dependências entre Tasks

```
AUTH-01 (auth-service gera JWT)
    └── GW-01 (gateway valida JWT)
            └── GW-03 (gateway propaga X-User-*)
                    └── FIN-01 (financial usa X-User-Id)

GW-02 (CORS) ← independente, fazer primeiro
GW-04 (Circuit Breaker) ← independente
OBS-01 (Correlation ID) ← independente
```

---

## Task 1 — [GW-02] Configurar CORS

**Pré-requisito:** Nenhum — fazer primeiro pois não depende de nada.

**Esforço:** 2-4 horas

**Problema:** Sem CORS, o frontend recebe erro 403 antes de qualquer lógica de negócio.

**Arquivos a modificar:**
- `src/main/java/br/com/gateway_apllication/config/SecurityConfig.java` — adicionar `corsConfigurationSource()`
- `src/main/resources/application.properties` — adicionar `CORS_ALLOWED_ORIGINS`

**Verificação:** Fazer request OPTIONS de `http://localhost:3000` para `http://localhost:8080/auth/login` e confirmar headers `Access-Control-Allow-Origin` na resposta.

---

## Task 2 — [GW-01] Ativar Validação JWT

**Pré-requisito:** AUTH-01 implementado no auth-service (JWT sendo gerado).

**Esforço:** 4-8 horas

**Problema:** `anyExchange().permitAll()` deixa tudo exposto.

**Mudanças no `pom.xml`:** Nenhuma — `spring-boot-starter-oauth2-resource-server` já está presente.

**Mudanças no `SecurityConfig.java`:**

```java
// Substituir o método springSecurityFilterChain
@Bean
public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
    return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeExchange(exchanges -> exchanges
                    .pathMatchers(HttpMethod.POST, "/auth/login").permitAll()
                    .pathMatchers(HttpMethod.POST, "/auth/refresh").permitAll()
                    .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                    .pathMatchers("/fallback/**").permitAll()
                    .pathMatchers(HttpMethod.POST, "/user").hasRole("PASTOR")
                    .pathMatchers(HttpMethod.DELETE, "/user/email/**").hasRole("PASTOR")
                    .pathMatchers(HttpMethod.DELETE, "/members/**").hasRole("PASTOR")
                    .pathMatchers("/members/**").hasAnyRole("PASTOR", "SECRETARIO")
                    .pathMatchers(HttpMethod.DELETE, "/transactions/**").hasRole("PASTOR")
                    .pathMatchers("/transactions/**").hasAnyRole("PASTOR", "TESOUREIRO")
                    .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 ->
                    oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            .build();
}
```

**Mudança no `application.properties`:**
```properties
spring.security.oauth2.resourceserver.jwt.secret-key=${JWT_SECRET}
```

**Variável de ambiente obrigatória:**
```
JWT_SECRET=<mesmo valor configurado no auth-service>
```

**Verificação:**
1. Chamar `GET /members` sem token → deve retornar 401
2. Fazer login → obter token → chamar `GET /members` com `Authorization: Bearer <token>` → deve retornar 200
3. Chamar `POST /user` com token de SECRETARIO → deve retornar 403

---

## Task 3 — [GW-03] Propagar Identidade

**Pré-requisito:** GW-01 implementado.

**Esforço:** 4-8 horas

**Problema:** Microsserviços não sabem quem é o usuário autenticado.

**Novo arquivo:** `src/main/java/br/com/gateway_apllication/filter/JwtPropagationFilter.java`

```java
package br.com.gateway_apllication.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class JwtPropagationFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(p -> p instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .flatMap(token -> {
                    var jwt = token.getToken();
                    var mutatedRequest = exchange.getRequest().mutate()
                            .header("X-User-Id", jwt.getSubject())
                            .header("X-User-Email", jwt.getClaimAsString("email") != null
                                    ? jwt.getClaimAsString("email") : "")
                            .header("X-User-Roles", String.join(",",
                                    jwt.getClaimAsStringList("roles") != null
                                    ? jwt.getClaimAsStringList("roles") : List.of()))
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

**Verificação:** Adicionar log temporário no `TransactionController` do financial-service para confirmar que `X-User-Id` chega com o UUID correto do JWT.

---

## Task 4 — [GW-04] Circuit Breaker

**Pré-requisito:** Nenhum — implementar em paralelo com GW-01.

**Esforço:** 4-8 horas

**Problema:** Sem fallback, falha em downstream trava threads do gateway.

**Mudança no `pom.xml`:** Adicionar suporte reativo ao Resilience4j:
```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-reactor</artifactId>
</dependency>
```

**Migrar `application.properties` para `application.yml`** para melhor suporte a configuração hierárquica:

```yaml
spring:
  application:
    name: gateway-application
  main:
    web-application-type: reactive
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
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
            - name: Retry
              args:
                retries: 2
                statuses: BAD_GATEWAY,SERVICE_UNAVAILABLE
                methods: GET
                backoff:
                  firstBackoff: 100ms
                  maxBackoff: 500ms

        - id: member-service
          uri: lb://member-service
          predicates:
            - Path=/members/**
          filters:
            - name: CircuitBreaker
              args:
                name: member-service-cb
                fallbackUri: forward:/fallback/members
            - name: Retry
              args:
                retries: 2
                statuses: BAD_GATEWAY,SERVICE_UNAVAILABLE
                methods: GET

        - id: financial-service
          uri: lb://financial-service
          predicates:
            - Path=/transactions/**
          filters:
            - name: CircuitBreaker
              args:
                name: financial-service-cb
                fallbackUri: forward:/fallback/financial

eureka:
  instance:
    prefer-ip-address: true
    hostname: localhost
  client:
    service-url:
      defaultZone: http://${EUREKA_USER:eureka}:${EUREKA_PASSWORD:changeme}@${EUREKA_HOST:localhost}:8761/eureka

resilience4j:
  circuitbreaker:
    instances:
      auth-service-cb:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
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

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers
  endpoint:
    health:
      show-details: always
  tracing:
    sampling:
      probability: 1.0
```

**Novo arquivo:** `src/main/java/br/com/gateway_apllication/controller/FallbackController.java`

```java
package br.com.gateway_apllication.controller;

@RestController
public class FallbackController {

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<Map<String, Object>> fallback(@PathVariable String service) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
            "error", "SERVICE_UNAVAILABLE",
            "message", service + " service is temporarily unavailable. Please try again later.",
            "timestamp", Instant.now().toString()
        ));
    }
}
```

**Verificação:** Parar o `member-service` → chamar `GET /members` → deve retornar 503 com corpo JSON (não erro de conexão).

---

## Task 5 — [OBS-01] Correlation ID GlobalFilter

**Pré-requisito:** Nenhum.

**Esforço:** 4 horas

**Novo arquivo:** `src/main/java/br/com/gateway_apllication/filter/CorrelationIdFilter.java`

```java
package br.com.gateway_apllication.filter;

@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders()
                .getFirst(CORRELATION_ID_HEADER);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        final String finalCorrelationId = correlationId;
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(CORRELATION_ID_HEADER, finalCorrelationId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .then(Mono.fromRunnable(() ->
                    exchange.getResponse().getHeaders().add(CORRELATION_ID_HEADER, finalCorrelationId)
                ));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
```

**Verificação:** Fazer qualquer request e confirmar `X-Correlation-Id` nos headers da resposta.

---

## Resumo — Novos Arquivos a Criar

| Arquivo | Task |
|---------|------|
| `config/SecurityConfig.java` (reescrever) | GW-01 + GW-02 |
| `filter/JwtPropagationFilter.java` (novo) | GW-03 |
| `filter/CorrelationIdFilter.java` (novo) | OBS-01 |
| `controller/FallbackController.java` (novo) | GW-04 |
| `application.yml` (substituir .properties) | GW-04 |

**Total de arquivos novos/modificados:** 5 (além do `pom.xml`)
