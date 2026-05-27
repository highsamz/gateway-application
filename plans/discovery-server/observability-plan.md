# Plano de Observabilidade — discovery-server

---

## Estado Atual

Sem nenhuma observabilidade configurada.

---

## Health Checks (Prioritário)

O discovery-server é o serviço mais crítico para o ecossistema. O Docker Compose e Kubernetes precisam de health checks confiáveis para determinar quando o Eureka está pronto antes de subir os outros serviços.

### Dependência a adicionar

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### Configuração

```properties
management.endpoints.web.exposure.include=health,info,env
management.endpoint.health.show-details=always
```

### Endpoint de health check
```
GET /actuator/health
```

Retorna:
```json
{
  "status": "UP",
  "components": {
    "diskSpace": { "status": "UP" },
    "eureka": {
      "status": "UP",
      "details": {
        "applications": {
          "auth-service": 1,
          "member-service": 1,
          "financial-service": 1,
          "gateway-application": 1
        }
      }
    }
  }
}
```

### No docker-compose.yml

```yaml
discovery-server:
  healthcheck:
    test: ["CMD", "wget", "-qO-", "http://localhost:8761/actuator/health"]
    interval: 10s
    timeout: 5s
    retries: 5
    start_period: 30s
```

Outros serviços usam `depends_on: discovery-server: condition: service_healthy`.

---

## Logs Estruturados (Opcional para Eureka)

O Eureka produz muitos logs de heartbeat por padrão. É importante configurar o nível de log para evitar ruído:

```properties
# Reduzir verbosidade dos logs do Eureka
logging.level.com.netflix.eureka=WARN
logging.level.com.netflix.discovery=WARN
logging.level.org.springframework.cloud.netflix.eureka=INFO
```

Para logs JSON em produção:

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

```xml
<!-- logback-spring.xml -->
<springProfile name="!local">
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</springProfile>
```

---

## Alertas Críticos para o Eureka

| Situação | Alerta | Severidade |
|----------|--------|------------|
| Eureka DOWN | Todos os serviços perdem Service Discovery | 🔴 Crítico |
| Menos de 3 serviços registrados | Um serviço caiu | 🟠 Alto |
| Modo de self-preservation ativo | Eureka suspeita de falha de rede | 🟡 Médio |

### Custom Health Indicator para monitorar serviços registrados

```java
@Component
public class ServiceRegistryHealthIndicator implements HealthIndicator {

    @Autowired
    private EurekaClient eurekaClient;

    private static final List<String> EXPECTED_SERVICES = List.of(
        "auth-service", "member-service", "financial-service", "gateway-application"
    );

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        boolean allHealthy = true;

        for (String service : EXPECTED_SERVICES) {
            List<InstanceInfo> instances = eurekaClient.getInstancesByVipAddress(service, false);
            details.put(service, instances.isEmpty() ? "DOWN" : "UP (" + instances.size() + " instances)");
            if (instances.isEmpty()) allHealthy = false;
        }

        return allHealthy
            ? Health.up().withDetails(details).build()
            : Health.down().withDetails(details).build();
    }
}
```

---

## Tracing (Não Recomendado para o Eureka)

O discovery-server não processa requisições de negócio — ele apenas gerencia registros de serviços. Adicionar distributed tracing ao Eureka não agrega valor observacional e pode criar ruído nos traces do Zipkin.

**Recomendação:** Não configurar Zipkin no discovery-server.
