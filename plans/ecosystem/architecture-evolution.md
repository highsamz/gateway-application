# Evolução Arquitetural — Ecossistema Noiva de Cristo

> Planejamento da evolução da arquitetura atual (microsserviços REST síncronos) para uma arquitetura mais resiliente e escalável.

---

## Estado Atual da Arquitetura

```
Estilo:        Microsserviços REST síncronos
Comunicação:   HTTP exclusivamente (sem mensageria)
Descoberta:    Eureka (Netflix OSS)
Gateway:       Spring Cloud Gateway (WebFlux/Netty)
Segurança:     Inexistente (anyExchange().permitAll())
Observabilidade: Zero (dependências presentes, não configuradas)
Infraestrutura: Local apenas (sem Docker Compose completo)
```

### Pontos de Pressão Arquitetural Atuais

1. **Ausência de resiliência:** Falha em qualquer microsserviço derruba o gateway sem fallback
2. **Acoplamento temporal:** Todas as operações são síncronas — indisponibilidade cascateia
3. **SPOF no Eureka:** Uma instância única — queda causa perda de Service Discovery
4. **Versões divergentes:** Spring Boot 3.3.2 → 3.5.9 entre serviços
5. **Configuração distribuída:** `application.properties` independentes por serviço

---

## Fase 1 — Estabilização (0–2 meses)

**Objetivo:** Tornar a arquitetura atual segura e estável sem mudanças estruturais grandes.

### Mudanças Arquiteturais

**1. Ativar Camada de Segurança**
- auth-service passa a emitir JWT (JJWT 0.12.x)
- gateway valida JWT via OAuth2 Resource Server (dependência já presente)
- gateway propaga identidade via headers `X-User-*`

**2. Ativar Resiliência Básica**
- Circuit Breaker por rota no gateway (Resilience4j — dependência já presente)
- Timeout por rota (ex.: 5s para `/transactions/**`)
- Retry com backoff exponencial para erros 5xx em GETs

**3. Centralizar Configuração por Ambiente**
- Todas as credenciais migram para variáveis de ambiente
- `docker-compose.yml` com `env_file` para valores sensíveis
- `.env.example` comitado como documentação

**4. Unificar Versões**
- Criar parent POM em `ecosystem-parent/pom.xml`
- Todos os serviços herdam Spring Boot 3.4.4 + Spring Cloud 2024.0.0
- Garante comportamento consistente de serialização e Eureka

---

## Fase 2 — Observabilidade (2–4 meses)

**Objetivo:** Visibilidade completa do ecossistema em tempo real.

### Stack de Observabilidade

```
Logs      → Logback JSON → Loki / ELK Stack
Traces    → OpenTelemetry → Zipkin / Jaeger
Métricas  → Micrometer → Prometheus → Grafana
Alertas   → Alertmanager → Email / PagerDuty
```

### Correlation ID

Implementar `GlobalFilter` no gateway que:
1. Lê `X-Correlation-Id` do request (se presente) ou gera novo UUID
2. Propaga para todos os downstream via header
3. Cada serviço inclui o Correlation ID em todos os logs via MDC

```java
// CorrelationIdFilter.java — gateway
MDC.put("correlationId", correlationId);
exchange.getResponse().getHeaders().add("X-Correlation-Id", correlationId);
```

### Distributed Tracing

As dependências `micrometer-tracing-bridge-brave` e `zipkin-reporter-brave` já estão no gateway. Ativar:

```properties
management.tracing.sampling.probability=1.0
spring.zipkin.base-url=${ZIPKIN_URL:http://localhost:9411}
```

Adicionar as mesmas dependências nos demais serviços.

---

## Fase 3 — Infraestrutura Containerizada (3–5 meses)

**Objetivo:** Ecossistema completamente dockerizado e deployável com um comando.

### Docker Compose Unificado

