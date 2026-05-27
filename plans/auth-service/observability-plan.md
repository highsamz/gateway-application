# Plano de Observabilidade — auth-service

---

## Estado Atual

Nenhuma observabilidade configurada. Logs em formato texto livre padrão do Spring.

---

## [OBS-03] Logs Estruturados JSON

### Dependência a adicionar

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

### logback-spring.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProfile name="!local">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>correlationId</includeMdcKeyName>
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

## [OBS-01] Propagar Correlation ID

O gateway injeta `X-Correlation-Id` em todas as requisições. O auth-service deve propagar esse header nos logs.

### Filtro para capturar o Correlation ID

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
            response.addHeader("X-Correlation-Id", correlationId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }
}
```

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

## Logs de Segurança — O que e Como Logar

Para o auth-service, que lida com autenticação, a estratégia de logs tem atenção especial a segurança:

### Logar (com nível INFO/WARN)
- Login bem-sucedido: email mascarado (`pa****@noiva.com`), timestamp, IP
- Login falhado: motivo genérico ("INVALID_CREDENTIALS"), IP, timestamp
- Criação de usuário: email mascarado, role, quem criou
- Desativação de conta: email mascarado, quem desativou

### NUNCA logar
- Senha (nem hash)
- JWT completo (o token é uma credencial)
- CPF ou dados sensíveis

### Exemplo de log de login

```java
// AuthService.login()
log.info("Login attempt email={} success={} ip={}",
    maskEmail(request.email()),
    success,
    getClientIp(httpRequest));
```

```java
private String maskEmail(String email) {
    int atIndex = email.indexOf('@');
    if (atIndex <= 2) return "***" + email.substring(atIndex);
    return email.substring(0, 2) + "****" + email.substring(atIndex);
}
```

---

## Health Checks

### Dependência (adicionar se não presente)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### Configuração

```properties
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always
```

O health check automático do Spring Boot verifica a conectividade com o PostgreSQL via `DataSourceHealthIndicator`.

---

## Auditoria via login_audit (AUTH-03)

A tabela `login_audit` (ver [implementation-plan.md](./implementation-plan.md)) serve como log de auditoria persistente para:
- Detecção de brute force (muitas tentativas falhas para o mesmo email)
- Investigação de acesso suspeito (login de IP incomum)
- Compliance e rastreabilidade
