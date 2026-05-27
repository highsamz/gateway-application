# Prontidão para Produção — gateway-application

---

## Checklist Geral

### Segurança (Obrigatório)

- [ ] **JWT validado:** `SecurityConfig` com `oauth2ResourceServer().jwt()` ativa
- [ ] **Rotas protegidas:** apenas `/auth/login` e `/auth/refresh` são públicas
- [ ] **CORS configurado:** origens explícitas via `${CORS_ALLOWED_ORIGINS}` (não usar `*`)
- [ ] **RBAC aplicado:** PASTOR, SECRETARIO, TESOUREIRO com permissões separadas por rota
- [ ] **`spring-boot-devtools` removido:** scope `runtime` + `optional`
- [ ] **JWT_SECRET via variável de ambiente:** nunca hardcoded no `application.properties`

### Resiliência (Obrigatório)

- [ ] **Circuit Breaker nas 3 rotas:** member, auth, financial com fallback configurado
- [ ] **Retry em GETs:** máximo 2 tentativas com backoff para BAD_GATEWAY/SERVICE_UNAVAILABLE
- [ ] **Timeout por rota:** configurar `spring.cloud.gateway.httpclient.connect-timeout=2000` e `response-timeout=5s`
- [ ] **Fallback endpoints respondendo:** `FallbackController` retorna JSON com status 503

### Observabilidade (Recomendado)

- [ ] **Correlation ID:** `CorrelationIdFilter` propagando `X-Correlation-Id` para downstream
- [ ] **Health endpoint exposto:** `GET /actuator/health` retornando status dos circuit breakers
- [ ] **Logs estruturados:** `logback-spring.xml` com `LogstashEncoder` para ambientes não-local
- [ ] **Tracing configurado:** Zipkin endpoint configurado via `${ZIPKIN_URL}`

### Infraestrutura (Obrigatório para deploy)

- [ ] **Dockerfile multi-stage:** baseado em `eclipse-temurin:21-jre-alpine`
- [ ] **Todas as variáveis de ambiente documentadas** em `.env.example`
- [ ] **Eureka URL via env var:** `EUREKA_HOST` não hardcoded para `localhost`
- [ ] **docker-compose.yml** sobe o gateway com `depends_on: discovery-server`

---

## Variáveis de Ambiente Obrigatórias

| Variável | Descrição | Exemplo |
|----------|-----------|---------|
| `JWT_SECRET` | Chave secreta para validação JWT (mínimo 256 bits) | `openssl rand -hex 32` |
| `CORS_ALLOWED_ORIGINS` | Origens permitidas (vírgula separadas) | `https://app.noiva.com` |
| `EUREKA_HOST` | Hostname do discovery-server | `discovery-server` (Docker) |
| `EUREKA_USER` | Usuário básico do Eureka | `eureka` |
| `EUREKA_PASSWORD` | Senha básica do Eureka | `<secret>` |
| `ZIPKIN_URL` | URL do Zipkin (opcional) | `http://zipkin:9411` |

---

## Dockerfile

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
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

---

## Configuração docker-compose.yml (trecho)

```yaml
gateway-application:
  build: ./gateway-application
  ports:
    - "8080:8080"
  environment:
    JWT_SECRET: ${JWT_SECRET}
    CORS_ALLOWED_ORIGINS: ${CORS_ALLOWED_ORIGINS:-http://localhost:3000}
    EUREKA_HOST: discovery-server
    EUREKA_USER: ${EUREKA_USER:-eureka}
    EUREKA_PASSWORD: ${EUREKA_PASSWORD}
    ZIPKIN_URL: http://zipkin:9411
  depends_on:
    discovery-server:
      condition: service_healthy
    auth-service:
      condition: service_started
  healthcheck:
    test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
    interval: 30s
    timeout: 10s
    retries: 3
    start_period: 60s
```

---

## Configurações de Timeout Recomendadas para Produção

```yaml
spring:
  cloud:
    gateway:
      httpclient:
        connect-timeout: 2000      # 2 segundos para estabelecer conexão
        response-timeout: 10s      # 10 segundos para resposta total
      routes:
        - id: financial-service
          # ...
          filters:
            - name: RequestTimeout
              args:
                timeout: 30s       # transações podem demorar mais (upload de arquivo)
```

---

## Estratégia de Deploy

### Rolling Update (Kubernetes)
```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0
    maxSurge: 1
```

O gateway não tem estado — qualquer instância pode receber qualquer requisição. Rolling update é seguro.

### Bare Metal / Docker Swarm
1. Subir nova versão na porta temporária (ex.: 8081)
2. Validar com smoke test
3. Atualizar load balancer para nova porta
4. Encerrar versão antiga

---

## Escalabilidade

O gateway é stateless (WebFlux/Netty, sem sessões). Pode ser escalado horizontalmente sem configuração adicional. O único ponto de atenção é o Rate Limiting — se habilitado com Redis, todas as instâncias devem apontar para o mesmo Redis.

---

## Testes de Fumaça (Smoke Tests) antes de Produção

```bash
# 1. Gateway está de pé
curl -f http://localhost:8080/actuator/health

# 2. Rota pública funciona
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"wrong"}' # Deve retornar 401, não 500

# 3. Rota protegida retorna 401 sem token
curl -f http://localhost:8080/members # Deve retornar 401

# 4. CORS responde
curl -I -X OPTIONS http://localhost:8080/members \
  -H "Origin: http://localhost:3000" # Deve retornar Access-Control-Allow-Origin

# 5. Circuit breaker estado
curl http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
```
