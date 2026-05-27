# Matriz de Prioridades — Ecossistema Noiva de Cristo

> Tabela consolidada de todos os itens do backlog técnico. Baseada na análise de 2026-05-27.

---

## Legenda

| Campo | Valores |
|-------|---------|
| Severidade | 🔴 Crítico / 🟠 Alto / 🟡 Médio / 🟢 Baixo |
| Esforço | P (Pequeno: <8h) / M (Médio: 1-2 dias) / G (Grande: 3+ dias) |
| Prioridade | 1 = deve fazer antes de produção, 3 = pode adiar |
| Impacto | Segurança / Funcional / Qualidade / Infra |

---

## Tabela Consolidada

| ID | Título | Serviço | Severidade | Esforço | Prioridade | Impacto | Depende de |
|----|--------|---------|------------|---------|------------|---------|------------|
| AUTH-01 | Gerar JWT no login | auth-service | 🔴 Crítico | M | 1 | Segurança | — |
| GW-01 | Validar JWT no gateway | gateway | 🔴 Crítico | P | 1 | Segurança | AUTH-01 |
| GW-02 | Configurar CORS | gateway | 🔴 Crítico | P | 1 | Segurança | — |
| SEC-01 | Remover credenciais hardcoded | auth, member, financial | 🔴 Crítico | P | 1 | Segurança | — |
| FIN-01 | Corrigir createdBy (randomUUID → X-User-Id) | financial-service | 🟠 Alto | P | 1 | Funcional | GW-03 |
| GW-03 | Propagar X-User-Id, X-User-Roles | gateway | 🟠 Alto | P | 1 | Segurança | GW-01 |
| AUTH-02 | Refresh Token | auth-service | 🟠 Alto | M | 1 | Segurança | AUTH-01 |
| GW-04 | Circuit Breaker por rota | gateway | 🟠 Alto | P | 1 | Resiliência | — |
| QUAL-01 | GlobalExceptionHandler em auth e financial | auth, financial | 🟡 Médio | P | 2 | Qualidade | — |
| QUAL-02 | @Valid e Jakarta Validation nos DTOs | todos | 🟡 Médio | M | 2 | Qualidade | — |
| OBS-01 | Correlation ID GlobalFilter | gateway | 🟡 Médio | P | 2 | Observabilidade | — |
| OBS-02 | Ativar OpenTelemetry/Zipkin | todos | 🟡 Médio | M | 2 | Observabilidade | — |
| OBS-03 | Logs estruturados JSON | todos | 🟡 Médio | P | 2 | Observabilidade | — |
| INFRA-01 | Docker Compose unificado | ecossistema | 🟡 Médio | M | 2 | Infra | — |
| QUAL-03 | Parent POM unificado (versões Spring) | todos | 🟡 Médio | P | 2 | Qualidade | — |
| MBR-01 | Constraint unicidade CPF/email | member-service | 🟡 Médio | P | 2 | Funcional | — |
| FIN-02 | Endpoints upload/download de anexos | financial-service | 🟡 Médio | G | 2 | Funcional | — |
| GW-05 | Rate Limiting (Redis) | gateway | 🟡 Médio | M | 2 | Segurança | — |
| INFRA-02 | CI/CD Pipeline (GitHub Actions) | todos | 🟢 Baixo | G | 3 | Infra | INFRA-01 |
| QUAL-04 | Testes unitários e de integração | todos | 🟢 Baixo | G | 3 | Qualidade | — |
| QUAL-05 | Interfaces nos Services | todos | 🟢 Baixo | P | 3 | Qualidade | — |
| API-01 | Versionamento de API (/v1/) | todos | 🟢 Baixo | P | 3 | Qualidade | — |
| INFRA-03 | Config Server centralizado | ecossistema | 🟢 Baixo | G | 3 | Infra | — |
| OBS-04 | Prometheus + Grafana | todos | 🟢 Baixo | M | 3 | Observabilidade | OBS-03 |
| AUTH-03 | Auditoria de logins (tabela login_audit) | auth-service | 🟢 Baixo | P | 3 | Segurança | — |
| FIN-03 | Relatórios financeiros (extrato, CSV) | financial-service | 🟢 Baixo | M | 3 | Funcional | — |

