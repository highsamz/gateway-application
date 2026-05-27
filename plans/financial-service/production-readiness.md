# Prontidão para Produção — financial-service

---

## Checklist

### Funcionalidade (Obrigatório)

- [ ] **createdBy corrigido:** `TransactionMapper.toEntity()` recebe `userId` do header `X-User-Id`
- [ ] **Validação de amount:** `@Positive` e `@DecimalMin("0.01")` no DTO
- [ ] **Soft delete funciona:** `DELETE /transactions/{id}` seta `active = false`
- [ ] **Balance correto:** `calculateBalance()` retorna `ENTRADA - SAIDA` para registros ativos

### Segurança (Obrigatório)

- [ ] **Credenciais DB via env var:** `DB_PASSWORD` sem valor padrão
- [ ] **MinIO credenciais via env var:** `MINIO_ACCESS_KEY` e `MINIO_SECRET_KEY`
- [ ] **Bucket MinIO privado:** sem acesso público configurado
- [ ] **Validação de tipo de arquivo:** apenas PDFs e imagens aceitos (quando FIN-02 implementado)
- [ ] **Nome de arquivo sanitizado:** UUID gerado no storage path, não nome original do arquivo

### Qualidade (Recomendado)

- [ ] **GlobalExceptionHandler:** nenhum stack trace exposto
- [ ] **@Valid nos DTOs:** `CreateTransactionRequestDTO` com validações
- [ ] **Paginação configurável:** `@PageableDefault` no endpoint de listagem

### Infraestrutura (Obrigatório)

- [ ] **Dockerfile multi-stage:** imagem JRE baseada em `eclipse-temurin:21-jre-alpine`
- [ ] **Health check:** `/actuator/health` respondendo com status do PostgreSQL
- [ ] **Eureka URL via env var:** `EUREKA_HOST` configurável
- [ ] **MinIO bucket inicializado:** bucket criado na startup se não existir

---

## Variáveis de Ambiente Obrigatórias

| Variável | Descrição | Exemplo |
|----------|-----------|---------|
| `DB_URL` | URL de conexão com financialdb | `jdbc:postgresql://postgres-financial:5432/financialdb` |
| `DB_USER` | Usuário do banco | `financialuser` |
| `DB_PASSWORD` | Senha do banco | `<senha forte>` |
| `MINIO_ENDPOINT` | URL do MinIO | `http://minio:9000` |
| `MINIO_ACCESS_KEY` | Access key do MinIO | `<gerado no MinIO>` |
| `MINIO_SECRET_KEY` | Secret key do MinIO | `<gerado no MinIO>` |
| `MINIO_BUCKET` | Nome do bucket | `financial-attachments` |
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
RUN addgroup -S appgroup && adduser -S financialuser -G appgroup
USER financialuser
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

---

## docker-compose.yml (trecho)

```yaml
financial-service:
  build: ./financial-service
  ports:
    - "8083:8083"
  environment:
    DB_URL: jdbc:postgresql://postgres-financial:5432/financialdb
    DB_USER: ${FINANCIAL_DB_USER}
    DB_PASSWORD: ${FINANCIAL_DB_PASSWORD}
    MINIO_ENDPOINT: http://minio:9000
    MINIO_ACCESS_KEY: ${MINIO_ACCESS_KEY}
    MINIO_SECRET_KEY: ${MINIO_SECRET_KEY}
    MINIO_BUCKET: financial-attachments
    EUREKA_HOST: discovery-server
    EUREKA_USER: ${EUREKA_USER}
    EUREKA_PASSWORD: ${EUREKA_PASSWORD}
  depends_on:
    postgres-financial:
      condition: service_healthy
    minio:
      condition: service_started
    discovery-server:
      condition: service_healthy
  healthcheck:
    test: ["CMD", "wget", "-qO-", "http://localhost:8083/actuator/health"]
    interval: 30s
    timeout: 10s
    retries: 3
    start_period: 60s
```

---

## Testes de Fumaça antes de Produção

```bash
# 1. Serviço está de pé
curl -f http://localhost:8083/actuator/health

# 2. Criar transação (via gateway com JWT)
TOKEN="<access_token_tesoureiro>"
curl -X POST http://localhost:8080/transactions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "ENTRADA",
    "category": "DIZIMO",
    "amount": 150.00,
    "description": "Dízimo semana",
    "date": "2026-05-27"
  }'

# 3. Verificar createdBy no retorno (deve ser o UUID do TESOUREIRO, não aleatório)
# Se createdBy for sempre diferente a cada chamada, FIN-01 não foi implementado

# 4. Consultar saldo
curl http://localhost:8080/transactions/balance \
  -H "Authorization: Bearer $TOKEN"

# 5. Amount negativo deve retornar 400
curl -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/transactions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type":"ENTRADA","category":"DIZIMO","amount":-100,"description":"teste","date":"2026-05-27"}'
# deve retornar 400

# 6. SECRETARIO não pode acessar transactions (403)
TOKEN_SECRETARIO="<access_token_secretario>"
curl -o /dev/null -w "%{http_code}" \
  http://localhost:8080/transactions \
  -H "Authorization: Bearer $TOKEN_SECRETARIO"
# deve retornar 403
```

---

## Considerações de Backup para Dados Financeiros

Dados financeiros têm importância especial para a congregação. Configurar:

1. **Backup diário do PostgreSQL:**
```bash
pg_dump -h localhost -U financialuser financialdb > backup_$(date +%Y%m%d).sql
```

2. **Retenção mínima:** 90 dias de backup
3. **Teste de restore:** Validar mensalmente que o backup pode ser restaurado
4. **Não deletar transações fisicamente:** O soft delete (`active = false`) é a estratégia correta
