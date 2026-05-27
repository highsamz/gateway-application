# Débitos Técnicos — financial-service

---

## [TD-FIN-01] createdBy com UUID.randomUUID() (Principal)

**Tipo:** Funcionalidade incorreta / Rastreabilidade crítica  
**Prioridade:** 🟠 Alto  
**Esforço para remover:** 4 horas (depende de GW-03)

**Descrição:**  
O `TransactionMapper` usa `UUID.randomUUID()` para o campo `createdBy`, que deveria ser o ID do usuário autenticado. O próprio código tem um TODO e um comentário no controller ("por enquanto, userId pode ser mockado no teste").

**Código problemático confirmado:**
```java
// TransactionMapper.java
.createdBy(UUID.randomUUID())  // TODO: pegar do contexto de segurança
```

**Impacto:**  
100% das transações são irrastreáveis. Auditoria financeira é impossível — não se sabe quem lançou nenhum dízimo, oferta ou despesa.

**Causa:**  
O campo `createdBy` foi modelado corretamente mas sem o mecanismo de propagação de identidade (JWT → gateway → header X-User-Id) em funcionamento, não havia como obter o ID do usuário.

**Estratégia:** Implementar junto com GW-03 (gateway propagando X-User-Id). Ver [implementation-plan.md](./implementation-plan.md) Task 3.

---

## [TD-FIN-02] Funcionalidade de Anexos Incompleta

**Tipo:** Funcionalidade prometida pelo modelo de dados, não exposta  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 2-3 dias

**Descrição:**  
A tabela `attachments` existe no banco (via Flyway), a entidade `Attachment` e o `AttachmentRepository` estão implementados, mas não há endpoint de upload/download nem cliente MinIO configurado. O schema "promete" funcionalidade que não existe na API.

**Impacto:** Comprovantes de transações (recibos, notas fiscais) não podem ser armazenados, comprometendo a auditoria financeira.

**Estratégia:** Ver [implementation-plan.md](./implementation-plan.md) Task 5.

---

## [TD-FIN-03] MinIO sem Configuração

**Tipo:** Dependência presente não utilizada  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 4 horas (parte do FIN-02)

**Descrição:**  
A dependência `io.minio:minio:8.5.7` está no `pom.xml` mas sem:
- Bean `MinioClient` configurado
- Propriedades `minio.endpoint`, `minio.access-key`, `minio.secret-key`
- Nenhum serviço usando o cliente

**Versão desatualizada:** 8.5.7 pode ter vulnerabilidades conhecidas. Atualizar para 8.5.17+ ao configurar.

---

## [TD-FIN-04] Sem GlobalExceptionHandler

**Tipo:** Qualidade  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 4 horas

**Descrição:**  
Sem `@RestControllerAdvice`, exceções como `EntityNotFoundException` e erros de banco retornam stack traces ao cliente.

**Exemplos de erros não tratados:**
- `GET /transactions/id-invalido` → stack trace do `NumberFormatException`
- `POST /transactions` com `amount: null` → stack trace do banco
- Transação não encontrada → stack trace do JPA

---

## [TD-FIN-05] Credenciais Hardcoded

**Tipo:** Segurança crítica  
**Prioridade:** 🔴 Crítico  
**Esforço para remover:** 30 minutos

**Descrição:**
```properties
spring.datasource.username=postgres
spring.datasource.password=postgres
```

Em produção, qualquer pessoa com acesso ao repositório tem acesso ao banco financeiro.

---

## [TD-FIN-06] calculateBalance Carrega Todas as Transações em Memória

**Tipo:** Performance potencial  
**Prioridade:** 🟢 Baixo (aceitável no volume atual)  
**Esforço para remover:** 2 horas

**Descrição:**  
O método `calculateBalance()` provavelmente busca todas as transações ativas e soma em Java. Isso funciona bem com poucas transações mas degrada linearmente com o crescimento da base.

**Solução futura:**
```java
@Query("SELECT SUM(CASE WHEN t.type = 'ENTRADA' THEN t.amount ELSE -t.amount END) FROM Transaction t WHERE t.active = true")
BigDecimal calculateBalance();
```

**Quando endereçar:** Quando a tabela de transações ultrapassar ~10.000 registros.

---

## [TD-FIN-07] Sem Validação de Entrada

**Tipo:** Qualidade  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 2 horas

**Descrição:**  
`CreateTransactionRequestDTO` não tem validações de Bean Validation. É possível criar transações com `amount` negativo, `type` nulo ou `date` no futuro distante.

---

## Resumo Priorizado

| ID | Débito | Prioridade | Esforço | Sprint |
|----|--------|------------|---------|--------|
| TD-FIN-05 | Credenciais hardcoded | 🔴 Crítico | Mínimo | Sprint 1 |
| TD-FIN-01 | createdBy randomUUID | 🟠 Alto | P | Sprint 1 (após GW-03) |
| TD-FIN-04 | Sem ExceptionHandler | 🟡 Médio | P | Sprint 1 |
| TD-FIN-07 | Sem @Valid | 🟡 Médio | P | Sprint 2 |
| TD-FIN-02 | Anexos incompletos | 🟡 Médio | G | Sprint 2 |
| TD-FIN-03 | MinIO não configurado | 🟡 Médio | P | Sprint 2 (junto FIN-02) |
| TD-FIN-06 | calculateBalance ineficiente | 🟢 Baixo | P | Monitorar volume |
