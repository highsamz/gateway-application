# Roadmap Global — Ecossistema Noiva de Cristo

> Documento de referência para evolução do ecossistema. Baseado na análise arquitetural de 2026-05-27.

---

## Curto Prazo (0–2 semanas) — Bloqueadores de Produção

### Semana 1 — Segurança Crítica

| Task | Serviço | Esforço | Dependências | Entregável |
|------|---------|---------|--------------|------------|
| [AUTH-01] Gerar JWT no login | auth-service | 1-2 dias | Nenhuma | `POST /auth/login` retorna `{accessToken, expiresIn}` |
| [GW-02] Configurar CORS | gateway-application | 2-4h | Nenhuma | Origens permitidas via env var |
| [SEC-01] Remover credenciais hardcoded | auth, member, financial | 2-4h | Nenhuma | `application.properties` usa `${DB_PASSWORD}` |
| [GW-01] Ativar validação JWT | gateway-application | 4-8h | AUTH-01 | `anyExchange().authenticated()` com OAuth2 |

> AUTH-01 é o pré-requisito de tudo. Implementar primeiro.

### Semana 2 — Propagação e Resiliência Básica

| Task | Serviço | Esforço | Dependências | Entregável |
|------|---------|---------|--------------|------------|
| [GW-03] Propagar X-User-Id, X-User-Roles | gateway-application | 4-8h | GW-01 | GatewayFilter extrai claims do JWT |
| [FIN-01] Corrigir createdBy | financial-service | 4h | GW-03 | `TransactionMapper` usa header, não `randomUUID()` |
| [GW-04] Circuit Breaker no gateway | gateway-application | 4-8h | Nenhuma | Resilience4j configurado por rota |
| [AUTH-02] Refresh Token | auth-service | 1 dia | AUTH-01 | `POST /auth/refresh` + tabela `refresh_tokens` |

---

## Médio Prazo (2–8 semanas) — Qualidade e Resiliência

### Semanas 3-4 — Qualidade de Código

| Task | Serviço | Esforço | Prioridade |
|------|---------|---------|------------|
| [QUAL-01] GlobalExceptionHandler em auth e financial | auth, financial | 4h cada | Alta |
| [QUAL-02] `@Valid` nos DTOs de todos os serviços | todos | 1 dia | Alta |
| [MBR-01] Constraint unicidade CPF/email no banco | member-service | 2h | Média |
| [QUAL-03] Parent POM unificado (unifica versões Spring) | todos | 4h | Média |
| [OBS-01] Correlation ID GlobalFilter | gateway | 4h | Alta |

### Semanas 5-6 — Observabilidade

| Task | Serviço | Esforço | Prioridade |
|------|---------|---------|------------|
| [OBS-02] Ativar OpenTelemetry/Zipkin (dependência já presente no gateway) | todos | 1-2 dias | Média |
| [OBS-03] Logs JSON estruturados (Logback + logstash-logback-encoder) | todos | 4h | Média |
| [INFRA-01] Docker Compose unificado com todos os serviços | ecossistema | 1 dia | Média |
| [FIN-02] Endpoints upload/download de anexos + MinIO configurado | financial | 2-3 dias | Média |

### Semanas 7-8 — Infraestrutura

| Task | Serviço | Esforço | Prioridade |
|------|---------|---------|------------|
| [GW-05] Rate Limiting (Redis + RequestRateLimiter) | gateway | 1 dia | Média |
| [INFRA-02] CI/CD Pipeline básico (GitHub Actions) | todos | 2-3 dias | Baixa |
| [QUAL-04] Testes unitários Services e Mappers | todos | ongoing | Baixa |
| [OBS-04] Prometheus + Grafana | todos | 1-2 dias | Baixa |

---

## Longo Prazo (2–6 meses) — Evolução Arquitetural

### Mês 3 — Observabilidade Enterprise

- Prometheus + Grafana: dashboards de latência, throughput, error rate por serviço
- ELK Stack ou Loki para logs centralizados (correlacionado ao Correlation ID)
- Alertas automáticos para error rate > threshold
- APM: identificar gargalos de performance

### Mês 4 — Infraestrutura Avançada

- Alta disponibilidade Eureka: cluster peer-to-peer (mínimo 2 instâncias) ou migração para Kubernetes Service Discovery
- Spring Cloud Config Server centralizado
- API versioning (`/v1/auth/**`, `/v1/members/**`, `/v1/transactions/**`)
- Secrets Management (HashiCorp Vault ou AWS Secrets Manager)

### Mês 5-6 — Arquitetura Event-Driven (Opcional, baseado em necessidade)

- Avaliar mensageria: Kafka (alta throughput) vs RabbitMQ (simples, AMQP)
- Casos de uso candidatos:
  - `UsuarioCriado` → member-service pré-cria perfil
  - `TransacaoCriada` → notificação para TESOUREIRO
  - `MembroDesativado` → remoção de acesso no auth-service
- CQRS no financial-service para separar leitura pesada de escrita
- Saga pattern para operações que cruzam domínios

---

## Critérios de Prontidão para Produção (Definition of Done)

### Obrigatórios — não deploy sem estes

- [ ] JWT gerado pelo auth-service e validado no gateway (AUTH-01 + GW-01)
- [ ] CORS configurado com origens explícitas (GW-02)
- [ ] Todas as credenciais em variáveis de ambiente (SEC-01)
- [ ] `createdBy` corrigido no financial-service (FIN-01)
- [ ] Circuit Breaker configurado nas 3 rotas do gateway (GW-04)
- [ ] Nenhum stack trace exposto ao cliente

### Recomendados antes do primeiro deploy real

- [ ] `GlobalExceptionHandler` em todos os serviços (QUAL-01)
- [ ] Validação de entrada com `@Valid` (QUAL-02)
- [ ] Correlation ID propagado em todos os logs (OBS-01)
- [ ] Docker Compose funcional sobe todo o ecossistema (INFRA-01)
- [ ] Health checks expostos via Actuator
- [ ] `spring-boot-devtools` removido do gateway (ou restrito a perfil dev)

---

## Ordem de Inicialização Obrigatória

```
1. PostgreSQL (authdb:5433, memberdb:5432, financialdb:5434)
2. MinIO (armazenamento de anexos)
3. discovery-server / Eureka (8761)
4. auth-service (8082)  ─── qualquer ordem
   member-service (8081) ─┘
   financial-service (8083)
5. gateway-application (8080)  ← ÚLTIMO: precisa dos outros no Eureka
```