---

## Agrupamento por Serviço

### gateway-application (8 itens)

| ID | Título | Prioridade |
|----|--------|------------|
| GW-01 | Validar JWT | 1 — Bloqueador |
| GW-02 | CORS | 1 — Bloqueador |
| GW-03 | Propagar identidade | 1 — Bloqueador |
| GW-04 | Circuit Breaker | 1 — Bloqueador |
| OBS-01 | Correlation ID | 2 — Importante |
| GW-05 | Rate Limiting | 2 — Importante |
| OBS-02 | OpenTelemetry/Zipkin | 2 — Importante |
| OBS-03 | Logs JSON | 2 — Importante |

### auth-service (4 itens)

| ID | Título | Prioridade |
|----|--------|------------|
| AUTH-01 | Gerar JWT | 1 — Bloqueador |
| AUTH-02 | Refresh Token | 1 — Bloqueador |
| QUAL-01 | GlobalExceptionHandler | 2 — Importante |
| AUTH-03 | Auditoria de logins | 3 — Futuro |

### financial-service (4 itens)

| ID | Título | Prioridade |
|----|--------|------------|
| FIN-01 | Corrigir createdBy | 1 — Bloqueador |
| QUAL-01 | GlobalExceptionHandler | 2 — Importante |
| FIN-02 | Endpoints de anexos | 2 — Importante |
| FIN-03 | Relatórios financeiros | 3 — Futuro |

### member-service (2 itens)

| ID | Título | Prioridade |
|----|--------|------------|
| MBR-01 | Unicidade CPF/email | 2 — Importante |
| QUAL-02 | @Valid nos DTOs | 2 — Importante |

### discovery-server (0 itens críticos)

> Sem itens de alta prioridade. Monitorar SPOF e avaliar clustering em Fase 3.

### Todos os serviços (4 itens)

| ID | Título | Prioridade |
|----|--------|------------|
| SEC-01 | Remover credenciais hardcoded | 1 — Bloqueador |
| QUAL-02 | @Valid nos DTOs | 2 — Importante |
| QUAL-03 | Parent POM unificado | 2 — Importante |
| INFRA-01 | Docker Compose unificado | 2 — Importante |

---

## Estimativa Total de Esforço

| Prioridade | Itens | Esforço Total Estimado |
|------------|-------|------------------------|
| 1 — Bloqueadores | 8 | ~10–15 dias úteis |
| 2 — Importantes | 10 | ~15–20 dias úteis |
| 3 — Futuros | 8 | ~20+ dias úteis |
| **Total** | **26** | **~45–55 dias úteis** |

> Estimativa para 1 desenvolvedor trabalhando em tempo integral. Com paralelismo (2 devs), reduz ~40%.

---

## Sequência de Execução Recomendada

```
Sprint 1 (semana 1-2): AUTH-01 → GW-01 → GW-02 → SEC-01 → GW-03 → FIN-01 → GW-04 → AUTH-02
Sprint 2 (semana 3-4): QUAL-01 → QUAL-02 → OBS-01 → QUAL-03 → MBR-01
Sprint 3 (semana 5-6): OBS-02 → OBS-03 → INFRA-01 → FIN-02
Sprint 4 (semana 7-8): GW-05 → INFRA-02 → OBS-04
Backlog:               QUAL-04 → QUAL-05 → API-01 → INFRA-03 → AUTH-03 → FIN-03
```

---

## Riscos Não Endereçados por Este Backlog

| Risco | Descrição | Quando Endereçar |
|-------|-----------|-----------------|
| SPOF Eureka | Instância única do discovery-server | Fase 3 (Kubernetes) |
| Versão antiga member-service | Spring Boot 3.3.2 vs 3.5.9 nos demais | Sprint 2 via QUAL-03 |
| Sem testes automatizados | Zero cobertura de testes | Sprint 4+ (ongoing) |
| MinIO sem autenticação configurada | Credenciais padrão expostas | Sprint 3 via FIN-02 |
| Typo no pacote do gateway | `gateway_apllication` histórico | Refactor opcional baixa prioridade |
