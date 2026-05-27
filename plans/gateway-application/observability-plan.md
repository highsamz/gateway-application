# Plano de Observabilidade — gateway-application

---

## Estado Atual

Dependências presentes mas completamente não configuradas:
- `micrometer-tracing-bridge-brave` — bridge para Brave tracing
- `zipkin-reporter-brave` — reporter para Zipkin
- `spring-boot-starter-actuator` — health checks e métricas

Não há:
- Logs estruturados
- Correlation ID
- Distributed Tracing configurado
- Exportação de métricas

---

## Stack de Observabilidade Alvo

```
┌─────────────────────────────────────────────────────┐
│  gateway-application                                 │
│                                                      │
│  Logs JSON (Logback) ──────────────► Loki / ELK     │
│  Traces (Brave/Zipkin) ────────────► Zipkin UI       │
│  Métricas (Micrometer) ────────────► Prometheus      │
│  Correlation ID (GlobalFilter) ────► todos os serviços│
└─────────────────────────────────────────────────────┘
```

---

## [OBS-01] Correlation ID GlobalFilter

### Problema
Sem Correlation ID, é impossível rastrear uma requisição através dos logs do gateway, auth-service, member-service e financial-service.

### Implementação

**Arquivo novo:** `src/main/java/br/com/gateway_apllication/filter/CorrelationIdFilter.java`

```java
package br.com.gateway_apllication.filter;

@Component
@Slf4j
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = Optional.ofNullable(
                exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER))
                .filter(s -> !s.isBlank())
                .orElse(UUID.randomUUID().toString());

        return chain.filter(
                exchange.mutate()
                        .request(r -> r.header(CORRELATION_ID_HEADER, correlationId))
                        .build()
        ).then(Mono.fromRunnable(() -> {
            exchange.getResponse().getHeaders()
                    .add(CORRELATION_ID_HEADER, correlationId);
            log.debug("Request {} {} correlationId={} status={}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(),
                    correlationId,
                    exchange.getResponse().getStatusCode());
        }));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
```

Cada microsserviço downstream deve propagar o `X-Correlation-Id` em todos os seus logs:
```java
// Em cada serviço downstream (interceptor ou filter)
MDC.put("correlationId", request.getHeader("X-Correlation-Id"));
```

---

## [OBS-02] Ativar Distributed Tracing (Zipkin)

### Dependências já presentes no pom.xml
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
```

### Configuração (adicionar ao application.yml)

```yaml
management:
  tracing:
    sampling:
      probability: 1.0  # 100% em dev; usar 0.1 em produção com alto volume
  zipkin:
    tracing:
      endpoint: ${ZIPKIN_URL:http://localhost:9411}/api/v2/spans
```

### Adicionar ao docker-compose.yml

```yaml
zipkin:
  image: openzipkin/zipkin:latest
  ports:
    - "9411:9411"
```

**Verificação:** Após configurar, abrir `http://localhost:9411` e buscar pelo service `gateway-application`. Cada requisição deve aparecer com todos os spans.

---

## [OBS-03] Logs Estruturados JSON

### Dependência a adicionar no pom.xml

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

### Arquivo de configuração

**Criar:** `src/main/resources/logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProfile name="!local">
        <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>correlationId</includeMdcKeyName>
                <includeMdcKeyName>traceId</includeMdcKeyName>
                <includeMdcKeyName>spanId</includeMdcKeyName>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON_CONSOLE"/>
        </root>
    </springProfile>

    <springProfile name="local">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} [%X{correlationId}] - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="DEBUG">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>
</configuration>
```

---

## Health Checks com Actuator

### Configuração (adicionar ao application.yml)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  health:
    circuitbreakers:
      enabled: true
```

**Endpoints disponíveis:**
- `GET /actuator/health` — status geral + estado dos circuit breakers
- `GET /actuator/health/liveness` — Kubernetes liveness probe
- `GET /actuator/health/readiness` — Kubernetes readiness probe

---

## Métricas Prometheus (OBS-04 — Futuro)

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
management:
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: gateway-application
```

**Métricas mais importantes para o gateway:**
- `spring.cloud.gateway.requests` — throughput por rota
- `resilience4j.circuitbreaker.state` — estado dos circuit breakers
- `http.server.requests` — latência por endpoint

---

## Estratégia de Troubleshooting

```
1. Usuário reporta erro
2. Buscar X-Correlation-Id no header da resposta de erro
3. Buscar o correlationId nos logs centralizados
4. Identificar em qual serviço o erro ocorreu
5. Verificar span no Zipkin para latência
6. Verificar estado do circuit breaker via Actuator
7. Checar métricas de erro no Grafana
```
