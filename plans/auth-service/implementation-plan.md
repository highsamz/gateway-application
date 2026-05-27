# Plano de Implementação — auth-service

---

## Sequência de Execução

```
AUTH-01 (JWT) ──► AUTH-02 (Refresh Token)
SEC-01 (Credenciais) ── independente, fazer imediatamente
QUAL-01 (ExceptionHandler) ── independente
QUAL-02 (@Valid) ── independente
AUTH-03 (Auditoria) ── após AUTH-01
```

---

## Task 1 — [SEC-01] Remover Credenciais Hardcoded

**Esforço:** 30 minutos  
**Pré-requisito:** Nenhum — fazer imediatamente

**Arquivo:** `src/main/resources/application.properties`

```properties
# Antes (inseguro):
spring.datasource.url=jdbc:postgresql://localhost:5433/authdb
spring.datasource.username=postgres
spring.datasource.password=postgres

# Depois (seguro):
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5433/authdb}
spring.datasource.username=${DB_USER:postgres}
spring.datasource.password=${DB_PASSWORD}

# Eureka com suporte a Docker:
eureka.client.service-url.defaultZone=http://${EUREKA_USER:eureka}:${EUREKA_PASSWORD:changeme}@${EUREKA_HOST:localhost}:8761/eureka
```

**Variáveis de ambiente obrigatórias para produção:**
```
DB_URL=jdbc:postgresql://postgres-auth:5432/authdb
DB_USER=<usuário não-root>
DB_PASSWORD=<senha forte>
EUREKA_HOST=discovery-server
```

---

## Task 2 — [AUTH-01] Gerar JWT no Login

**Esforço:** 1-2 dias  
**Pré-requisito:** Nenhum (independente de outros serviços)

**Mudanças no `pom.xml`:**
```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

**Novos arquivos a criar:**

1. `src/main/java/br/com/auth_service/infrastructure/security/JwtService.java`
   - `generateAccessToken(User user)` → retorna String (JWT assinado)
   - `validateToken(String token)` → retorna boolean
   - Claims: `sub`=userId, `email`, `roles` (List<String>), `iat`, `exp`

2. Atualizar `src/main/java/br/com/auth_service/.../dto/LoginResponseDTO.java`
   ```java
   public record LoginResponseDTO(
       UUID userId,
       String email,
       Set<RoleName> roles,
       String accessToken,   // NOVO
       Long expiresIn        // NOVO — 900 segundos
   ) {}
   ```

3. Atualizar `AuthService.login()` para chamar `jwtService.generateAccessToken(user)`

4. `src/main/resources/application.properties`:
   ```properties
   jwt.secret=${JWT_SECRET}
   jwt.expiration=${JWT_EXPIRATION:900}
   ```

**Verificação:**
```bash
curl -X POST http://localhost:8082/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"pastor@noiva.com","password":"senha123"}'

# Resposta esperada:
# {
#   "userId": "...",
#   "email": "pastor@noiva.com",
#   "roles": ["PASTOR"],
#   "accessToken": "eyJ...",
#   "expiresIn": 900
# }
```

---

## Task 3 — [QUAL-01] GlobalExceptionHandler

**Esforço:** 4 horas  
**Pré-requisito:** Nenhum

**Arquivo novo:** `src/main/java/br/com/auth_service/api/exception/GlobalExceptionHandler.java`

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            new ApiError(401, "INVALID_CREDENTIALS", e.getMessage(), Instant.now().toString())
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

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDuplicateEmail(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            new ApiError(409, "EMAIL_ALREADY_EXISTS", "Email already registered", Instant.now().toString())
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception e) {
        // NUNCA expor stack trace ao cliente
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            new ApiError(500, "INTERNAL_ERROR", "An unexpected error occurred", Instant.now().toString())
        );
    }
}

public record ApiError(int status, String code, String message, String timestamp) {}
```

---

## Task 4 — [QUAL-02] Validação de Entrada

**Esforço:** 2 horas  
**Pré-requisito:** Dependência `spring-boot-starter-validation` adicionada (Task 2)

**Atualizar DTOs:**
```java
public record LoginRequestDTO(
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    String email,

    @NotBlank(message = "Password is required")
    String password
) {}

public record CreateUserRequestDTO(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8, message = "Password must have at least 8 characters") String password,
    @NotNull(message = "Role is required") RoleName role
) {}
```

**Atualizar controllers para usar `@Valid`:**
```java
@PostMapping("/auth/login")
public ResponseEntity<LoginResponseDTO> login(@Valid @RequestBody LoginRequestDTO request) {
    // ...
}
```

---

## Task 5 — [AUTH-02] Refresh Token

**Esforço:** 1 dia  
**Pré-requisito:** AUTH-01 (JWT gerado)

**Nova migração Flyway:**
```sql
-- src/main/resources/db/migration/V3__create_refresh_tokens.sql
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    token TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);
```

**Novos arquivos:**
- `RefreshToken` entity
- `RefreshTokenRepository`
- `RefreshTokenService` com método `createRefreshToken(User)` e `rotateRefreshToken(String)`

**Endpoint no `AuthController`:**
```java
@PostMapping("/auth/refresh")
public ResponseEntity<RefreshResponseDTO> refresh(@Valid @RequestBody RefreshRequestDTO request) {
    return ResponseEntity.ok(authService.refresh(request.refreshToken()));
}
```

**Rotação obrigatória:** ao usar um refresh token, invalidar o atual e gerar novo. Isso detecta uso de tokens roubados.

---

## Task 6 — [AUTH-03] Auditoria de Logins

**Esforço:** 4 horas  
**Pré-requisito:** AUTH-01

**Nova migração:**
```sql
-- V4__create_login_audit.sql
CREATE TABLE login_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    success BOOLEAN NOT NULL,
    ip_address VARCHAR(45),
    user_agent VARCHAR(512),
    failure_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_login_audit_email ON login_audit(email);
CREATE INDEX idx_login_audit_created_at ON login_audit(created_at);
```

Registrar na `AuthService.login()` tanto sucesso quanto falha, incluindo o motivo da falha.

---

## Resumo de Novos Arquivos

| Arquivo | Task |
|---------|------|
| `infrastructure/security/JwtService.java` | AUTH-01 |
| `api/exception/GlobalExceptionHandler.java` | QUAL-01 |
| `api/exception/InvalidCredentialsException.java` | QUAL-01 |
| `api/exception/ApiError.java` | QUAL-01 |
| `domain/entity/RefreshToken.java` | AUTH-02 |
| `domain/repository/RefreshTokenRepository.java` | AUTH-02 |
| `domain/service/RefreshTokenService.java` | AUTH-02 |
| `domain/entity/LoginAudit.java` | AUTH-03 |
| `db/migration/V3__create_refresh_tokens.sql` | AUTH-02 |
| `db/migration/V4__create_login_audit.sql` | AUTH-03 |
