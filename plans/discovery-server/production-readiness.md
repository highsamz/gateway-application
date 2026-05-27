# Prontidão para Produção — discovery-server

---

## Checklist

### Segurança (Obrigatório)

- [ ] **Dashboard autenticado:** Spring Security com usuário e senha via env var
- [ ] **Clientes atualizados:** Todos os serviços usando `http://user:pass@eureka-host:8761/eureka`
- [ ] **Eureka isolado na rede Docker:** porta 8761 não exposta publicamente
- [ ] **CSRF desabilitado para /eureka/\*\*:** clientes Eureka usam POST para registro

### Disponibilidade (Recomendado para Produção)

- [ ] **Health check via Actuator:** `GET /actuator/health` respondendo 200
- [ ] **Alta disponibilidade:** 2 instâncias peer-to-peer OU Kubernetes Service Discovery
- [ ] **Self-preservation habilitado:** `eureka.server.enable-self-preservation=true` em produção

### Infraestrutura (Obrigatório)

- [ ] **Dockerfile** criado (nenhum existe ainda)
- [ ] **Eureka hostname via env var:** `EUREKA_HOSTNAME` configurável
- [ ] **Primeiro na ordem de startup:** `discovery-server` sobe antes de todos os outros serviços

---

## Variáveis de Ambiente Obrigatórias

| Variável | Descrição | Exemplo |
|----------|-----------|---------|
| `EUREKA_USER` | Usuário para autenticação do dashboard | `eureka` |
| `EUREKA_PASSWORD` | Senha para autenticação | `<senha forte>` |
| `EUREKA_HOSTNAME` | Hostname da instância (para HA) | `eureka-primary` |
| `EUREKA_SELF_PRESERVATION` | Habilitar self-preservation | `true` (produção) |

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
RUN addgroup -S appgroup && adduser -S eurekauser -G appgroup
USER eurekauser
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

---

## docker-compose.yml (trecho)

```yaml
discovery-server:
  build: ./discovery-server
  ports:
    - "8761:8761"  # Em produção: remover esta linha (acessar apenas internamente)
  environment:
    EUREKA_USER: ${EUREKA_USER:-eureka}
    EUREKA_PASSWORD: ${EUREKA_PASSWORD}
    EUREKA_SELF_PRESERVATION: "true"
    SPRING_PROFILES_ACTIVE: prod
  networks:
    - internal
  healthcheck:
    test: ["CMD", "wget", "-qO-", "http://localhost:8761/actuator/health"]
    interval: 10s
    timeout: 5s
    retries: 5
    start_period: 30s

# Todos os outros serviços dependem do Eureka estar healthy
auth-service:
  depends_on:
    discovery-server:
      condition: service_healthy
```

---

## Configuração peer-to-peer para Alta Disponibilidade

Para eliminar o SPOF, em produção usar 2 instâncias:

```yaml
# docker-compose.yml — HA

eureka-primary:
  build: ./discovery-server
  hostname: eureka-primary
  environment:
    EUREKA_HOSTNAME: eureka-primary
    EUREKA_PEER_URL: http://${EUREKA_USER}:${EUREKA_PASSWORD}@eureka-secondary:8761/eureka
    SPRING_PROFILES_ACTIVE: ha

eureka-secondary:
  build: ./discovery-server
  hostname: eureka-secondary
  environment:
    EUREKA_HOSTNAME: eureka-secondary
    EUREKA_PEER_URL: http://${EUREKA_USER}:${EUREKA_PASSWORD}@eureka-primary:8761/eureka
    SPRING_PROFILES_ACTIVE: ha
```

Clientes Eureka apontam para as duas instâncias:
```properties
eureka.client.service-url.defaultZone=\
  http://${EUREKA_USER}:${EUREKA_PASSWORD}@eureka-primary:8761/eureka,\
  http://${EUREKA_USER}:${EUREKA_PASSWORD}@eureka-secondary:8761/eureka
```

---

## Smoke Tests

```bash
# 1. Eureka está de pé e autenticado
curl -u eureka:senha http://localhost:8761/actuator/health

# 2. Dashboard sem credenciais retorna 401
curl -o /dev/null -w "%{http_code}" http://localhost:8761
# deve retornar 401

# 3. Verificar serviços registrados após subir tudo
curl -u eureka:senha http://localhost:8761/eureka/apps | grep "<app>"
# deve mostrar AUTH-SERVICE, MEMBER-SERVICE, FINANCIAL-SERVICE, GATEWAY-APPLICATION

# 4. Em HA: verificar que ambas as instâncias se enxergam
curl -u eureka:senha http://localhost:8761/eureka/peers
```
