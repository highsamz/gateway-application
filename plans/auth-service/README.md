# auth-service — Plano de Evolução

## Objetivo do Serviço

Identidade e acesso: gestão de usuários do sistema, verificação de credenciais, e emissão de tokens de autenticação.

**Porta:** 8082 | **Stack:** Spring Boot 3.5.9 + Spring Web (Tomcat) | **Banco:** PostgreSQL (`authdb`, porta 5433)

---

## Estado Atual (confirmado pela leitura do código)

**Pacote:** `br.com.auth_service`  
**SecurityConfig:** em `infrascruture/config/SecurityConfig.java` (typo no diretório: "infrascruture")

**O que existe:**
- `AuthController`: `POST /auth/login` → verifica BCrypt → retorna `LoginResponseDTO{userId, email, roles}`
- `UserController`: `POST /user` (criar usuário), `DELETE /user/email/{email}` (soft delete via `enabled=false`)
- Entidade `User`: UUID id, email (unique), password (BCrypt), enabled (Boolean), roles (ManyToMany EAGER)
- Entidade `Role`: UUID id, RoleName enum (`PASTOR`, `SECRETARIO`, `TESOUREIRO`)
- Flyway para migrações do schema

**O que NÃO existe (crítico):**
- **Nenhuma geração de JWT** — o login está incompleto
- Refresh Token
- Log de auditoria de logins
- Validação de formato de email com `@Email`
- Rate limiting / lockout de conta após tentativas falhas

**SecurityConfig atual:**
```java
// CSRF desabilitado, tudo liberado — igual ao gateway
http.csrf().disable()
    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
    .httpBasic(Customizer.withDefaults());
```

---

## Principais Riscos

| Risco | Severidade | Bloqueador? |
|-------|------------|-------------|
| Login não gera JWT | 🔴 Crítico | Sim |
| Sem refresh token | 🟠 Alto | Recomendado |
| Sem validação de email | 🟡 Médio | Não |
| Sem auditoria de login | 🟡 Médio | Não |
| Sem lockout de conta | 🟡 Médio | Não |
| Credenciais DB hardcoded | 🔴 Crítico | Sim |

---

## Prioridades do Serviço

### Prioridade 1 — Bloqueadores

1. **[AUTH-01]** Implementar geração de JWT no `POST /auth/login`
2. **[SEC-01]** Remover credenciais hardcoded do `application.properties`

### Prioridade 2 — Importantes

3. **[AUTH-02]** Implementar Refresh Token (`POST /auth/refresh`)
4. **[QUAL-01]** Implementar `GlobalExceptionHandler`
5. **[QUAL-02]** Adicionar `@Valid` e `@Email` nos DTOs
6. Adicionar log de auditoria de logins

### Prioridade 3 — Futuro

7. **[AUTH-03]** Tabela `login_audit` com IP e timestamp
8. Lockout de conta após 5 tentativas falhas
9. Endpoint de verificação de token (introspection) — se necessário

---

## Roadmap Resumido

```
Sprint 1: AUTH-01 (JWT) → SEC-01 (credenciais)
Sprint 2: AUTH-02 (Refresh Token) → QUAL-01 (ExceptionHandler) → QUAL-02 (@Valid)
Sprint 3: AUTH-03 (Auditoria) → Lockout de conta
```

---

## Impacto da Implementação de AUTH-01 no Ecossistema

Quando AUTH-01 for implementado:
- **gateway-application:** pode ativar GW-01 (validação JWT)
- **financial-service:** pode implementar FIN-01 (corrigir `createdBy`)
- **member-service:** pode aplicar controle de acesso por role

> AUTH-01 é o pré-requisito de toda a cadeia de segurança. É a task mais crítica do ecossistema inteiro.
