# member-service — Plano de Evolução

## Objetivo do Serviço

Gestão do cadastro de membros da congregação: criação, consulta, atualização, inativação e listagem paginada.

**Porta:** 8081 | **Stack:** Spring Boot 3.3.2 + Spring Web (Tomcat) | **Banco:** PostgreSQL (`memberdb`, porta 5432)

> ⚠️ Este serviço usa Spring Boot **3.3.2**, a versão mais antiga do ecossistema. Os demais usam 3.5.x. Isso causa inconsistências de comportamento (serialização JSON, comportamento do Eureka client).

---

## Estado Atual (confirmado pela leitura do código)

**Pontos positivos (únicos em todo o ecossistema):**
- `GlobalExceptionHandler` com `@RestControllerAdvice` implementado
- Swagger/OpenAPI configurado (`springdoc-openapi`)
- Soft delete implementado (`status = INATIVO`)
- Paginação no `GET /members`
- `getIdade()` calculado dinamicamente (não armazenado no banco)

**Campos da entidade `Member`:**
`id, nome, sexo (enum), dataNascimento, igreja, dataFiliacao, telefone, email, rg, cpf, gruposMinisterios (TEXT), observacoes, status (ATIVO/INATIVO), createdAt`

**Endpoints:**
- `POST /members` — criar membro
- `GET /members/{id}` — buscar por ID
- `GET /members` — listar com paginação
- `PUT /members/{id}` — atualizar
- `DELETE /members/{id}` — soft delete (status = INATIVO)

**O que NÃO existe:**
- Sem constraint de unicidade para CPF ou email no banco
- Sem validação de formato de CPF
- Sem controle de autorização (qualquer um pode criar/editar/excluir)
- Sem `updatedAt` para rastreamento de modificações

---

## Principais Riscos

| Risco | Severidade | Bloqueador? |
|-------|------------|-------------|
| Sem constraint de unicidade CPF/email | 🟡 Médio | Não (mas causa dados duplicados) |
| Sem controle de autorização | 🔴 Crítico | Sim |
| Credenciais DB hardcoded | 🔴 Crítico | Sim |
| Spring Boot 3.3.2 (versão divergente) | 🟡 Médio | Não (risco de comportamento inconsistente) |
| Sem validação de CPF | 🟡 Médio | Não |

---

## Prioridades do Serviço

### Prioridade 1 — Bloqueadores

1. **[SEC-01]** Remover credenciais hardcoded
2. Controle de autorização (PASTOR e SECRETARIO — virá do gateway quando GW-01 + GW-03 implementados)

### Prioridade 2 — Importantes

3. **[MBR-01]** Constraint de unicidade para CPF e email (migração Flyway)
4. **[QUAL-02]** Validação de entrada com `@Valid` nos DTOs
5. Adicionar `updatedAt` com `@UpdateTimestamp`
6. **[QUAL-03]** Atualizar Spring Boot para 3.4.4 (via parent POM)

### Prioridade 3 — Melhorias Futuras

7. Validação de formato de CPF (11 dígitos, algoritmo de verificação)
8. Busca por nome (filtro no `GET /members?nome=João`)
9. Importação em lote de membros via CSV
10. Exportação da lista de membros

---

## Roadmap Resumido

```
Sprint 1: SEC-01 (credenciais) → MBR-01 (unicidade CPF/email)
Sprint 2: QUAL-02 (@Valid) → QUAL-03 (atualizar Spring Boot) → updatedAt
Sprint 3: Filtros de busca → validação de CPF → exportação
```

---

## Diferencial Positivo deste Serviço

O member-service é o mais maduro do ecossistema em termos de qualidade de código:
- Único com `GlobalExceptionHandler` ✅
- Único com Swagger/OpenAPI ✅
- Possui paginação implementada ✅
- Soft delete funcional ✅

As melhorias aqui são incrementais, não estruturais.
