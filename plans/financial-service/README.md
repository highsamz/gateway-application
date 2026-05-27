# financial-service — Plano de Evolução

## Objetivo do Serviço

Gestão de movimentações financeiras da congregação: entradas (dízimos, ofertas, doações), saídas (despesas), cálculo de saldo, consulta por período, e armazenamento de comprovantes.

**Porta:** 8083 | **Stack:** Spring Boot 3.5.9 + Spring Web (Tomcat) | **Banco:** PostgreSQL (`financialdb`, porta 5434) + MinIO

---

## Estado Atual (confirmado pela leitura do código)

**Pacote:** `br.com.financial_service`

**Entidades:**
- `Transaction`: UUID id, type (ENTRADA/SAIDA), category (DIZIMO/OFERTA/DESPESA_FIXA/DESPESA_VARIAVEL/DOACAO/OUTROS), amount (BigDecimal 15,2), description, date, createdAt, createdBy (UUID), active (boolean)
- `Attachment`: UUID id, transaction (ManyToOne), fileName, contentType, size, storagePath, uploadedAt, uploadedBy

**Endpoints implementados:**
- `POST /transactions` — aceita header `X-User-Id` (mas ignora — usa `randomUUID()`)
- `GET /transactions/{id}`, `GET /transactions`, `GET /transactions/balance`, `GET /transactions/period`
- `DELETE /transactions/{id}` — soft delete (`active = false`)

**TODO real encontrado no código:**
```java
// TransactionController.java linha 34:
// por enquanto, userId pode ser mockado no teste
```

**TransactionMapper** (confirmado): usa `UUID.randomUUID()` para `createdBy`

**O que NÃO funciona:**
- `createdBy` sempre é UUID aleatório — rastreabilidade nula
- Entidade `Attachment` e `AttachmentRepository` existem mas sem nenhum endpoint exposto
- MinIO configurado como dependência mas sem cliente configurado
- Sem validação de entrada (`amount` negativo é aceito)
- Sem autorização por papel

---

## Principais Riscos

| Risco | Severidade | Bloqueador? |
|-------|------------|-------------|
| createdBy com randomUUID() | 🟠 Alto | Sim (auditoria financeira impossível) |
| Sem autorização por papel | 🔴 Crítico | Sim (qualquer pessoa faz transações) |
| Credenciais DB hardcoded | 🔴 Crítico | Sim |
| Funcionalidade de anexos incompleta | 🟡 Médio | Não |
| MinIO sem configuração | 🟡 Médio | Não (bloqueia anexos) |
| Sem validação de amount > 0 | 🟡 Médio | Não |

---

## Prioridades do Serviço

### Prioridade 1 — Bloqueadores

1. **[FIN-01]** Corrigir `createdBy`: usar `X-User-Id` do header (depende de GW-03)
2. **[SEC-01]** Remover credenciais hardcoded
3. **[QUAL-01]** Implementar `GlobalExceptionHandler`

### Prioridade 2 — Importantes

4. **[QUAL-02]** Adicionar `@Valid` nos DTOs (`amount > 0`, `@NotNull type/category`)
5. **[FIN-02]** Implementar endpoints de upload/download de anexos com MinIO configurado
6. Adicionar controle de autorização baseado em `X-User-Roles`

### Prioridade 3 — Futuro

7. **[FIN-03]** Relatórios financeiros (extrato mensal, CSV)
8. Soft delete reversal (reativar transação excluída por engano)
9. Paginação configurável via query params

---

## Roadmap Resumido

```
Sprint 1: FIN-01 (createdBy) → SEC-01 (credenciais) → QUAL-01 (ExceptionHandler)
Sprint 2: QUAL-02 (@Valid) → FIN-02 (anexos + MinIO)
Sprint 3: FIN-03 (relatórios) → Autorização por papel
```

---

## Dependências Externas

| Dependência | Estado | Necessidade |
|-------------|--------|-------------|
| PostgreSQL financialdb:5434 | ✅ Configurado | Persistência principal |
| MinIO | ❌ Dependência presente, cliente não configurado | Necessário para FIN-02 |
| Eureka | ✅ Configurado | Service discovery |
| Gateway (X-User-Id) | ❌ Não propagado ainda | Necessário para FIN-01 |
