# Roadmap de Segurança Global — Ecossistema Noiva de Cristo

> Plano centralizado de segurança para todo o ecossistema. Complementa os `security-plan.md` individuais de cada serviço.

---

## Visão Geral da Arquitetura de Segurança Alvo

```
┌─────────────────────────────────────────────────────────────┐
│                     CLIENTE                                  │
└───────────────────────────┬─────────────────────────────────┘
                            │ HTTPS (obrigatório em produção)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              GATEWAY (8080) — Perímetro de Segurança         │
│                                                              │
│  1. CORS: origens explícitas via ${CORS_ALLOWED_ORIGINS}    │
│  2. Rate Limiting: 10 req/s por IP (Redis)                  │
│  3. JWT Validation: OAuth2 Resource Server (HS256/RS256)     │
│  4. Identity Propagation: X-User-Id, X-User-Email,          │
│     X-User-Roles → downstream                               │
│  5. Circuit Breaker: abre após 5 falhas em 10s              │
└──────────────┬─────────────────┬─────────────┬──────────────┘
               │                 │             │
    ┌──────────▼──┐  ┌───────────▼──┐  ┌───────▼──────────┐
    │ auth-service│  │member-service│  │financial-service  │
    │             │  │              │  │                   │
    │ Emite JWT   │  │ Confia em    │  │ Confia em         │
    │ Valida cred │  │ X-User-*     │  │ X-User-*          │
    │ Gera Refresh│  │ headers do   │  │ TESOUREIRO        │
    │ Token       │  │ gateway      │  │ only para create  │
    └─────────────┘  └──────────────┘  └───────────────────┘
```

---

## 1. Estratégia de Autenticação (JWT Centralizado)

### Estado Atual (problemático)
O `auth-service` verifica senha com BCrypt mas retorna apenas `LoginResponseDTO{userId, email, roles}` — sem token. O gateway tem `anyExchange().permitAll()`.

### Estado Alvo

**Fluxo de Autenticação:**
```
1. Cliente → POST /auth/login {email, password}
2. Gateway → repassa ao auth-service (rota pública)
3. auth-service → verifica BCrypt → gera JWT assinado
4. JWT contém: sub=userId, email, roles, iat, exp (15min)
5. auth-service → gera RefreshToken (7 dias, opaco, armazenado no banco)
6. Resposta: {accessToken, refreshToken, expiresIn: 900}
7. Cliente armazena em memória (access) e HttpOnly cookie (refresh)
```

**Fluxo de Requisição Autenticada:**
```
1. Cliente → Authorization: Bearer <JWT>
2. Gateway → valida JWT offline (sem chamar auth-service)
3. Gateway → extrai claims: userId, roles
4. Gateway → injeta: X-User-Id, X-User-Email, X-User-Roles
5. Microsserviço → usa headers sem re-validar JWT
```

### Configuração JWT

```properties
# auth-service (emissor)
jwt.secret=${JWT_SECRET}          # mínimo 256 bits, gerado com: openssl rand -hex 32
jwt.expiration=900                # 15 minutos em segundos
jwt.refresh-expiration=604800     # 7 dias em segundos

# gateway-application (validador)
spring.security.oauth2.resourceserver.jwt.secret-key=${JWT_SECRET}
```

> IMPORTANTE: `JWT_SECRET` deve ser IDÊNTICO no auth-service e no gateway. Em produção, usar HashiCorp Vault ou AWS Secrets Manager para garantir consistência.

---

## 2. Estratégia RBAC (Role-Based Access Control)

### Roles Definidas

| Role | Descrição | Acesso |
|------|-----------|--------|
| `PASTOR` | Administrador geral da congregação | Tudo |
| `SECRETARIO` | Gestão de membros | Leitura/escrita de membros |
| `TESOUREIRO` | Gestão financeira | Leitura/escrita de transações |

### Matriz de Permissões (Estado Alvo)

| Endpoint | PASTOR | SECRETARIO | TESOUREIRO |
|----------|--------|------------|------------|
| `POST /user` | ✅ | ❌ | ❌ |
| `DELETE /user/email/{email}` | ✅ | ❌ | ❌ |
| `POST /members` | ✅ | ✅ | ❌ |
| `GET /members/**` | ✅ | ✅ | ❌ |
| `PUT /members/{id}` | ✅ | ✅ | ❌ |
| `DELETE /members/{id}` | ✅ | ❌ | ❌ |
| `POST /transactions` | ✅ | ❌ | ✅ |
| `GET /transactions/**` | ✅ | ❌ | ✅ |
| `DELETE /transactions/{id}` | ✅ | ❌ | ❌ |

### Implementação no Gateway

```yaml
# application.yml — gateway
spring:
  cloud:
    gateway:
      routes:
        - id: member-service
          uri: lb://member-service
          predicates:
            - Path=/members/**
          filters:
            - name: AuthorizationFilter
              args:
                allowedRoles: PASTOR,SECRETARIO
```

Alternativa: implementar via `SecurityConfig` com `pathMatchers`:

