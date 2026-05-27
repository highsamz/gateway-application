# Débitos Técnicos — auth-service

---

## [TD-AUTH-01] Login sem Geração de JWT (Principal)

**Tipo:** Funcionalidade incompleta / Segurança crítica  
**Prioridade:** 🔴 Crítico  
**Esforço para remover:** 1-2 dias

**Descrição:**  
O `AuthService.login()` verifica a senha com BCrypt e retorna `LoginResponseDTO{userId, email, roles}` mas nunca gera um token JWT. A autenticação está incompleta.

**Causa:** A implementação da verificação de credenciais foi feita mas a parte de emissão de token foi deixada como TODO.

**Impacto futuro:** É o bloqueador principal do ecossistema. Sem JWT do auth-service, o gateway não tem token para validar.

**Estratégia:** Ver [implementation-plan.md](./implementation-plan.md) Task 2.

---

## [TD-AUTH-02] Typo no Diretório do Pacote (infrascruture)

**Tipo:** Cosmético / Nomenclatura  
**Prioridade:** 🟢 Baixo  
**Esforço para remover:** 2-3 horas

**Descrição:**  
O diretório `infrascruture` foi criado com erro de digitação. Deveria ser `infrastructure`. O typo está propagado em todos os imports do pacote.

**Causa:** Erro de digitação na criação inicial do projeto.

**Impacto futuro:** Confusão para novos desenvolvedores. Não afeta funcionamento.

**Estratégia de remoção:** Usar `Refactor → Rename Package` no IntelliJ após existir cobertura de testes suficiente.

---

## [TD-AUTH-03] Ausência de Refresh Token

**Tipo:** Funcionalidade ausente  
**Prioridade:** 🟠 Alto  
**Esforço para remover:** 1 dia

**Descrição:**  
Sem refresh token, quando o access token expirar (15min), o usuário precisará fazer login novamente. Para compensar, seria tentador aumentar a expiração do access token — o que é inseguro.

**Impacto futuro:** UX ruim. Tokens roubados com longa duração representam risco de segurança elevado.

**Estratégia:** Ver [implementation-plan.md](./implementation-plan.md) Task 5.

---

## [TD-AUTH-04] Sem Validação de Entrada

**Tipo:** Qualidade / Segurança  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 2 horas

**Descrição:**  
`LoginRequestDTO` e `CreateUserRequestDTO` não possuem anotações de validação. É possível enviar email sem `@`, senha em branco, ou `role` null.

**Causa:** A dependência `spring-boot-starter-validation` não foi adicionada ao `pom.xml`.

**Impacto futuro:** Dados inválidos chegam ao banco. Erros de constraint do banco são expostos ao cliente.

---

## [TD-AUTH-05] GlobalExceptionHandler Ausente

**Tipo:** Qualidade  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 4 horas

**Descrição:**  
Sem `@RestControllerAdvice`, exceções como `UsernameNotFoundException`, `DataIntegrityViolationException` (email duplicado) e erros genéricos retornam stack traces completas ou respostas padrão Spring sem formato definido.

**Impacto:** Stack traces expostos revelam detalhes de implementação (nomes de classes, linha de erro) que facilitam ataques direcionados.

---

## [TD-AUTH-06] SecurityConfig com httpBasic habilitado

**Tipo:** Segurança desnecessária  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 30 minutos

**Descrição:**  
A `SecurityConfig` atual tem `.httpBasic(Customizer.withDefaults())`. O auth-service é stateless e não usa autenticação básica HTTP. Essa configuração é desnecessária e pode confundir ferramentas de segurança.

**Estratégia:** Remover `httpBasic` ao reescrever a `SecurityConfig`.

---

## [TD-AUTH-07] Roles com FetchType.EAGER

**Tipo:** Performance potencial  
**Prioridade:** 🟢 Baixo (aceitável no contexto atual)  
**Esforço para remover:** 4 horas

**Descrição:**  
`User.roles` usa `FetchType.EAGER`, o que carrega todas as roles a cada query de usuário, mesmo quando não necessário.

**Causa:** Para o fluxo de login, as roles são sempre necessárias. EAGER foi uma escolha consciente (ou padrão não questionado).

**Impacto futuro:** Se forem adicionadas queries de listagem de usuários sem necessidade de roles, haverá N+1 queries desnecessárias.

**Estratégia:** Manter EAGER enquanto o único use case é o login. Reavaliar quando existirem queries de listagem de usuários.

---

## [TD-AUTH-08] Sem Auditoria de Logins

**Tipo:** Segurança / Compliance  
**Prioridade:** 🟡 Médio (LGPD)  
**Esforço para remover:** 4 horas

**Descrição:**  
Não há registro de tentativas de login (sucesso ou falha) com IP, timestamp e user-agent. Sem isso, é impossível detectar brute force, investigar acessos suspeitos ou cumprir requisitos de auditoria.

---

## Resumo Priorizado

| ID | Débito | Prioridade | Esforço | Sprint |
|----|--------|------------|---------|--------|
| TD-AUTH-01 | Login sem JWT | 🔴 Crítico | M | Sprint 1 |
| TD-AUTH-03 | Sem Refresh Token | 🟠 Alto | M | Sprint 1 |
| TD-AUTH-05 | Sem ExceptionHandler | 🟡 Médio | P | Sprint 2 |
| TD-AUTH-04 | Sem @Valid | 🟡 Médio | P | Sprint 2 |
| TD-AUTH-08 | Sem auditoria | 🟡 Médio | P | Sprint 3 |
| TD-AUTH-06 | httpBasic desnecessário | 🟡 Médio | Mínimo | Sprint 2 |
| TD-AUTH-07 | EAGER fetch roles | 🟢 Baixo | P | Monitorar |
| TD-AUTH-02 | Typo infrascruture | 🟢 Baixo | P | Após testes |
