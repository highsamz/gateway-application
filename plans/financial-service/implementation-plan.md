# Plano de Implementação — financial-service

---

## Sequência de Execução

```
SEC-01 (credenciais) ── independente, fazer imediatamente
QUAL-01 (ExceptionHandler) ── independente
FIN-01 (createdBy) ── depende de GW-03 (gateway propagar X-User-Id)
QUAL-02 (@Valid) ── independente
FIN-02 (anexos MinIO) ── após FIN-01
FIN-03 (relatórios) ── após FIN-01 e FIN-02
```

---

## Task 1 — [SEC-01] Remover Credenciais Hardcoded

**Esforço:** 30 minutos  
**Pré-requisito:** Nenhum

**Arquivo:** `src/main/resources/application.properties`

```properties
# Antes:
spring.datasource.url=jdbc:postgresql://localhost:5434/financialdb
spring.datasource.username=postgres
spring.datasource.password=postgres

# Depois:
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5434/financialdb}
spring.datasource.username=${DB_USER:postgres}
spring.datasource.password=${DB_PASSWORD}

eureka.client.service-url.defaultZone=http://${EUREKA_USER:eureka}:${EUREKA_PASSWORD:changeme}@${EUREKA_HOST:localhost}:8761/eureka
```

---

## Task 2 — [QUAL-01] GlobalExceptionHandler

**Esforço:** 4 horas  
**Pré-requisito:** Nenhum

**Arquivo novo:** `src/main/java/br/com/financial_service/api/exception/GlobalExceptionHandler.java`

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(TransactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            new ApiError(404, "TRANSACTION_NOT_FOUND", e.getMessage(), Instant.now().toString())
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            new ApiError(400, "VALIDATION_ERROR", message, Instant.now().toString())
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegal(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            new ApiError(400, "INVALID_ARGUMENT", e.getMessage(), Instant.now().toString())
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            new ApiError(500, "INTERNAL_ERROR", "An unexpected error occurred", Instant.now().toString())
        );
    }
}

public record ApiError(int status, String code, String message, String timestamp) {}
```

---

## Task 3 — [FIN-01] Corrigir createdBy

**Esforço:** 4 horas  
**Pré-requisito:** GW-03 implementado (gateway propaga `X-User-Id`)

### Problema atual (confirmado no código)

O `TransactionController` recebe o header `X-User-Id` mas comenta que pode ser mockado:
```java
// por enquanto, userId pode ser mockado no teste
```

O `TransactionMapper` usa `UUID.randomUUID()` para `createdBy`.

### Solução

**Atualizar `TransactionController.create()`:**

```java
@PostMapping
public ResponseEntity<TransactionResponseDTO> create(
        @Valid @RequestBody CreateTransactionRequestDTO request,
        @RequestHeader(value = "X-User-Id", required = false) String userId) {

    UUID createdBy = userId != null ? UUID.fromString(userId) : null;
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(transactionService.create(request, createdBy));
}
```

**Atualizar `TransactionService.create()`:**

```java
public TransactionResponseDTO create(CreateTransactionRequestDTO request, UUID userId) {
    if (userId == null) {
        throw new IllegalStateException("X-User-Id header is required");
    }
    Transaction transaction = transactionMapper.toEntity(request, userId);
    return transactionMapper.toResponse(transactionRepository.save(transaction));
}
```

**Atualizar `TransactionMapper.toEntity()`:**

```java
// REMOVER:
.createdBy(UUID.randomUUID())  // TODO: pegar do contexto de segurança

// SUBSTITUIR POR:
public Transaction toEntity(CreateTransactionRequestDTO dto, UUID userId) {
    return Transaction.builder()
            .type(dto.type())
            .category(dto.category())
            .amount(dto.amount())
            .description(dto.description())
            .date(dto.date())
            .createdBy(userId)  // agora correto
            .active(true)
            .build();
}
```

**Verificação:**
```bash
# Com JWT de TESOUREIRO no gateway
curl -X POST http://localhost:8080/transactions \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"type":"ENTRADA","category":"DIZIMO","amount":150.00,"date":"2026-05-27","description":"Dízimo semana"}'

