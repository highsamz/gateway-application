# Prontidão para Produção — auth-service

---

## Checklist

### Segurança (Obrigatório)

- [ ] **JWT gerado no login:** `JwtService.generateAccessToken()` implementado e retornando token
- [ ] **JWT_SECRET via env var:** nunca hardcoded no `application.properties`
- [ ] **Credenciais DB via env var:** `DB_PASSWORD` sem valor padrão (falha intencional se ausente)
- [ ] **Refresh Token implementado:** `POST /auth/refresh` funcional com rotação de token
- [ ] **Senha mínimo 8 caracteres:** validação `@Size(min=8)` no `CreateUserRequestDTO`
- [ ] **Email validado:** `@Email` no DTO + constraint única no banco
- [ ] **Mensagens genéricas:** login falho retorna "Invalid email or password" (não revelando o motivo exato)

### Qualidade (Obrigatório antes de produção)

- [ ] **GlobalExceptionHandler:** nenhum stack trace exposto ao cliente
- [ ] **@Valid nos controllers:** `AuthController` e `UserController` com `@Valid @RequestBody`
- [ ] **Flyway migrations:** V1, V2, V3 (refresh_tokens), V4 (login_audit) executados com sucesso

### Infraestrutura

- [ ] **Dockerfile multi-stage:** imagem JRE (não JDK) baseada em `eclipse-temurin:21-jre-alpine`
- [ ] **Health check:** `/actuator/health` respondendo 200 com DB status
- [ ] **Eureka URL via env var:** `EUREKA_HOST` não hardcoded para `localhost`
- [ ] **Dependência de banco na inicialização:** não subir antes do PostgreSQL estar disponível

---

## Variáveis de Ambiente Obrigatórias

| Variável | Descrição | Exemplo |
|----------|-----------|---------|
| `DB_URL` | URL de conexão com o banco | `jdbc:postgresql://postgres-auth:5432/authdb` |
| `DB_USER` | Usuário do banco | `authuser` (não usar `postgres`) |
| `DB_PASSWORD` | Senha do banco | `<senha forte>` |
| `JWT_SECRET` | Chave secreta para assinatura JWT | `<base64 de 32 bytes>` |
| `JWT_EXPIRATION` | Expiração do access token em segundos | `900` (15 min) |
| `EUREKA_HOST` | Hostname do discovery-server | `discovery-server` |
| `EUREKA_USER` | Usuário básico do Eureka | `eureka` |
| `EUREKA_PASSWORD` | Senha básica do Eureka | `<senha>` |

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
RUN addgroup -S appgroup && adduser -S authuser -G appgroup
USER authuser
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

---

## docker-compose.yml (trecho)

```yaml
auth-service:
  build: ./auth-service
  ports:
    - "8082:8082"
  environment:
    DB_URL: jdbc:postgresql://postgres-auth:5432/authdb
    DB_USER: ${AUTH_DB_USER}
    DB_PASSWORD: ${AUTH_DB_PASSWORD}
    JWT_SECRET: ${JWT_SECRET}
    JWT_EXPIRATION: 900
    EUREKA_HOST: discovery-server
    EUREKA_USER: ${EUREKA_USER}
    EUREKA_PASSWORD: ${EUREKA_PASSWORD}
  depends_on:
    postgres-auth:
      condition: service_healthy
    discovery-server:
      condition: service_healthy
  healthcheck:
    test: ["CMD", "wget", "-qO-", "http://localhost:8082/actuator/health"]
    interval: 30s
    timeout: 10s
    retries: 3
    start_period: 60s
```

---

## Testes de Fumaça antes de Produção

```bash
# 1. Serviço está de pé
curl -f http://localhost:8082/actuator/health

# 2. Login retorna JWT
TOKEN=$(curl -s -X POST http://localhost:8082/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"pastor@noiva.com","password":"senha123"}' | jq -r '.accessToken')
echo "Token recebido: ${TOKEN:0:20}..."

# 3. Token é um JWT válido (3 partes separadas por ponto)
echo $TOKEN | tr '.' '\n' | wc -l  # deve retornar 3

# 4. Login inválido retorna 401
curl -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"nao@existe.com","password":"errado"}'  # deve retornar 401

# 5. Email inválido retorna 400
curl -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"naoeumemail","password":"senha123"}'  # deve retornar 400
```

---

## Considerações de Segurança de Banco de Dados

Em produção:
1. Criar usuário PostgreSQL específico para o auth-service (não usar `postgres`):
```sql
CREATE USER authuser WITH PASSWORD 'senha-forte';
GRANT CONNECT ON DATABASE authdb TO authuser;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO authuser;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO authuser;
```

2. Não dar permissão de `DROP TABLE` ou `ALTER TABLE` ao usuário da aplicação — apenas ao usuário do Flyway (que pode ser o mesmo, mas com cuidado).
