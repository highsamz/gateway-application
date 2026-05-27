# 03 — Comunicação entre Serviços

---

## 1. Protocolo de Comunicação

**Tipo:** REST/HTTP síncrono exclusivamente.

Não há mensageria assíncrona (Kafka, RabbitMQ) nem gRPC.  
Toda comunicação entre cliente e microsserviços passa pelo gateway.  
Os microsserviços **não se comunicam diretamente entre si**.

---

## 2. Topologia de Comunicação

```
┌──────────────────────────────────────────────────────────┐
│                    CLIENTE (Browser/App)                  │
└──────────────────────┬───────────────────────────────────┘
                       │ HTTP
                       ▼
┌──────────────────────────────────────────────────────────┐
│              GATEWAY APPLICATION (8080)                   │
│           Spring Cloud Gateway — WebFlux                  │
│                                                           │
│  Predicados de rota:                                      │
│  /auth/**     → lb://auth-service                        │
│  /user/**     → lb://auth-service                        │
│  /members/**  → lb://member-service                      │
│  /transactions/** → lb://financial-service               │
└────────────────┬──────────────┬──────────────┬───────────┘
                 │              │              │
          lb://  │        lb:// │        lb:// │
                 ▼              ▼              ▼
     ┌───────────────┐ ┌────────────────┐ ┌─────────────────┐
     │  auth-service │ │ member-service  │ │financial-service│
     │    (8082)     │ │    (8081)       │ │    (8083)       │
     └───────┬───────┘ └───────┬────────┘ └───────┬─────────┘
             │                 │                   │
             ▼                 ▼                   ▼
        authdb(5433)     memberdb(5432)     financialdb(5434)
        PostgreSQL        PostgreSQL          PostgreSQL
                                            + MinIO (arquivos)

Todos os serviços ↑↓ se comunicam com:
     ┌─────────────────────┐
     │   Eureka (8761)     │  (registro e descoberta)
     └─────────────────────┘
```

---

## 3. Roteamento do Gateway

| Route ID | Path Predicate | URI destino | Load Balance |
|----------|----------------|-------------|--------------|
| `auth-service` | `/auth/**`, `/user/**` | `lb://auth-service` | ✅ Round-robin via Eureka |
| `member-service` | `/members/**` | `lb://member-service` | ✅ Round-robin via Eureka |
| `financial-service` | `/transactions/**` | `lb://financial-service` | ✅ Round-robin via Eureka |

**Discovery automático habilitado:**
```properties
spring.cloud.gateway.discovery.locator.enabled=true
spring.cloud.gateway.discovery.locator.lower-case-service-id=true
```

Isso significa que qualquer serviço registrado no Eureka se torna acessível automaticamente via `/{service-name}/**`.

---

## 4. Fluxo de Autenticação (Estado Atual — Problemático)

```
┌────────────┐         ┌──────────┐         ┌──────────────┐
│  Cliente   │ POST    │ Gateway  │ repassa  │ auth-service │
│            ├────────►│ /auth/   │─────────►│ /auth/login  │
│            │         │  login   │          │              │
│            │         │          │◄─────────┤ LoginResponse│
│            │◄────────┤          │          │ DTO (sem JWT) │
│            │ 200 OK  │          │          └──────────────┘
│            │ {userId,│          │
│            │  email, │          │
│            │  roles} │          │
└────────────┘         └──────────┘
```

**Problema crítico:** O `auth-service` retorna `LoginResponseDTO` com `userId`, `email` e `roles`, mas **não gera nem retorna um JWT Token**. A segurança depende de confiança implícita — qualquer cliente pode chamar qualquer endpoint sem se identificar.

**Estado esperado (como deveria ser):**

```
1. Cliente → POST /auth/login → Gateway → auth-service
2. auth-service verifica credenciais → gera JWT assinado
3. JWT retornado ao cliente
4. Cliente inclui JWT no header: Authorization: Bearer <token>
5. Gateway valida JWT (OAuth2 Resource Server)
6. Gateway propaga identidade para downstream via headers:
   X-User-Id, X-User-Email, X-User-Roles
7. Microsserviços confiam nos headers sem re-validar JWT
```

---

## 5. Headers Compartilhados

### Estado Atual

| Header | Usado? | Origem | Destino |
|--------|--------|--------|---------|
| `X-User-Id` | ⚠️ Parcial | Não propagado pelo gateway | financial-service lê (mas não usa) |
| `Authorization` | ❌ | Não validado | Não propagado |
| `Content-Type` | ✅ | Cliente | Todos os serviços |

### Estado Esperado

```
Gateway → downstream services:
  X-User-Id:    <uuid do usuário autenticado>
  X-User-Email: <email>
  X-User-Roles: <PASTOR,SECRETARIO,TESOUREIRO>
```

---

## 6. Contratos de API

### auth-service

```
POST /auth/login
  Body:    { "email": string, "password": string }
  Returns: { "userId": UUID, "email": string, "roles": [RoleName] }

POST /user
  Body:    { "email": string, "password": string, "role": RoleName }
  Returns: { "id": UUID, "email": string, "roles": [RoleName], "enabled": boolean }

DELETE /user/email/{email}
  Returns: 200 OK (sem body — soft delete)
```

### member-service

```
POST /members
  Body:    MemberRequestDTO
  Returns: MemberResponseDTO

GET /members/{id}
  Returns: MemberResponseDTO

GET /members?page=0&size=20&sort=createdAt,DESC
  Returns: Page<MemberResponseDTO>

PUT /members/{id}
  Body:    MemberRequestDTO
  Returns: MemberResponseDTO

DELETE /members/{id}
  Returns: 200 OK (soft delete — status=INATIVO)
```

### financial-service

```
POST /transactions
  Header:  X-User-Id (opcional, UUID)
  Body:    CreateTransactionRequestDTO
  Returns: TransactionResponseDTO

GET /transactions/{id}
  Returns: TransactionResponseDTO

GET /transactions?page=0&size=20
  Returns: Page<TransactionResponseDTO>

GET /transactions/balance
  Returns: BigDecimal (saldo calculado)

GET /transactions/period?start=YYYY-MM-DD&end=YYYY-MM-DD
  Returns: List<TransactionResponseDTO>

DELETE /transactions/{id}
  Returns: 200 OK (soft delete — active=false)
```

---

## 7. Comunicação Síncrona vs. Assíncrona

| Tipo | Status | Impacto |
|------|--------|---------|
| REST síncrono | ✅ Em uso | Simples, porém cria acoplamento temporal |
| Mensageria assíncrona | ❌ Não implementada | Ausência limita resiliência e desacoplamento |

**Risco de ausência de assíncronia:**  
Se o `financial-service` ficar indisponível, o cliente recebe erro imediato. Com mensageria, o pedido poderia ser enfileirado e processado quando o serviço se recuperar.

---

## 8. Descoberta de Serviços

**Todos os serviços** apontam para:
```
eureka.client.service-url.defaultZone=http://localhost:8761/eureka
```

**Problema:** URL hardcoded para `localhost`. Em ambiente Docker/Kubernetes, isso quebra. O endereço deve vir de variável de ambiente.

---

## 9. Ausência de Comunicação Inter-Serviços

Nenhum serviço faz chamadas HTTP diretas a outro serviço via Feign Client, RestTemplate ou WebClient. Isso é positivo para o estágio atual (sem acoplamento direto), mas levanta uma questão:

> O `financial-service` armazena `created_by` (UUID do usuário) mas não valida se esse UUID existe no `auth-service`.

Essa validação cruzada entre domínios precisará de uma estratégia futura (event-driven ou chamada explícita).
