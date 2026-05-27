# Plano de Segurança — financial-service

---

## Estado Atual

```java
// SecurityConfig atual — mesmo padrão do gateway e auth-service
http.csrf().disable()
    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
    .httpBasic(Customizer.withDefaults());
```

Qualquer pessoa pode criar transações, consultar saldo ou deletar transações sem autenticação.

---

## [SEC-01] Remover Credenciais Hardcoded

### Problema
```properties
spring.datasource.url=jdbc:postgresql://localhost:5434/financialdb
spring.datasource.username=postgres
spring.datasource.password=postgres
```

### Solução
```properties
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5434/financialdb}
spring.datasource.username=${DB_USER:postgres}
spring.datasource.password=${DB_PASSWORD}
eureka.client.service-url.defaultZone=http://${EUREKA_USER:eureka}:${EUREKA_PASSWORD:changeme}@${EUREKA_HOST:localhost}:8761/eureka
```

---

## [SEC-FIN-01] Autorização por Papel via X-User-Roles

### Problema
O financial-service não verifica os roles do usuário. Qualquer usuário autenticado (incluindo SECRETARIO) pode criar transações ou consultar o saldo.

### Matriz de permissões para financial-service

| Endpoint | PASTOR | SECRETARIO | TESOUREIRO |
|----------|--------|------------|------------|
| `POST /transactions` | ✅ | ❌ | ✅ |
| `GET /transactions/**` | ✅ | ❌ | ✅ |
| `GET /transactions/balance` | ✅ | ❌ | ✅ |
| `DELETE /transactions/{id}` | ✅ | ❌ | ❌ |

### Implementação — Validação por Header

O gateway propaga `X-User-Roles` com as roles do usuário. O financial-service pode criar um interceptor:

```java
@Component
public class RoleAuthorizationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String rolesHeader = request.getHeader("X-User-Roles");
        String method = request.getMethod();
        String path = request.getRequestURI();

        if (rolesHeader == null || rolesHeader.isBlank()) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Missing identity headers");
            return false;
        }

        List<String> roles = Arrays.asList(rolesHeader.split(","));

        // Apenas PASTOR e TESOUREIRO acessam financial endpoints
        if (!roles.contains("PASTOR") && !roles.contains("TESOUREIRO")) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "Insufficient permissions");
            return false;
        }

        // DELETE apenas para PASTOR
        if ("DELETE".equals(method) && !roles.contains("PASTOR")) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "Only PASTOR can delete transactions");
            return false;
        }

        return true;
    }
}
```

> **Nota:** A proteção principal vem do gateway (SecurityConfig + pathMatchers). Esta validação no financial-service é defesa em profundidade.

---

## [FIN-01] Corrigir createdBy — Rastreabilidade Financeira

### Problema
O `TransactionMapper` usa `UUID.randomUUID()` para `createdBy`. Nenhuma transação tem autoria rastreável.

### Risco
Dados financeiros sem rastreabilidade violam boas práticas de contabilidade. Fraudes e erros não podem ser atribuídos.

### Solução
Ler `X-User-Id` do header HTTP (ver [implementation-plan.md](./implementation-plan.md) Task 3).

---

## [SEC-FIN-02] Segurança do MinIO

### Problema
A dependência MinIO está presente mas sem configuração. Quando configurado, o MinIO deve:

1. **Credenciais via env var:**
```properties
minio.endpoint=${MINIO_ENDPOINT:http://localhost:9000}
minio.access-key=${MINIO_ACCESS_KEY}
minio.secret-key=${MINIO_SECRET_KEY}
minio.bucket-name=${MINIO_BUCKET:financial-attachments}
```

2. **Bucket privado** — não público:
```java
// Verificar/criar bucket na inicialização
@EventListener(ApplicationReadyEvent.class)
public void initBucket() throws Exception {
    if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        // Definir política private (nenhum acesso público)
        minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                .bucket(bucketName)
                .config("{\"Version\":\"2012-10-17\",\"Statement\":[]}")
                .build());
    }
}
```

3. **Validação de tipo de arquivo no upload:**
```java
private static final List<String> ALLOWED_CONTENT_TYPES = List.of(
    "image/jpeg", "image/png", "application/pdf", "image/heic"
);

if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
    throw new InvalidFileTypeException("Only images and PDFs are allowed");
}

if (file.getSize() > 10 * 1024 * 1024) { // 10MB
    throw new FileTooLargeException("File size must be under 10MB");
}
```

4. **Nome do arquivo sanitizado** — nunca usar o nome original do arquivo como caminho:
```java
// NUNCA fazer:
String storagePath = file.getOriginalFilename(); // path traversal!

// Fazer:
String extension = getExtension(file.getOriginalFilename());
String storagePath = transactionId + "/" + UUID.randomUUID() + "." + extension;
```

---

## [SEC-FIN-03] Proteção Contra Manipulação de Dados Financeiros

### Amount nunca pode ser negativo ou zero
```java
// CreateTransactionRequestDTO
@Positive(message = "Amount must be greater than 0")
@DecimalMin(value = "0.01")
BigDecimal amount;
```

### Soft delete é o correto para auditoria financeira
O soft delete atual (`active = false`) é correto — transações não devem ser deletadas permanentemente. Manter esse padrão.

### Imutabilidade de transações após criação
Considerar não permitir atualização de transações históricas. Criar uma nova transação de estorno em vez de editar a original.

---

## SecurityConfig Atualizada

```java
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                // Proteção defensiva adicional (gateway já filtra)
                .anyRequest().permitAll()
            );
        return http.build();
    }
}
```

A proteção primária de autorização está no gateway. O financial-service adiciona a lógica via `RoleAuthorizationInterceptor` para defesa em profundidade.
