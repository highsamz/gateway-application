# Serviço: financial-service

**Criticidade:** 🔴 Alta (movimentações financeiras da congregação)

---

## Objetivo

Gerenciar as movimentações financeiras (entradas e saídas) da congregação, calcular saldo, permitir consulta por período e armazenar comprovantes/anexos das transações.

---

## Responsabilidades

### Corretas (implementadas)
- CRUD de transações financeiras com categorização
- Soft delete (active = false)
- Cálculo de saldo (∑ENTRADA - ∑SAÍDA)
- Consulta por período (data inicio/fim)
- Paginação de resultados
- Modelo de dados para anexos (schema + repository)

### Incorretas / Ausentes
- ❌ `createdBy` hardcoded com `UUID.randomUUID()` — auditoria impossível
- ❌ Sem endpoint de upload/download de anexos
- ❌ MinIO configurado como dependência mas sem client bean configurado
- ❌ Sem autorização — qualquer usuário cria/vê transações
- ❌ Sem validação de `amount > 0` via `@Positive` (feita manualmente no service)
- ❌ SecurityConfig libera todos os endpoints

---

## Stack

| Item | Versão |
|------|--------|
| Spring Boot | 3.5.9 |
| Spring Cloud | 2025.0.1 |
| Spring Data JPA | Gerenciada pelo Boot |
| PostgreSQL | 5434 |
| Flyway | Gerenciada pelo Boot |
| MinIO | 8.5.7 |
| Java | 21 |

---

## Modelo de Dados

### Tabela: `transactions`
```sql
id          UUID PRIMARY KEY DEFAULT gen_random_uuid()
type        VARCHAR NOT NULL                -- ENTRADA | SAIDA
category    VARCHAR NOT NULL                -- DIZIMO | OFERTA | DESPESA_FIXA | etc.
amount      NUMERIC(15, 2) NOT NULL
description TEXT
date        DATE NOT NULL
created_at  TIMESTAMP DEFAULT now()
created_by  UUID                            -- hardcoded como randomUUID() atualmente
active      BOOLEAN DEFAULT true
```

### Tabela: `attachments`
```sql
id             UUID PRIMARY KEY DEFAULT gen_random_uuid()
transaction_id UUID REFERENCES transactions(id)
file_name      VARCHAR NOT NULL
content_type   VARCHAR NOT NULL
size           BIGINT
storage_path   VARCHAR(500)                 -- path no MinIO
uploaded_at    TIMESTAMP DEFAULT now()
uploaded_by    UUID
```

---

## Enums

### TransactionType
```
ENTRADA  — Receitas (dízimos, ofertas, doações)
SAIDA    — Despesas (fixas, variáveis)
```

### TransactionCategory
```
DIZIMO            — Dízimos recebidos dos membros
OFERTA            — Ofertas em cultos/eventos
DESPESA_FIXA      — Aluguel, água, luz, etc.
DESPESA_VARIAVEL  — Manutenção, eventos eventuais
DOACAO            — Doações externas
OUTROS            — Lançamentos não categorizados
```

---

## Principais Endpoints

### POST /transactions
```
Header:  X-User-Id: <uuid> (opcional — não utilizado corretamente ainda)
Request:
  {
    "type": "ENTRADA",
    "category": "DIZIMO",
    "amount": 150.00,
    "description": "Dízimo de João",
    "date": "2026-05-27",
    "createdBy": "uuid"        ← ignorado, substituído por randomUUID()
  }

Response 201: TransactionResponseDTO
```

### GET /transactions/{id}
```
Response 200: TransactionResponseDTO
Response 404: (não tratado — retorna 500 ou erro padrão Spring)
```

### GET /transactions?page=0&size=20
```
Response 200: Page<TransactionResponseDTO>
```

### GET /transactions/balance
```
Response 200: BigDecimal
  Ex: 1250.00  (resultado de ENTRADA - SAIDA)
```

### GET /transactions/period?start=2026-01-01&end=2026-05-27
```
Response 200: List<TransactionResponseDTO>
```

### DELETE /transactions/{id}
```
Response 200: (sem body) — soft delete, active=false
```

---

## Fluxo Interno

### Criação de Transação
```
POST /transactions
  └── TransactionController.create(dto, X-User-Id header)
       └── TransactionService.create(dto)
            ├── Validação: amount > 0
            ├── TransactionMapper.toEntity(dto)
            │    └── createdBy = UUID.randomUUID()  ← BUG
            └── TransactionRepository.save(transaction)
```

### Cálculo de Saldo
```
GET /transactions/balance
  └── TransactionController.getBalance()
       └── TransactionService.calculateBalance()
            ├── TransactionRepository.findByTypeAndActiveTrue(ENTRADA) → soma
            ├── TransactionRepository.findByTypeAndActiveTrue(SAIDA) → soma
            └── retorna entradas.subtract(saidas)
```

---

## Dependências

- **PostgreSQL** (`financialdb` na porta 5434) — obrigatório
- **MinIO** — necessário para upload de anexos (não configurado)
- **Eureka Server** (8761) — para registro e descoberta
- **Gateway** — recebe requisições roteadas por `/transactions/**`

---

## Problemas Encontrados

| Problema | Severidade | Descrição |
|----------|------------|-----------|
| createdBy com randomUUID() | 🔴 Crítico | Auditoria financeira impossível — sem rastreabilidade de autoria |
| Anexos sem implementação | 🟠 Alto | Schema existe, API não exposta, MinIO não configurado |
| Sem tratamento global de exceções | 🟠 Alto | GET /transactions/{id} inválido retorna erro não estruturado |
| Sem autorização por role | 🔴 Crítico | Qualquer usuário acessa dados financeiros |
| Credenciais DB hardcoded | 🔴 Crítico | `postgres/postgres` no código |
| Sem validação @Positive no DTO | 🟡 Médio | Validação de amount feita manualmente no service, não no DTO |
| MinIO sem configuração | 🟠 Alto | Dependência declarada sem bean de client |

---

## Melhorias Sugeridas

### Prioridade Alta

1. **Corrigir `createdBy` para usar X-User-Id do header:**
   ```java
   @PostMapping
   public ResponseEntity<TransactionResponseDTO> create(
       @RequestBody CreateTransactionRequestDTO dto,
       @RequestHeader(value = "X-User-Id", required = false) UUID userId
   ) {
       return ResponseEntity.status(201).body(service.create(dto, userId));
   }
   ```

2. **Implementar GlobalExceptionHandler** com resposta padronizada para 404 e 400.

3. **Adicionar autorização por role** (apenas TESOUREIRO cria/visualiza transações).

4. **Externalizar credenciais** via variáveis de ambiente.

### Prioridade Média

5. Configurar client MinIO e implementar endpoints de anexo:
   ```
   POST /transactions/{id}/attachments   (multipart/form-data)
   GET  /transactions/{id}/attachments/{attachmentId}
   ```

6. Adicionar `@Positive` no `amount` do DTO.

7. Adicionar `updatedAt` e audit trail de modificações.

8. Implementar relatório mensal (`GET /transactions/summary?month=2026-05`).

### Prioridade Baixa

9. Adicionar exportação CSV/PDF do extrato.

10. Implementar categorias customizáveis por congregação.

---

## Nível de Criticidade

🔴 **Alta** — Gerencia movimentações financeiras reais da congregação. A ausência de autenticação/autorização e auditoria (`createdBy` incorreto) torna o sistema financeiramente irrastreável e exposto.