# GET para verificar o createdBy
curl http://localhost:8080/transactions/{id} -H "Authorization: Bearer <token>"
# "createdBy" deve ser o UUID do usuário logado, não um UUID aleatório
```

---

## Task 4 — [QUAL-02] Validação de Entrada

**Esforço:** 2 horas

**Dependência a adicionar:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

**DTO atualizado:**
```java
public record CreateTransactionRequestDTO(
    @NotNull(message = "Transaction type is required")
    TransactionType type,

    @NotNull(message = "Category is required")
    TransactionCategory category,

    @NotNull @Positive(message = "Amount must be positive")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    BigDecimal amount,

    @NotBlank(message = "Description is required")
    @Size(max = 500)
    String description,

    @NotNull(message = "Date is required")
    LocalDate date
) {}
```

---

## Task 5 — [FIN-02] Implementar Upload/Download de Anexos

**Esforço:** 2-3 dias  
**Pré-requisito:** FIN-01 (userId correto), MinIO configurado

### Configurar Cliente MinIO

**Adicionar ao `pom.xml`** (já existe `io.minio:minio:8.5.7` — verificar se é a versão mais recente):
```xml
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.5.17</version>  <!-- atualizar para versão mais recente -->
</dependency>
```

**Propriedades:**
```properties
minio.endpoint=${MINIO_ENDPOINT:http://localhost:9000}
minio.access-key=${MINIO_ACCESS_KEY}
minio.secret-key=${MINIO_SECRET_KEY}
minio.bucket-name=${MINIO_BUCKET:financial-attachments}
```

**Configuração Bean:**
```java
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
```

### AttachmentService

```java
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final MinioClient minioClient;
    private final AttachmentRepository attachmentRepository;
    private final TransactionRepository transactionRepository;

    @Value("${minio.bucket-name}")
    private String bucketName;

    public AttachmentResponseDTO upload(UUID transactionId, MultipartFile file, UUID userId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        String storagePath = transactionId + "/" + UUID.randomUUID() + "_" + file.getOriginalFilename();

        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(storagePath)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        } catch (Exception e) {
            throw new StorageException("Failed to upload file: " + e.getMessage());
        }

        Attachment attachment = Attachment.builder()
                .transaction(transaction)
                .fileName(file.getOriginalFilename())
                .contentType(file.getContentType())
                .size(file.getSize())
                .storagePath(storagePath)
                .uploadedBy(userId)
                .build();

        return attachmentMapper.toResponse(attachmentRepository.save(attachment));
    }
}
```

### Endpoints no TransactionController

```java
@PostMapping("/{id}/attachments")
public ResponseEntity<AttachmentResponseDTO> uploadAttachment(
        @PathVariable UUID id,
        @RequestParam("file") MultipartFile file,
        @RequestHeader("X-User-Id") String userId) {
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(attachmentService.upload(id, file, UUID.fromString(userId)));
}

@GetMapping("/{id}/attachments/{attachmentId}/download")
public ResponseEntity<Resource> downloadAttachment(
        @PathVariable UUID id,
        @PathVariable UUID attachmentId) {
    return attachmentService.download(attachmentId);
}
```

---

## Task 6 — [FIN-03] Relatórios Financeiros (Futuro)

**Esforço:** 1-2 dias  
**Pré-requisito:** FIN-01

**Endpoints futuros:**
```
GET /transactions/summary?year=2026&month=5
  Returns: { totalEntradas, totalSaidas, saldo, quantidadeTransacoes, porCategoria: {...} }

GET /transactions/export?start=2026-01-01&end=2026-12-31&format=csv
  Returns: arquivo CSV com todas as transações do período
```
