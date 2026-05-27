# 08 — Visão Macro do Ecossistema

---

## 1. Diagrama Textual da Arquitetura

```
╔══════════════════════════════════════════════════════════════════════╗
║                      ECOSSISTEMA NOIVA DE CRISTO                     ║
╚══════════════════════════════════════════════════════════════════════╝

  ┌──────────────────────┐
  │   CLIENTE            │  Browser / App Mobile / Postman
  │   (qualquer origem)  │
  └──────────┬───────────┘
             │ HTTP (sem TLS configurado)
             │
             ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │                  GATEWAY APPLICATION :8080                        │
  │                  Spring Cloud Gateway (WebFlux/Netty)             │
  │                                                                    │
  │  ┌────────────────────────────────────────────────────────────┐  │
  │  │  Roteamento por Path Predicate                              │  │
  │  │  /auth/**    /user/**   → lb://auth-service                │  │
  │  │  /members/**            → lb://member-service              │  │
  │  │  /transactions/**       → lb://financial-service           │  │
  │  └────────────────────────────────────────────────────────────┘  │
  │                                                                    │
  │  ⚠️ SecurityConfig: anyExchange().permitAll() — SEM AUTH         │
  │  ⚠️ CORS: NÃO configurado                                        │
  │  ⚠️ Rate Limiting: NÃO configurado                               │
  │  ⚠️ Circuit Breaker: dependência presente, NÃO configurada       │
  └──────────────────────────────────────────────────────────────────┘
             │
             │ lb:// (Spring Cloud Load Balancer)
             │
  ┌──────────┼────────────────────────────────────────────────────┐
  │          │           EUREKA SERVER :8761                       │
  │          │           (Service Registry — SPOF)                 │
  │          │                                                      │
  │   ┌──────┴────┐   ┌───────────┐   ┌────────────────────────┐  │
  │   │  Resolve  │   │  Resolve  │   │       Resolve           │  │
  │   │auth-service│  │member-svc │   │   financial-service     │  │
  │   └─────┬─────┘   └─────┬─────┘   └───────────┬────────────┘  │
  └─────────┼───────────────┼─────────────────────┼───────────────┘
            │               │                     │
            ▼               ▼                     ▼
  ┌─────────────────┐ ┌──────────────────┐ ┌──────────────────────┐
  │  AUTH SERVICE   │ │  MEMBER SERVICE  │ │  FINANCIAL SERVICE   │
  │     :8082       │ │     :8081        │ │     :8083            │
  │                 │ │                  │ │                       │
  │ POST /auth/login│ │ POST /members    │ │ POST /transactions    │
  │ POST /user      │ │ GET  /members    │ │ GET  /transactions    │
  │ DELETE /user/   │ │ GET  /members/id │ │ GET  /transactions/id │
  │        email/.. │ │ PUT  /members/id │ │ GET  /balance        │
  │                 │ │ DELETE /members/ │ │ GET  /period         │
  │                 │ │         id       │ │ DELETE /transactions/│
  │ ⚠️ Sem JWT      │ │                  │ │           id         │
  │                 │ │ ⚠️ Sem auth     │ │                       │
  │                 │ │                  │ │ ⚠️ createdBy=random  │
  └────────┬────────┘ └──────────┬───────┘ └──────────┬───────────┘
           │                     │                     │
           ▼                     ▼                     ├──────────┐
  ┌─────────────────┐ ┌──────────────────┐ ┌──────────▼──────┐   │
  │   authdb        │ │    memberdb       │ │  financialdb    │   │
  │  PostgreSQL     │ │   PostgreSQL      │ │  PostgreSQL     │   │
  │   :5433         │ │     :5432         │ │    :5434        │   │
  │                 │ │                  │ └─────────────────┘   │
  │  users          │ │  member           │                        │
  │  roles          │ │                  │ ┌──────────────────┐   │
  │  user_roles     │ │                  │ │     MinIO        │◄──┘
  └─────────────────┘ └──────────────────┘ │  (não configurado)│
                                            └──────────────────┘
```

---

## 2. Fluxo Completo: Frontend → Microsserviços

### 2.1 Fluxo de Login (Estado Atual — Sem JWT)

```
┌─────────┐   POST /auth/login        ┌─────────┐
│ Frontend│ ─────────────────────────► │ Gateway │
└─────────┘   {email, password}        └────┬────┘
                                            │ repassa
                                            │ POST /auth/login
                                            ▼
                                       ┌──────────┐
                                       │auth-svc  │
                                       └────┬─────┘
                                            │ verifica senha
                                            │ BCrypt.matches()
                                            │
                                       ┌────▼─────┐
                                       │ authdb   │
                                       └────┬─────┘
                                            │
                                       ◄────┘
                                       retorna LoginResponseDTO
                                       { userId, email, roles }
                                            │
              ◄─────────────────────────────┘
              200 OK { userId, email, roles }
              ⚠️ SEM TOKEN JWT
```

### 2.2 Fluxo de Login (Estado Esperado — Com JWT)