```yaml
# docker-compose.yml (raiz do ecossistema)
services:
  postgres-auth:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: authdb
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    ports:
      - "5433:5432"

  postgres-member:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: memberdb
    ports:
      - "5432:5432"

  postgres-financial:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: financialdb
    ports:
      - "5434:5432"

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    ports:
      - "9000:9000"
      - "9001:9001"

  discovery-server:
    build: ./discovery-server
    ports:
      - "8761:8761"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8761/actuator/health"]

  auth-service:
    build: ./auth-service
    depends_on:
      postgres-auth:
        condition: service_healthy
      discovery-server:
        condition: service_healthy
    environment:
      DB_URL: jdbc:postgresql://postgres-auth:5432/authdb
      EUREKA_HOST: discovery-server

  # ... member-service, financial-service, gateway-application
```

### Dockerfiles Multi-Stage (padrão para todos os serviços)

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q
COPY src ./src
RUN ./mvnw package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## Fase 4 — Event-Driven (5–6 meses, condicional)

**Objetivo:** Desacoplar serviços que hoje dependem sincronicamente um do outro ou que têm operações que podem ser assíncronas.

### Quando Adotar Mensageria

Adotar event-driven APENAS quando um dos seguintes for verdadeiro:
- Operação pode tolerar consistência eventual (não precisa de resposta imediata)
- Serviço downstream frequentemente indisponível
- Volume de eventos justifica o overhead do broker

### Casos de Uso Candidatos

| Evento | Produtor | Consumidor | Justificativa |
|--------|----------|------------|---------------|
| `UsuarioCriado` | auth-service | (futuro) notification-service | Envio de email de boas-vindas |
| `TransacaoCriada` | financial-service | (futuro) report-service | Geração de relatórios assíncronos |
| `MembroDesativado` | member-service | auth-service | Desativar acesso do membro |

### Tecnologia Recomendada

Para o estágio atual do projeto: **RabbitMQ** (Spring AMQP — mais simples de operar)  
Para escala futura: **Apache Kafka** (Kafka Streams para relatórios)

> Não adotar antes da Fase 3. Event-driven adiciona complexidade operacional que só vale quando REST síncrono já atingiu seus limites.

---

## Fase 5 — Kubernetes (6+ meses)

**Objetivo:** Alta disponibilidade, escalabilidade horizontal e self-healing.

### Migração do Eureka para Kubernetes Service Discovery

Em Kubernetes, o Service Discovery nativo substitui o Eureka:
- Cada serviço se torna um `Service` do Kubernetes
- O gateway usa o DNS interno do K8s (`http://auth-service:8082`)
- O Eureka pode ser removido gradualmente

```yaml
# auth-service/kubernetes/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: auth-service
spec:
  selector:
    app: auth-service
  ports:
    - port: 8082
      targetPort: 8082
```

### Spring Cloud Config Server

Centraliza todas as configurações em um repositório Git:

```
config-repo/
├── gateway-application.yml
├── auth-service.yml
├── member-service.yml
├── financial-service.yml
└── application.yml  ← configurações compartilhadas
```

Serviços buscam configuração na inicialização via `spring.config.import=configserver:http://config-server:8888`.

### Service Mesh (Istio)

Para mTLS automático entre serviços e observabilidade avançada:
- Traffic management com retry e timeout automáticos
- mTLS entre todos os pods (sem configuração no código)
- Kiali para visualização do tráfego
- Jaeger integrado automaticamente

---

## Resumo da Evolução por Dimensão

| Dimensão | Atual | Fase 1 | Fase 2 | Fase 3 | Fase 4+ |
|----------|-------|--------|--------|--------|---------|
| Segurança | Zero | JWT + CORS + RBAC | + Auditoria | + mTLS interno | + Vault |
| Resiliência | Zero | Circuit Breaker | + Retry/Timeout | + HA Eureka | + K8s self-healing |
| Observabilidade | Zero | Correlation ID | + Zipkin + Logs JSON | + Prometheus/Grafana | + APM |
| Infraestrutura | Local | Docker básico | + Docker Compose completo | + K8s | + Service Mesh |
| Comunicação | REST síncrono | REST seguro | REST + Tracing | REST resiliente | + Event-Driven |
