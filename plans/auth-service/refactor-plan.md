# Plano de Refatoração — auth-service

---

## [REFACTOR-AUTH-01] Typo no Diretório infrascruture

### Problema
O diretório/pacote `infrascruture` (com typo — deveria ser `infrastructure`) foi criado com erro de digitação e está propagado em todo o serviço.

### Impacto
Cosmético — não afeta funcionamento, mas é confuso para novos desenvolvedores.

### Estratégia
**Não refatorar agora.** Prioridade baixa. Corrigir em uma sprint dedicada a melhorias de qualidade, com IDE (Rename Package do IntelliJ) para garantir que todos os imports sejam atualizados automaticamente.

---

## [REFACTOR-AUTH-02] Extrair Interface para AuthService e UserService

### Problema
`AuthService` e `UserService` são classes concretas sem interface. Isso viola o princípio DIP do SOLID e dificulta testes unitários com mock (Mockito não precisa de interface, mas é boa prática).

### Solução

```java
// Antes:
@Service
public class AuthService { ... }

// Depois:
public interface AuthService {
    LoginResponseDTO login(LoginRequestDTO request);
    RefreshResponseDTO refresh(String refreshToken);
}

@Service
public class AuthServiceImpl implements AuthService { ... }
```

```java
public interface UserService {
    UserResponseDTO create(CreateUserRequestDTO request);
    void delete(String email);
}

@Service
public class UserServiceImpl implements UserService { ... }
```

**Impacto:** Controllers precisam ser atualizados para injetar a interface, não a implementação.

---

## [REFACTOR-AUTH-03] Padronizar Tratamento de Erros

### Problema
Sem `GlobalExceptionHandler`, erros como `UsernameNotFoundException` e `DataIntegrityViolationException` retornam stack traces completas ou respostas padrão Spring sem formato definido.

### Solução
Criar `GlobalExceptionHandler` (ver [implementation-plan.md](./implementation-plan.md) Task 3) com:
- `InvalidCredentialsException` → 401 com mensagem genérica (não revelar se email existe)
- `MethodArgumentNotValidException` → 400 com campos inválidos
- `DataIntegrityViolationException` → 409 (email já cadastrado)
- `Exception` → 500 sem stack trace

### Mensagem genérica para segurança
```java
// NÃO fazer:
throw new Exception("Email not found");  // revela que o email não existe

// Fazer:
throw new InvalidCredentialsException("Invalid email or password");  // genérico
```

---

## [REFACTOR-AUTH-04] Separar Domínio de Infraestrutura

### Problema
A estrutura atual mistura responsabilidades. A entidade `User` com anotações JPA (`@Entity`, `@Table`) está no domínio.

### Estado atual (aproximado)
```
br.com.auth_service/
├── domain/
│   ├── entity/User.java           ← @Entity misturado com domínio
│   ├── entity/Role.java
│   └── repository/UserRepository.java  ← interface JPA no domínio
├── infrascruture/
│   └── config/SecurityConfig.java
└── api/
    └── controller/AuthController.java
```

### Estado alvo (para sprint futura)
```
br.com.auth_service/
├── domain/
│   ├── model/User.java            ← POJO sem @Entity
│   └── service/AuthDomainService.java  ← regras de negócio puras
├── infrastructure/
│   ├── persistence/
│   │   ├── UserEntity.java        ← @Entity aqui
│   │   └── UserRepository.java   ← JPA aqui
│   ├── security/
│   │   └── JwtService.java
│   └── config/SecurityConfig.java
└── api/
    └── controller/AuthController.java
```

> Esta refatoração é de médio prazo — não bloqueia nenhuma implementação atual.

---

## [REFACTOR-AUTH-05] Roles com EAGER fetch

### Problema
A relação `User.roles` usa `FetchType.EAGER`:
```java
@ManyToMany(fetch = FetchType.EAGER)
private Set<Role> roles;
```

Para um serviço de autenticação onde sempre precisamos das roles no login, EAGER é aceitável. Mas se forem adicionadas queries de listagem de usuários sem necessidade de roles, haverá N+1 queries desnecessárias.

### Decisão
**Manter EAGER** por enquanto — o auth-service só tem login e criação de usuário, e ambos precisam das roles. Reavaliar quando houver endpoint de listagem de usuários.

---

## [REFACTOR-AUTH-06] LoginResponseDTO — Exposição de Dados

### Problema
O `LoginResponseDTO` atual retorna `Set<RoleName> roles` diretamente. Após AUTH-01, também retornará `accessToken` que já contém as roles nos claims. Retornar as roles no corpo é redundante e pode ser removido no futuro.

### Decisão para MVP
Manter `roles` no corpo por conveniência para o frontend (evita decodificar JWT no cliente).

### Estado alvo (futuro)
```java
public record LoginResponseDTO(
    String accessToken,
    String refreshToken,
    Long expiresIn
) {}
// Frontend decodifica JWT para obter userId, email, roles se necessário
```

---

## Ordem de Execução

```
Sprint 1: REFACTOR-AUTH-03 (ExceptionHandler — bloqueia qualidade)
Sprint 2: REFACTOR-AUTH-02 (Interfaces nos Services)
Sprint 2: REFACTOR-AUTH-04 (início da separação domínio/infra)
Sprint 3+: REFACTOR-AUTH-01 (typo no pacote — após testes)
Reavaliar: REFACTOR-AUTH-05 (EAGER fetch — monitorar quando escalar)
```
