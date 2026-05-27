# Plano de Implementação — member-service

---

## Task 1 — [SEC-01] Remover Credenciais Hardcoded

**Esforço:** 30 minutos

**Arquivo:** `src/main/resources/application.properties`

```properties
# Antes:
spring.datasource.url=jdbc:postgresql://localhost:5432/memberdb
spring.datasource.username=postgres
spring.datasource.password=postgres

# Depois:
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/memberdb}
spring.datasource.username=${DB_USER:postgres}
spring.datasource.password=${DB_PASSWORD}

eureka.client.service-url.defaultZone=http://${EUREKA_USER:eureka}:${EUREKA_PASSWORD:changeme}@${EUREKA_HOST:localhost}:8761/eureka
```

---

## Task 2 — [MBR-01] Constraint de Unicidade CPF e Email

**Esforço:** 2-3 horas

**Problema:** Dois membros com o mesmo CPF ou email podem ser cadastrados. Isso compromete a integridade dos dados.

**Nova migração Flyway:**

```sql
-- Verificar a versão mais recente existente (ex.: V2) e criar V3
-- src/main/resources/db/migration/V2__add_unique_constraints.sql

-- Verificar duplicatas antes de adicionar a constraint:
-- SELECT cpf, COUNT(*) FROM member GROUP BY cpf HAVING COUNT(*) > 1;
-- SELECT email, COUNT(*) FROM member GROUP BY email HAVING COUNT(*) > 1;

-- Adicionar apenas se não houver duplicatas:
ALTER TABLE member ADD CONSTRAINT uq_member_cpf UNIQUE (cpf);
ALTER TABLE member ADD CONSTRAINT uq_member_email UNIQUE (email);

-- Índices para performance de busca
CREATE INDEX IF NOT EXISTS idx_member_cpf ON member(cpf);
CREATE INDEX IF NOT EXISTS idx_member_email ON member(email);
CREATE INDEX IF NOT EXISTS idx_member_status ON member(status);
```

> ATENÇÃO: Antes de aplicar esta migração em produção, verificar se existem CPFs ou emails duplicados na base. Se sim, precisará de uma estratégia de limpeza de dados antes da constraint.

**Tratamento no GlobalExceptionHandler (já existe, adicionar caso):**
```java
@ExceptionHandler(DataIntegrityViolationException.class)
public ResponseEntity<ApiError> handleDuplicateConstraint(DataIntegrityViolationException e) {
    if (e.getMessage() != null && e.getMessage().contains("uq_member_cpf")) {
        return ResponseEntity.status(409).body(
            new ApiError(409, "DUPLICATE_CPF", "CPF already registered", ...)
        );
    }
    if (e.getMessage() != null && e.getMessage().contains("uq_member_email")) {
        return ResponseEntity.status(409).body(
            new ApiError(409, "DUPLICATE_EMAIL", "Email already registered", ...)
        );
    }
    return ResponseEntity.status(409).body(new ApiError(409, "CONFLICT", "Duplicate data", ...));
}
```

---

## Task 3 — [QUAL-02] Validação de Entrada com @Valid

**Esforço:** 3 horas

**Dependência (verificar se já presente):**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

**MemberRequestDTO atualizado:**
```java
public record MemberRequestDTO(
    @NotBlank(message = "Nome is required")
    @Size(max = 255)
    String nome,

    @NotNull(message = "Sexo is required")
    Sexo sexo,

    @NotNull(message = "Data de nascimento is required")
    @Past(message = "Data de nascimento must be in the past")
    LocalDate dataNascimento,

    @NotBlank(message = "Igreja is required")
    String igreja,

    @NotNull(message = "Data de filiação is required")
    LocalDate dataFiliacao,

    @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Invalid phone format")
    String telefone,

    @Email(message = "Invalid email format")
    String email,

    String rg,

    @Pattern(regexp = "^[0-9]{11}$", message = "CPF must have 11 digits")
    String cpf,

    String gruposMinisterios,

    String observacoes
) {}
```

