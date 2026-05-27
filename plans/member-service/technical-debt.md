# Débitos Técnicos — member-service

---

## [TD-MBR-01] Spring Boot 3.3.2 — Versão Divergente

**Tipo:** Inconsistência de versão  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 2-4 horas

**Descrição:**  
O member-service usa Spring Boot 3.3.2 enquanto os outros serviços usam 3.5.x. O Spring Cloud client também é 2023.0.3 vs 2024.0.0/2025.0.x nos demais.

**Causa:** O member-service foi provavelmente criado antes dos demais e nunca teve sua versão atualizada.

**Impacto:**
- Serialização JSON pode ter comportamento ligeiramente diferente
- O Eureka client 2023.x pode ter bugs diferentes do 2024.x usado pelo gateway
- Dificuldade de debugar problemas de integração que só aparecem no member-service

**Estratégia:** Atualizar para 3.4.4 + Spring Cloud 2024.0.0 (mesmo que o gateway) via parent POM compartilhado (QUAL-03).

---

## [TD-MBR-02] Sem Constraint de Unicidade para CPF/Email

**Tipo:** Integridade de dados  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 2-3 horas

**Descrição:**  
O banco aceita dois membros com o mesmo CPF ou email. Isso causa:
- Dados duplicados não detectados automaticamente
- Impossibilidade de buscar membro por CPF de forma confiável
- Violação de regra de negócio (cada membro é único)

**Estratégia:** Migração Flyway com `ADD CONSTRAINT UNIQUE`. Ver [implementation-plan.md](./implementation-plan.md) Task 2.

---

## [TD-MBR-03] Sem Validação de Entrada (@Valid)

**Tipo:** Qualidade  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 3 horas

**Descrição:**  
Apesar do GlobalExceptionHandler existente, os DTOs não têm anotações de validação e os controllers não usam `@Valid`. É possível criar um membro com nome em branco, email inválido ou CPF com formato incorreto.

---

## [TD-MBR-04] Sem updatedAt na Entidade

**Tipo:** Rastreabilidade  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 1-2 horas

**Descrição:**  
A entidade `Member` tem `createdAt` mas não `updatedAt`. Não é possível saber quando um membro teve seus dados alterados pela última vez.

**Impacto para LGPD:** Impossível auditar quando dados pessoais foram modificados.

---

## [TD-MBR-05] gruposMinisterios como TEXT Livre

**Tipo:** Modelo de dados limitado  
**Prioridade:** 🟢 Baixo (aceitável para MVP)  
**Esforço para remover:** 2-3 dias (migração de dados + tabela nova)

**Descrição:**  
`gruposMinisterios` é armazenado como texto livre (ex: "Louvor, Jovens"). Isso impossibilita:
- Filtrar "todos os membros do grupo Louvor"
- Relatórios por ministério
- Consistência de nomes de grupos

**Estratégia futura:** Tabela `ministerios` + relação ManyToMany com `member`.

---

## [TD-MBR-06] Sem Autorização por Papel

**Tipo:** Segurança  
**Prioridade:** 🔴 Crítico (virá do gateway, mas sem defesa em profundidade)  
**Esforço para remover:** 4 horas

**Descrição:**  
O member-service não verifica os roles do usuário. Quando o gateway implementar GW-01 e GW-03, o member-service começará a receber `X-User-Roles`, mas sem um interceptor que verifique esse header, a proteção existe apenas no perímetro do gateway.

**Defesa em profundidade:** Implementar `MemberRoleInterceptor` para validar roles mesmo sem o gateway (ex.: acesso direto na rede interna).

---

## [TD-MBR-07] Credenciais Hardcoded

**Tipo:** Segurança crítica  
**Prioridade:** 🔴 Crítico  
**Esforço para remover:** 30 minutos

**Descrição:**  
```properties
spring.datasource.password=postgres
```

---

## Resumo Priorizado

| ID | Débito | Prioridade | Esforço | Sprint |
|----|--------|------------|---------|--------|
| TD-MBR-07 | Credenciais hardcoded | 🔴 Crítico | Mínimo | Sprint 1 |
| TD-MBR-06 | Sem autorização por papel | 🔴 Crítico | P | Sprint 1 (após GW-01) |
| TD-MBR-02 | Sem unicidade CPF/email | 🟡 Médio | P | Sprint 1 |
| TD-MBR-03 | Sem @Valid | 🟡 Médio | P | Sprint 2 |
| TD-MBR-04 | Sem updatedAt | 🟡 Médio | P | Sprint 2 |
| TD-MBR-01 | Spring Boot 3.3.2 | 🟡 Médio | M | Sprint 2 |
| TD-MBR-05 | gruposMinisterios TEXT | 🟢 Baixo | G | Sprint 4+ |
