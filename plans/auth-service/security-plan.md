# Plano de Segurança — auth-service

---

## [AUTH-01] Implementar Geração de JWT no Login

### Por que esse problema existe
A lógica de verificação de senha com BCrypt foi implementada, mas a geração do token JWT nunca foi adicionada. O `LoginResponseDTO` retorna dados do usuário mas não inclui token.

### Impacto técnico
Toda a cadeia de autenticação do ecossistema está quebrada. O gateway não consegue validar credenciais porque não há token para validar.

### Risco futuro
Em produção, qualquer pessoa pode acessar qualquer endpoint sem credenciais. Violação de LGPD para dados de membros.

### Implementação

**Passo 1: Adicionar dependência JJWT ao pom.xml**

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
```

**Passo 2: Configurar propriedades JWT**

```properties
# application.properties
jwt.secret=${JWT_SECRET}
jwt.expiration=${JWT_EXPIRATION:900}
```

**Passo 3: Criar `JwtService`**

```java
package br.com.auth_service.infrastructure.security;

@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expirationSeconds;

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(User user) {
        List<String> roles = user.getRoles().stream()
                .map(role -> role.getName().name())
                .collect(Collectors.toList());

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationSeconds * 1000))
                .signWith(getSigningKey())
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

**Passo 4: Atualizar `AuthService` para usar `JwtService`**

```java
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public LoginResponseDTO login(LoginRequestDTO request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!user.isEnabled()) {
            throw new InvalidCredentialsException("Account is disabled");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        String accessToken = jwtService.generateAccessToken(user);

        return new LoginResponseDTO(
                user.getId(),
                user.getEmail(),
                user.getRoles().stream().map(r -> r.getName()).collect(Collectors.toSet()),
                accessToken,
                900L
        );
    }
}
```

**Passo 5: Atualizar `LoginResponseDTO`**

```java
public record LoginResponseDTO(
        UUID userId,
        String email,
        Set<RoleName> roles,
        String accessToken,          // NOVO
        Long expiresIn               // NOVO — segundos até expirar
) {}
```

**Passo 6: Variável de ambiente obrigatória**

```bash
# Gerar chave de 256 bits (32 bytes em hex = 64 caracteres)
openssl rand -base64 32  # gera string Base64 de 32 bytes
```

```env
JWT_SECRET=<output do comando acima>
JWT_EXPIRATION=900
```

> CRÍTICO: O mesmo `JWT_SECRET` deve ser configurado no `gateway-application`. Se forem diferentes, o gateway não conseguirá validar os tokens gerados pelo auth-service.

---

## [AUTH-02] Implementar Refresh Token

### Por que é necessário
Sem refresh token, o usuário precisa fazer login a cada 15 minutos (expiração do access token). Aumentar a expiração do access token é inseguro — se roubado, fica válido por mais tempo.

### Modelo de dados — Migração Flyway

```sql
-- V3__create_refresh_tokens.sql
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    token VARCHAR(512) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
```

### Implementação do endpoint

```java
// AuthController.java
@PostMapping("/auth/refresh")
public ResponseEntity<RefreshResponseDTO> refresh(@RequestBody RefreshRequestDTO request) {
    return ResponseEntity.ok(authService.refresh(request.refreshToken()));
}
```

```java
// AuthService.java
public RefreshResponseDTO refresh(String refreshToken) {
    RefreshToken token = refreshTokenRepository.findByToken(refreshToken)
            .filter(t -> !t.isRevoked())
            .filter(t -> t.getExpiresAt().isAfter(LocalDateTime.now()))
            .orElseThrow(() -> new InvalidRefreshTokenException("Invalid or expired refresh token"));

    // Rotação do refresh token (invalida o atual, gera novo)
    token.setRevoked(true);
    refreshTokenRepository.save(token);

    User user = token.getUser();
    String newAccessToken = jwtService.generateAccessToken(user);
    String newRefreshToken = generateAndSaveRefreshToken(user);

    return new RefreshResponseDTO(newAccessToken, newRefreshToken, 900L);
}
```

---

## [SEC-01] Remover Credenciais Hardcoded

### Problema atual no application.properties

```properties
spring.datasource.url=jdbc:postgresql://localhost:5433/authdb
spring.datasource.username=postgres
spring.datasource.password=postgres
```

### Solução

```properties
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5433/authdb}
spring.datasource.username=${DB_USER:postgres}
spring.datasource.password=${DB_PASSWORD}
```

`DB_PASSWORD` não deve ter valor padrão — sem a variável, o serviço falha ao iniciar (comportamento correto em produção).

---

## [QUAL-02] Validação de Entrada nos DTOs

### Problema
`LoginRequestDTO` e `CreateUserRequestDTO` aceitam qualquer valor — email sem `@`, senha em branco.

### Dependência a adicionar

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

### DTOs atualizados

```java
public record LoginRequestDTO(
        @NotBlank @Email String email,
        @NotBlank String password
) {}

public record CreateUserRequestDTO(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password,
        @NotNull RoleName role
) {}
```

### Controllers atualizados

```java
@PostMapping("/auth/login")
public ResponseEntity<LoginResponseDTO> login(@Valid @RequestBody LoginRequestDTO request) {
    return ResponseEntity.ok(authService.login(request));
}
```

---

## Hardening da SecurityConfig

### Estado atual problemático
```java
http.csrf().disable()
    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
    .httpBasic(Customizer.withDefaults());
```

### Estado alvo
O auth-service NÃO precisa de JWT próprio para proteção — ele está atrás do gateway. A proteção vem do gateway via `X-User-*` headers. O auth-service pode manter `anyRequest().permitAll()` internamente, mas deve:

1. Remover `httpBasic` (não usado e desnecessário)
2. Adicionar validação de que chamadas críticas (criar usuário, deletar) só chegam com `X-User-Roles` contendo `PASTOR` — proteção em profundidade

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
                .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/refresh").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // Proteção defensiva — o gateway já filtrou, mas dupla verificação
                .requestMatchers(HttpMethod.POST, "/user").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/user/email/**").permitAll()
                .anyRequest().permitAll()
            );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

---

## Proteção contra Brute Force (Futuro)

Para Sprint 3+, adicionar contador de tentativas falhas:

```sql
-- V4__add_login_attempt_tracking.sql
ALTER TABLE users ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMP;
```

```java
// AuthService.login()
if (user.getFailedLoginAttempts() >= 5 && user.getLockedUntil().isAfter(LocalDateTime.now())) {
    throw new AccountLockedException("Account locked. Try again after " + user.getLockedUntil());
}
```