**Controllers atualizados:**
```java
@PostMapping
public ResponseEntity<MemberResponseDTO> create(@Valid @RequestBody MemberRequestDTO request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(memberService.create(request));
}

@PutMapping("/{id}")
public ResponseEntity<MemberResponseDTO> update(
        @PathVariable UUID id,
        @Valid @RequestBody MemberRequestDTO request) {
    return ResponseEntity.ok(memberService.update(id, request));
}
```

---

## Task 4 — Adicionar updatedAt

**Esforço:** 1-2 horas

**Migração:**
```sql
-- V3__add_updated_at_to_member.sql
ALTER TABLE member ADD COLUMN updated_at TIMESTAMP;
UPDATE member SET updated_at = created_at WHERE updated_at IS NULL;
```

**Entidade Member:**
```java
@Column(name = "created_at", updatable = false)
@CreationTimestamp
private LocalDateTime createdAt;

@Column(name = "updated_at")
@UpdateTimestamp
private LocalDateTime updatedAt;
```

**MemberResponseDTO:**
```java
public record MemberResponseDTO(
    UUID id,
    String nome,
    // ... outros campos
    LocalDateTime createdAt,
    LocalDateTime updatedAt  // NOVO
) {}
```

---

## Task 5 — [QUAL-03] Atualizar Spring Boot para 3.4.4

**Esforço:** 2-4 horas (incluindo teste de regressão)

**Problema:** O member-service usa Spring Boot 3.3.2 enquanto os demais usam 3.5.x. Isso cria inconsistências de serialização JSON e comportamento do Eureka client.

**Abordagem 1: Atualizar independentemente**
```xml
<!-- pom.xml -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.4</version>
</parent>
```

**Abordagem 2: Criar parent POM do ecossistema (QUAL-03)**
```xml
<!-- ecosystem-parent/pom.xml -->
<groupId>br.com.noiva-de-cristo</groupId>
<artifactId>ecosystem-parent</artifactId>
<version>1.0.0</version>
<packaging>pom</packaging>

<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.4</version>
</parent>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>2024.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

A Abordagem 2 é mais robusta mas requer criar uma pasta `ecosystem-parent` na raiz e atualizar o `pom.xml` de todos os serviços.

**Após atualizar:**
1. Executar todos os endpoints via Postman/curl
2. Verificar logs de inicialização por erros de compatibilidade
3. Verificar registro no Eureka

---

## Task 6 — Adicionar Busca por Nome/Status (Futuro)

**Endpoints futuros:**
```
GET /members?nome=João&status=ATIVO&page=0&size=20
GET /members?sexo=MASCULINO&page=0&size=20
```

**Repository:**
```java
Page<Member> findByNomeContainingIgnoreCaseAndStatus(String nome, Status status, Pageable pageable);
```

Ou usando Specification para filtragem dinâmica:
```java
@Query("SELECT m FROM Member m WHERE " +
    "(:nome IS NULL OR LOWER(m.nome) LIKE LOWER(CONCAT('%', :nome, '%'))) AND " +
    "(:status IS NULL OR m.status = :status)")
Page<Member> findWithFilters(@Param("nome") String nome, @Param("status") Status status, Pageable pageable);
```

---

## Resumo de Arquivos Modificados

| Arquivo | Task |
|---------|------|
| `application.properties` | SEC-01 |
| `db/migration/V2__add_unique_constraints.sql` (novo) | MBR-01 |
| `db/migration/V3__add_updated_at.sql` (novo) | Task 4 |
| `MemberRequestDTO.java` | QUAL-02 |
| `MemberResponseDTO.java` | Task 4 |
| `MemberController.java` | QUAL-02 |
| `Member.java` (entidade) | Task 4 |
| `GlobalExceptionHandler.java` | MBR-01 (adicionar caso duplicidade) |
| `pom.xml` | QUAL-03 |
