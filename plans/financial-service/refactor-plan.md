# Plano de Refatoração — financial-service

---

## [REFACTOR-FIN-01] Corrigir createdBy no TransactionMapper

### Problema
```java
// TransactionMapper atual — código confirmado
.createdBy(UUID.randomUUID())  // TODO: pegar do contexto de segurança
```

### Por que esse código foi escrito assim
O desenvolvedor sabia que precisaria do userId mas não tinha como obtê-lo (sem JWT, sem X-User-Id propagado). Usou `randomUUID()` como placeholder com um TODO.

### Solução
Adicionar `userId` como parâmetro do método `toEntity()`:
```java
// Antes:
public Transaction toEntity(CreateTransactionRequestDTO dto) {
    return Transaction.builder()
            .createdBy(UUID.randomUUID())  // remover
            // ...
            .build();
}

// Depois:
public Transaction toEntity(CreateTransactionRequestDTO dto, UUID userId) {
    return Transaction.builder()
            .createdBy(userId)  // do header X-User-Id
            // ...
            .build();
}
```

Ver [implementation-plan.md](./implementation-plan.md) Task 3 para o fluxo completo.

---

## [REFACTOR-FIN-02] Extrair Interfaces para Services

### Problema
`TransactionService` e `AttachmentService` são implementações concretas sem interface. Viola DIP do SOLID.

### Solução

```java
public interface TransactionService {
    TransactionResponseDTO create(CreateTransactionRequestDTO dto, UUID userId);
    TransactionResponseDTO findById(UUID id);
    Page<TransactionResponseDTO> findAll(Pageable pageable);
    BigDecimal calculateBalance();
    List<TransactionResponseDTO> findByPeriod(LocalDate start, LocalDate end);
    void delete(UUID id);
}

@Service
public class TransactionServiceImpl implements TransactionService { ... }
```

---

## [REFACTOR-FIN-03] Separar calculateBalance da camada de Service

### Problema
O cálculo de saldo está no `TransactionService`, que faz uma query no banco para todos os registros ativos e soma/subtrai em Java:

```java
// Prática atual (ineficiente para grande volume)
BigDecimal entradas = transactions.stream()
    .filter(t -> t.getType() == ENTRADA)
    .map(Transaction::getAmount)
    .reduce(BigDecimal.ZERO, BigDecimal::add);
```

### Solução mais eficiente (migração gradual)
Usar query SQL para cálculo no banco:

```java
// TransactionRepository — query JPQL
@Query("SELECT " +
    "SUM(CASE WHEN t.type = 'ENTRADA' THEN t.amount ELSE 0 END) - " +
    "SUM(CASE WHEN t.type = 'SAIDA' THEN t.amount ELSE 0 END) " +
    "FROM Transaction t WHERE t.active = true")
BigDecimal calculateBalance();
```

Isso evita carregar todas as transações em memória para fazer a soma.

---

## [REFACTOR-FIN-04] GlobalExceptionHandler

### Problema
Sem `@RestControllerAdvice`, exceções como `TransactionNotFoundException` e erros de validação retornam stack traces.

### Padrão de exceções customizadas

```java
public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(UUID id) {
        super("Transaction not found: " + id);
    }
}

public class InvalidFileTypeException extends RuntimeException {
    public InvalidFileTypeException(String message) { super(message); }
}

public class StorageException extends RuntimeException {
    public StorageException(String message) { super(message); }
}
```

Ver [implementation-plan.md](./implementation-plan.md) Task 2 para o handler completo.

---

## [REFACTOR-FIN-05] Attachment — Funcionalidade Incompleta

### Problema
A entidade `Attachment` e `AttachmentRepository` existem mas:
- Nenhum endpoint de upload/download está exposto
- O MinIO não está configurado
- O código de attachment não é chamado por nada

### Situação atual
O schema de banco tem `attachments` (via Flyway), mas o código de serviço e controller está ausente.

### Estratégia
Não remover o código existente — está correto. Completar implementando:
1. `MinioConfig.java` (configurar cliente MinIO)
2. `AttachmentService.java` (upload/download)
3. Endpoints no `TransactionController` (ver [implementation-plan.md](./implementation-plan.md) Task 5)

---

## [REFACTOR-FIN-06] Paginação e Ordenação

### Problema
O endpoint `GET /transactions` usa paginação com valores fixos no código (20 por página, ordenado por `createdAt DESC`).

### Verificar estado atual
Se os valores estão hardcoded, devem ser parâmetros configuráveis:

```java
// Controller
@GetMapping
public ResponseEntity<Page<TransactionResponseDTO>> findAll(
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
        Pageable pageable) {
    return ResponseEntity.ok(transactionService.findAll(pageable));
}
```

O `@PageableDefault` define os valores padrão enquanto permite que o cliente sobrescreva via `?page=0&size=50&sort=amount,DESC`.

---

## Ordem de Execução

```
Imediato:
  REFACTOR-FIN-01 (createdBy) — junto com FIN-01 na Sprint 1
  REFACTOR-FIN-04 (ExceptionHandler) — Sprint 1

Sprint 2:
  REFACTOR-FIN-03 (calculateBalance no banco) — melhoria de performance
  REFACTOR-FIN-05 (Completar attachments) — junto com FIN-02
  REFACTOR-FIN-06 (Paginação configurável)

Sprint 3+:
  REFACTOR-FIN-02 (Interfaces nos Services)
```