```
┌─────────┐   POST /auth/login        ┌─────────┐
│ Frontend│ ─────────────────────────► │ Gateway │
└─────────┘   {email, password}        └────┬────┘
                                            │ repassa
                                            ▼
                                       ┌──────────┐
                                       │auth-svc  │
                                       │ verifica │
                                       │ gera JWT │◄── chave secreta
                                       └────┬─────┘
                                            │
              ◄─────────────────────────────┘
              200 OK { accessToken, refreshToken, expiresIn }

Próxima requisição autenticada:
┌─────────┐  GET /members             ┌─────────┐
│ Frontend│  Authorization: Bearer X  ► │ Gateway │
└─────────┘                            └────┬────┘
                                            │ valida JWT localmente
                                            │ (chave pública / secret)
                                            │ extrai: userId, roles
                                            │ injeta headers:
                                            │   X-User-Id: uuid
                                            │   X-User-Roles: SECRETARIO
                                            ▼
                                       ┌──────────────┐
                                       │member-service│
                                       │ usa X-User-Id│
                                       │ para auditoria│
                                       └──────────────┘
```

### 2.3 Fluxo de Criação de Membro

```
Frontend → POST /members (com Authorization: Bearer <JWT>)
  │
  Gateway: valida JWT, injeta X-User-Id
  │
  member-service:
    ├── MemberController.create(MemberRequestDTO)
    ├── MemberService.create(dto)
    │     └── MemberMapper.toEntity(dto)
    │     └── MemberRepository.save(member)
    └── retorna MemberResponseDTO
  │
  Gateway: repassa resposta
  │
  Frontend: exibe membro criado
```

### 2.4 Fluxo Financeiro — Lançamento de Transação

```
Frontend (TESOUREIRO) → POST /transactions
  │  { type: "ENTRADA", category: "DIZIMO", amount: 150.00, date: "..." }
  │  Authorization: Bearer <JWT>
  │
  Gateway: valida JWT, injeta X-User-Id: <uuid-tesoureiro>
  │
  financial-service:
    ├── TransactionController.create(dto, X-User-Id)
    ├── TransactionService.create(dto, userId)  ← userId correto
    │     └── TransactionMapper.toEntity(dto, userId)
    │     └── TransactionRepository.save(transaction)
    └── retorna TransactionResponseDTO
```

---

## 3. Fluxo de Autenticação Detalhado (Estado Alvo)

```
                    AUTENTICAÇÃO CENTRALIZADA

  auth-service gera JWT assinado com chave secreta compartilhada
  gateway valida JWT usando a mesma chave (sem chamada ao auth-service)
  microsserviços confiam nos headers injetados pelo gateway

  ┌──────────────┐     JWT assinado      ┌─────────────┐
  │ auth-service │ ─────────────────────► │   Gateway   │
  │ (emissor)    │   (chave secreta       │  (validador)│
  └──────────────┘    compartilhada)      └──────┬──────┘
                                                 │ X-User-Id
                                                 │ X-User-Roles
                                                 ▼
                                    ┌────────────────────────┐
                                    │  Microsserviços        │
                                    │  (confiam nos headers) │
                                    └────────────────────────┘
```

---

## 4. Dependências entre Aplicações

```
discovery-server
    ↑ depende de ninguém
    │
    └── registram-se:
         ├── gateway-application
         ├── auth-service
         ├── member-service
         └── financial-service

gateway-application
    ├── depende de: discovery-server (para resolver lb://)
    └── roteia para: auth-service, member-service, financial-service

auth-service
    ├── depende de: discovery-server (registro)
    └── depende de: authdb (PostgreSQL)

member-service
    ├── depende de: discovery-server (registro)
    └── depende de: memberdb (PostgreSQL)

financial-service
    ├── depende de: discovery-server (registro)
    ├── depende de: financialdb (PostgreSQL)
    └── depende de: MinIO (não configurado)
```

**Ordem de inicialização obrigatória:**
```
1. PostgreSQL (authdb, memberdb, financialdb)
2. MinIO
3. discovery-server (Eureka)
4. auth-service, member-service, financial-service (qualquer ordem)
5. gateway-application (por último — precisa que os outros estejam no Eureka)
```

---

## 5. Papéis de Usuário e Permissões (Estado Alvo)

| Operação | PASTOR | SECRETARIO | TESOUREIRO |
|----------|--------|------------|------------|
| Gerenciar usuários | ✅ | ❌ | ❌ |
| Criar membros | ✅ | ✅ | ❌ |
| Visualizar membros | ✅ | ✅ | ❌ |
| Editar membros | ✅ | ✅ | ❌ |
| Excluir membros | ✅ | ❌ | ❌ |
| Criar transações | ✅ | ❌ | ✅ |
| Visualizar transações | ✅ | ❌ | ✅ |
| Consultar saldo | ✅ | ❌ | ✅ |
| Excluir transações | ✅ | ❌ | ❌ |

*Tabela sugerida — nenhuma dessas restrições está implementada atualmente.*

---

## 6. Pontos de Integração Externos (Futuros)

| Integração | Serviço | Finalidade |
|------------|---------|------------|
| Email (SMTP) | auth-service | Confirmação de conta, reset de senha |
| SMS | auth-service | MFA / OTP |
| MinIO / S3 | financial-service | Armazenamento de comprovantes |
| Zipkin / Jaeger | todos | Distributed tracing |
| Prometheus / Grafana | todos | Métricas e alertas |
| ELK Stack / Loki | todos | Log centralizado |
