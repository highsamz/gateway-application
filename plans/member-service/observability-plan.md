# Plano de Observabilidade — member-service

---

## Estado Atual

Provavelmente apenas o Eureka client configurado. Sem Actuator, sem logs estruturados, sem tracing.

---

## [OBS-01] Propagar Correlation ID via MDC

```java
@Component
public class CorrelationIdMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader("X-Correlation-Id");
        String userId = request.getHeader("X-User-Id");

        if (correlationId != null) MDC.put("correlationId", correlationId);
        if (userId != null) MDC.put("userId", userId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
```

---

## [OBS-03] Logs Estruturados JSON

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

**logback-spring.xml:**
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
                <pattern>%d{HH:mm:ss} [%X{correlationId}] %-5level %logger{25} - %msg%n</pattern>
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

---

## [OBS-02] Distributed Tracing

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

```properties
management.tracing.sampling.probability=1.0
management.zipkin.tracing.endpoint=${ZIPKIN_URL:http://localhost:9411}/api/v2/spans
```

---

## Logs de Operação de Membros

```java
// MemberServiceImpl.java
@Override
public MemberResponseDTO create(MemberRequestDTO dto) {
    Member member = memberMapper.toEntity(dto);
    Member saved = memberRepository.save(member);
    log.info("member.created id={} nome={} igreja={} createdBy={}",
        saved.getId(),
        saved.getNome(),
        saved.getIgreja(),
        MDC.get("userId"));  // do header X-User-Id
    return memberMapper.toResponse(saved);
}

@Override
public void delete(UUID id) {
    Member member = memberRepository.findById(id)
        .orElseThrow(() -> new MemberNotFoundException(id));
    member.setStatus(Status.INATIVO);
    memberRepository.save(member);
    log.info("member.deactivated id={} nome={} deactivatedBy={}",
        id, member.getNome(), MDC.get("userId"));
}
```

> IMPORTANTE: Nunca logar CPF, RG ou email completo nos logs operacionais (LGPD).

---

## Swagger/OpenAPI — Aproveitar o Diferencial

O member-service já tem `springdoc-openapi` — o único serviço do ecossistema com isso.

Melhorar a documentação existente:

```java
// MemberController.java
@Operation(summary = "Criar novo membro", description = "Cadastra um membro ativo na congregação")
@ApiResponse(responseCode = "201", description = "Membro criado")
@ApiResponse(responseCode = "400", description = "Dados inválidos")
@ApiResponse(responseCode = "409", description = "CPF ou email já cadastrado")
@PostMapping
public ResponseEntity<MemberResponseDTO> create(@Valid @RequestBody MemberRequestDTO request) {
    // ...
}
```

Adicionar informações de autenticação na documentação:
```java
// SwaggerConfig.java
@Bean
public OpenAPI customOpenAPI() {
    return new OpenAPI()
        .info(new Info().title("Member Service API").version("1.0"))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
        .components(new Components().addSecuritySchemes("bearerAuth",
            new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
}
```