```java
.authorizeExchange(exchanges -> exchanges
    .pathMatchers("/auth/login").permitAll()
    .pathMatchers(HttpMethod.POST, "/user").hasRole("PASTOR")
    .pathMatchers("/members/**").hasAnyRole("PASTOR", "SECRETARIO")
    .pathMatchers("/transactions/**").hasAnyRole("PASTOR", "TESOUREIRO")
    .anyExchange().authenticated()
)
```

---

## 3. Segurança Entre Microsserviços (Service-to-Service)

### Estado Atual
Nenhum serviço valida a origem das requisições. Um atacante com acesso à rede interna pode chamar diretamente `member-service:8081` sem passar pelo gateway.

### Estratégia Recomendada (por fase)

**Fase 1 (MVP):** Confiança nos headers propagados pelo gateway
- Gateway injeta `X-User-Id`, `X-User-Roles`
- Microsserviços leem os headers sem validação adicional
- Aceitar o risco de chamadas internas diretas (aceitável em rede privada)

**Fase 2 (Pós-MVP):** Restringir por IP/rede
- Microsserviços aceitam apenas chamadas do gateway (por IP da rede Docker/K8s)
- Configurar firewall de rede no Docker Compose ou Network Policy no Kubernetes

**Fase 3 (Maduro):** mTLS ou Service Account JWT
- Gateway inclui `X-Internal-Token` assinado com chave interna
- Microsserviços validam o token interno além do `X-User-*`
- Ou usar Istio/Linkerd para mTLS automático no service mesh

---

## 4. Proteção do Dashboard Eureka

### Estado Atual
Dashboard em `http://localhost:8761` acessível sem autenticação.

### Solução

```xml
<!-- discovery-server/pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

```properties
# discovery-server/application.properties
spring.security.user.name=${EUREKA_USER:eureka}
spring.security.user.password=${EUREKA_PASSWORD:changeme}

# Em todos os clientes Eureka:
eureka.client.service-url.defaultZone=http://${EUREKA_USER}:${EUREKA_PASSWORD}@${EUREKA_HOST:localhost}:8761/eureka
```

---

## 5. Gestão de Secrets

### Hierarquia de Secrets (por ambiente)

| Secret | Desenvolvimento | Produção |
|--------|-----------------|----------|
| `DB_PASSWORD` | `postgres` (local) | HashiCorp Vault / AWS Secrets Manager |
| `JWT_SECRET` | `.env` local (não comitado) | Vault / K8s Secret |
| `MINIO_SECRET_KEY` | `.env` local | Vault |
| `EUREKA_PASSWORD` | `.env` local | Vault / K8s Secret |

### `.env.example` (commitar este, nunca o `.env` real)
```env
DB_URL=jdbc:postgresql://localhost:5432/memberdb
DB_USER=postgres
DB_PASSWORD=CHANGE_ME

JWT_SECRET=CHANGE_ME_256_BIT_HEX_STRING
JWT_EXPIRATION=900

MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=CHANGE_ME
MINIO_SECRET_KEY=CHANGE_ME

EUREKA_HOST=localhost
EUREKA_USER=eureka
EUREKA_PASSWORD=CHANGE_ME

CORS_ALLOWED_ORIGINS=http://localhost:3000
```

---

## 6. Headers de Segurança HTTP

Configurar no gateway para todos os downstream:

```java
// GatewaySecurityHeadersFilter.java
@Component
public class SecurityHeadersFilter implements GlobalFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.add("X-Content-Type-Options", "nosniff");
            headers.add("X-Frame-Options", "DENY");
            headers.add("X-XSS-Protection", "1; mode=block");
            headers.add("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            headers.add("Cache-Control", "no-store");
        }));
    }
}
```

---

## 7. Proteção contra Ataques Comuns

| Ataque | Mitigação | Onde Implementar |
|--------|-----------|-----------------|
| Brute Force login | Rate limiting no `/auth/login` + lockout após 5 tentativas | gateway (rate limit) + auth-service (lockout) |
| JWT token theft | Expiração curta (15min) + refresh token rotation | auth-service |
| SQL Injection | JPA/Hibernate com queries parametrizadas (já protegido por padrão) | todos |
| XSS | Headers de segurança + `Content-Type: application/json` | gateway |
| CSRF | Stateless (JWT) não precisa de CSRF token | N/A |
| Path Traversal | Validar nomes de arquivo no upload de anexos | financial-service |
| Mass Assignment | DTOs separados de entidades (já implementado) | todos |

---

## 8. Auditoria e Compliance (LGPD)

O sistema armazena dados pessoais de membros da congregação (nome, CPF, RG, email, telefone). A LGPD exige:

| Requisito LGPD | Implementação Necessária |
|----------------|------------------------|
| Rastreabilidade de acesso | Auditoria de login no auth-service (tabela `login_audit`) |
| Direito ao esquecimento | Hard delete opcional para dados de membros |
| Segurança dos dados | Credenciais não hardcoded, HTTPS obrigatório |
| Notificação de vazamento | Monitoramento com alertas automáticos |
| Minimização de dados | Não logar CPF/senha em logs |
