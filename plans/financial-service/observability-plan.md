# Plano de Observabilidade — financial-service

---

## Contexto

O financial-service lida com dados financeiros da congregação. A observabilidade aqui tem importância dupla: diagnóstico técnico E rastreabilidade financeira (quem fez o quê, quando).

---

## [OBS-01] Propagar Correlation ID

O gateway injeta `X-Correlation-Id`. O financial-service deve capturá-lo e incluir em todos os logs.

```java
@Component
public class CorrelationIdMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
            MDC.put("userId", request.getHeader("X-User-Id"));
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
```

Incluir `userId` no MDC também — assim cada log de transação financeira tem o userId automaticamente.

---

## [OBS-03] Logs Estruturados JSON

### Dependência

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

### Configuração logback-spring.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProfile name="!local">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>correlationId</includeMdcKeyName>
                <includeMdcKeyName>userId</includeMdcKeyName>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON"/>
        </root>
    </springProfile>
    <springProfile name="local">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss} [%X{correlationId}] [user:%X{userId}] %-5level %logger{25} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="DEBUG">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>
</configuration>
```

---

## Logs de Auditoria Financeira

O `TransactionService` deve logar operações financeiras com nível INFO:

```java
// TransactionService.create()
log.info("transaction.created id={} type={} category={} amount={} createdBy={}",
    saved.getId(),
    saved.getType(),
    saved.getCategory(),
    saved.getAmount(),
    saved.getCreatedBy());

// TransactionService.delete()
log.warn("transaction.deleted id={} deletedBy={}",
    id,
    userId);  // do header X-User-Id
```

### Nunca logar valores completos de amount em debug
Em `DEBUG`, pode-se logar estrutura sem valores sensíveis. Em `INFO`, logar valores de transação é aceitável para auditoria financeira (não são dados pessoais sensíveis como CPF).

---

## [OBS-02] Distributed Tracing

### Dependências a adicionar

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

### Configuração

```properties
management.tracing.sampling.probability=1.0
management.zipkin.tracing.endpoint=${ZIPKIN_URL:http://localhost:9411}/api/v2/spans
```

---

## Health Checks

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```properties
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always
```

O health check verifica automaticamente:
- Conectividade com o PostgreSQL (`financialdb`)
- Possibilidade futura de verificar conectividade com MinIO

### Custom Health Indicator para MinIO

```java
@Component
public class MinioHealthIndicator implements HealthIndicator {

    @Autowired(required = false)
    private MinioClient minioClient;

    @Value("${minio.bucket-name:financial-attachments}")
    private String bucketName;

    @Override
    public Health health() {
        if (minioClient == null) {
            return Health.unknown().withDetail("minio", "not configured").build();
        }
        try {
            minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            return Health.up().withDetail("minio", "connected").build();
        } catch (Exception e) {
            return Health.down().withDetail("minio", e.getMessage()).build();
        }
    }
}
```

---

## Alertas Recomendados

Para o financial-service, configurar alertas em:

| Métrica | Threshold | Severidade |
|---------|-----------|------------|
| Taxa de erro em `/transactions` | > 5% em 5 min | Alto |
| Latência de `POST /transactions` | > 2s p95 | Médio |
| `calculateBalance` > 5s | qualquer | Alto (indica crescimento da tabela) |
| Upload de arquivo falha | > 10% | Médio |
| Conexão com PostgreSQL falha | qualquer | Crítico |
| Conexão com MinIO falha | qualquer | Médio |
