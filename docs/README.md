# Documentação Técnica — Ecossistema Noiva de Cristo

> Análise arquitetural completa do ecossistema de microsserviços.  
> Gerada em: 2026-05-27

---

## Índice Geral

| Documento | Conteúdo |
|-----------|----------|
| [01 — Stack e Dependências](./01-stack-e-dependencias.md) | Linguagens, frameworks, bibliotecas, bancos, ferramentas |
| [02 — Padrões Arquiteturais](./02-padroes-arquiteturais.md) | Padrões identificados e avaliação de uso |
| [03 — Comunicação entre Serviços](./03-comunicacao-entre-servicos.md) | REST, descoberta de serviços, fluxo de auth, contratos |
| [04 — Responsabilidades por Serviço](./04-responsabilidades-por-servico.md) | Domínio, regras de negócio, dependências externas |
| [05 — Riscos Arquiteturais](./05-riscos-arquiteturais.md) | Acoplamento, segurança, gargalos, observabilidade |
| [06 — Melhorias Recomendadas](./06-melhorias-recomendadas.md) | Lista priorizada de melhorias antes de produção |
| [07 — Documentação por Serviço](./servicos/) | Análise individual de cada microsserviço |
| [08 — Visão Macro](./08-visao-macro.md) | Diagrama da arquitetura, fluxos completos |
| [09 — Backlog Técnico](./09-backlog-tecnico.md) | Backlog priorizado (Alta / Média / Baixa) |

---

## Visão Rápida do Ecossistema

```
Cliente (HTTP)
     │
     ▼
┌─────────────────────┐
│   Gateway (8080)    │  ◄── Spring Cloud Gateway (WebFlux)
└─────────────────────┘
     │
     ├──/auth/** ──────────► Auth Service     (8082)  ── authdb      (PostgreSQL 5433)
     ├──/user/** ──────────► Auth Service     (8082)
     ├──/members/** ───────► Member Service   (8081)  ── memberdb    (PostgreSQL 5432)
     └──/transactions/** ──► Financial Service(8083)  ── financialdb (PostgreSQL 5434)
                                                       └─ MinIO (armazenamento de anexos)

Todos os serviços registrados em:
     ┌─────────────────────┐
     │  Eureka Server (8761)│  ◄── Discovery Server
     └─────────────────────┘
```

---

## Serviços do Ecossistema

| Serviço | Porta | Domínio | Banco |
|---------|-------|---------|-------|
| `discovery-server` | 8761 | Registro de serviços | — |
| `gateway-application` | 8080 | Roteamento e segurança | — |
| `auth-service` | 8082 | Autenticação e usuários | PostgreSQL (authdb) |
| `member-service` | 8081 | Membros da congregação | PostgreSQL (memberdb) |
| `financial-service` | 8083 | Transações financeiras | PostgreSQL (financialdb) + MinIO |

---

## Status Geral de Prontidão para Produção

| Área | Status | Crítico? |
|------|--------|----------|
| Segurança (JWT/Auth no Gateway) | ❌ Não implementado | Sim |
| CORS | ❌ Não configurado | Sim |
| Rate Limiting | ❌ Não configurado | Sim |
| Tratamento global de exceções | ⚠️ Parcial (só member-service) | Sim |
| Observabilidade / Tracing | ⚠️ Dependências presentes, não configuradas | Médio |
| Testes automatizados | ❌ Praticamente ausentes | Médio |
| Dockerização completa | ⚠️ Parcial (só member-service) | Médio |
| CI/CD | ❌ Ausente | Médio |
| Secrets Management | ❌ Credenciais hardcoded | Sim |
| Versionamento de API | ❌ Ausente | Médio |
