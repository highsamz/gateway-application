# gateway-application — Plano de Evolução

## Objetivo do Serviço

Ponto de entrada único do ecossistema. Responsável por roteamento, balanceamento de carga, e — quando implementado — autenticação, autorização, rate limiting e resiliência.

**Porta:** 8080 | **Stack:** Spring Cloud Gateway (WebFlux/Netty) | **Spring Boot:** 3.4.4 | **Spring Cloud:** 2024.0.0

---

## Estado Atual (confirmado pela leitura do código)

**Arquivos Java:** 2
- `br.com.gateway_apllication.GatewayApllicationApplication` — classe main
- `br.com.gateway_apllication.config.SecurityConfig` — configuração de segurança

**SecurityConfig atual (problema crítico):**
```java
return http
    .csrf(ServerHttpSecurity.CsrfSpec::disable)
    .authorizeExchange(exchanges -> exchanges
        .anyExchange().permitAll()  // TUDO LIBERADO — zero segurança
    )
    .build();
```

**Rotas configuradas:**
```properties
spring.cloud.gateway.routes[0].id=member-service
spring.cloud.gateway.routes[0].uri=lb://member-service
spring.cloud.gateway.routes[0].predicates[0]=Path=/members/**

spring.cloud.gateway.routes[1].id=auth-service
spring.cloud.gateway.routes[1].uri=lb://auth-service
spring.cloud.gateway.routes[1].predicates[0]=Path=/user/**,/auth/**

spring.cloud.gateway.routes[2].id=financial-service
spring.cloud.gateway.routes[2].uri=lb://financial-service
spring.cloud.gateway.routes[2].predicates[0]=Path=/transactions/**
```

**Dependências presentes mas não utilizadas:**
- `spring-boot-starter-oauth2-resource-server` — pronta para validar JWT
- `resilience4j-spring-boot3` — pronta para Circuit Breaker
- `spring-boot-starter-actuator` — pronto para health checks
- `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` — prontos para tracing

**Dependência problemática:**
- `spring-boot-devtools` — sem scope, nunca deve ir para produção

---

## Principais Riscos

| Risco | Severidade | Bloqueador para Produção? |
|-------|------------|--------------------------|
| Zero autenticação (permitAll) | 🔴 Crítico | Sim |
| CORS não configurado | 🔴 Crítico | Sim |
| Circuit Breaker ausente | 🟠 Alto | Recomendado |
| Rate Limiting ausente | 🟠 Alto | Recomendado |
| spring-boot-devtools em produção | 🟡 Médio | Não, mas deve remover |
| Eureka hardcoded para localhost | 🟡 Médio | Sim (Docker/K8s) |
| Zero observabilidade | 🟡 Médio | Não, mas impacta diagnóstico |

---

## Prioridades do Serviço

### Prioridade 1 — Implementar Imediatamente (Bloqueadores)

1. **[GW-01]** Ativar validação JWT via OAuth2 Resource Server
2. **[GW-02]** Configurar CORS com origens via variável de ambiente
3. **[GW-03]** Implementar GatewayFilter para propagação de identidade (`X-User-*`)
4. **[GW-04]** Configurar Circuit Breaker com Resilience4j nas 3 rotas

### Prioridade 2 — Implementar em Seguida

5. **[OBS-01]** GlobalFilter para Correlation ID
6. **[OBS-02]** Ativar Zipkin (dependência já presente)
7. **[OBS-03]** Logs estruturados JSON
8. **[GW-05]** Rate Limiting no endpoint `/auth/login`

### Prioridade 3 — Melhorias Futuras

9. Retry com backoff exponencial nas rotas
10. Headers de segurança HTTP (X-Content-Type-Options, etc.)
11. Migrar `application.properties` para `application.yml` (melhor legibilidade)
12. Métricas Prometheus via Actuator

---

## Roadmap Resumido

```
Semana 1:  GW-01 (JWT) → GW-02 (CORS) → GW-03 (X-User-*) → GW-04 (Circuit Breaker)
Semana 2:  OBS-01 (Correlation ID) → OBS-02 (Zipkin) → OBS-03 (Logs JSON)
Semana 3+: GW-05 (Rate Limiting) → Retry → Headers HTTP → Prometheus
```

---

## Impacto de Implementar a Segurança

Ao implementar GW-01 + GW-02 + GW-03:
- **auth-service:** nenhuma mudança necessária (apenas emite JWT)
- **member-service:** passa a receber `X-User-Id` e `X-User-Roles` nos headers
- **financial-service:** `createdBy` passa a ser preenchido corretamente (FIN-01)
- **discovery-server:** nenhuma mudança necessária

> Ver [security-plan.md](./security-plan.md) e [implementation-plan.md](./implementation-plan.md) para detalhes técnicos.
