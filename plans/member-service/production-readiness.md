# Prontidão para Produção — member-service

---

## Checklist

### Segurança (Obrigatório)

- [ ] **Credenciais DB via env var:** `DB_PASSWORD` sem valor padrão
- [ ] **Autorização via X-User-Roles:** interceptor verifica que apenas PASTOR/SECRETARIO acessam membros
- [ ] **DELETE restrito ao PASTOR:** `DELETE /members/{id}` retorna 403 para SECRETARIO
- [ ] **Nenhum dado pessoal nos logs:** CPF, RG e email não aparecem em nenhum log

### Qualidade (Obrigatório)

- [ ] **@Valid nos controllers:** `POST /members` e `PUT /members/{id}` com validação
- [ ] **Constraint de unicidade:** CPF e email únicos no banco (migração Flyway aplicada)
- [ ] **GlobalExceptionHandler** cobre duplicidade de CPF/email com 409 e mensagem clara

### Spring Boot (Recomendado antes de produção)

- [ ] **Versão atualizada:** Spring Boot 3.4.4 (de 3.3.2)
- [ ] **Testes de regressão executados:** confirmar que todos os endpoints funcionam após atualização

### Infraestrutura (Obrigatório)

- [ ] **Dockerfile multi-stage** baseado em `eclipse-temurin:21-jre-alpine`
- [ ] **Health check:** `/actuator/health` respondendo com status do PostgreSQL
- [ ] **Eureka URL via env var:** não hardcoded para localhost

---

## Variáveis de Ambiente Obrigatórias

| Variável | Descrição | Exemplo |
|----------|-----------|---------|
| `DB_URL` | URL de conexão com memberdb | `jdbc:postgresql://postgres-member:5432/memberdb` |
| `DB_USER` | Usuário do banco | `memberuser` |
| `DB_PASSWORD` | Senha do banco | `<senha forte>` |
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
RUN addgroup -S appgroup && adduser -S memberuser -G appgroup
USER memberuser
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

---

## docker-compose.yml (trecho)

```yaml
member-service:
  build: ./member-service
  ports:
    - "8081:8081"
  environment:
    DB_URL: jdbc:postgresql://postgres-member:5432/memberdb
    DB_USER: ${MEMBER_DB_USER}
    DB_PASSWORD: ${MEMBER_DB_PASSWORD}
    EUREKA_HOST: discovery-server
    EUREKA_USER: ${EUREKA_USER}
    EUREKA_PASSWORD: ${EUREKA_PASSWORD}
  depends_on:
    postgres-member:
      condition: service_healthy
    discovery-server:
      condition: service_healthy
  healthcheck:
    test: ["CMD", "wget", "-qO-", "http://localhost:8081/actuator/health"]
    interval: 30s
    timeout: 10s
    retries: 3
    start_period: 60s
```

---

## Testes de Fumaça

```bash
TOKEN_SECRETARIO="<access_token_secretario>"
TOKEN_PASTOR="<access_token_pastor>"

# 1. Criar membro como SECRETARIO (deve funcionar)
curl -X POST http://localhost:8080/members \
  -H "Authorization: Bearer $TOKEN_SECRETARIO" \
  -H "Content-Type: application/json" \
  -d '{
    "nome": "João da Silva",
    "sexo": "MASCULINO",
    "dataNascimento": "1985-03-15",
    "igreja": "Igreja Central",
    "dataFiliacao": "2020-01-01",
    "email": "joao@email.com",
    "cpf": "12345678901"
  }'

# 2. Criar membro duplicado (deve retornar 409)
# Repetir o mesmo request acima — deve retornar 409 DUPLICATE_CPF ou DUPLICATE_EMAIL

# 3. DELETE como SECRETARIO (deve retornar 403)
curl -o /dev/null -w "%{http_code}" \
  -X DELETE http://localhost:8080/members/{id} \
  -H "Authorization: Bearer $TOKEN_SECRETARIO"
# deve retornar 403

# 4. DELETE como PASTOR (deve funcionar)
curl -X DELETE http://localhost:8080/members/{id} \
  -H "Authorization: Bearer $TOKEN_PASTOR"

# 5. TESOUREIRO não acessa membros
TOKEN_TESOUREIRO="<access_token_tesoureiro>"
curl -o /dev/null -w "%{http_code}" \
  http://localhost:8080/members \
  -H "Authorization: Bearer $TOKEN_TESOUREIRO"
# deve retornar 403

# 6. Swagger acessível
curl -f http://localhost:8081/swagger-ui.html
```

---

## Considerações sobre Dados LGPD

O member-service armazena dados pessoais sensíveis (nome, CPF, RG, email, telefone). Em produção:

1. **Política de acesso:** apenas usuários com roles PASTOR ou SECRETARIO podem acessar dados de membros
2. **Backup criptografado:** dumps do banco devem ser criptografados em repouso
3. **Direito ao esquecimento:** implementar endpoint de hard delete para casos de solicitação LGPD:
   ```
   DELETE /members/{id}/gdpr-erasure
   ```
   (apenas PASTOR pode solicitar, requer confirmação dupla)
4. **Retenção:** definir por quanto tempo manter dados de membros inativos
