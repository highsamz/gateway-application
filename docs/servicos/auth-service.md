# Serviço: auth-service

**Criticidade:** 🔴 Alta (identidade e acesso de todo o ecossistema)

---

## Objetivo

Gerenciar usuários do sistema (administradores da plataforma), realizar verificação de credenciais e fornecer os dados necessários para emissão de tokens de acesso.

---

## Responsabilidades

### Corretas (implementadas)
- Cadastro de usuários com email/senha e papel (role)
- Hash de senha com BCrypt
- Verificação de credenciais no login
- Soft delete de usuários (campo `enabled = false`)
- Gestão de papéis (PASTOR, SECRETARIO, TESOUREIRO)

### Incorretas / Ausentes
- ❌ Não emite JWT — retorna apenas dados do usuário
- ❌ Sem Refresh Token
- ❌ Sem validação de formato de email (`@Email`)
- ❌ Sem lockout por tentativas repetidas de login
- ❌ Sem log de auditoria de acessos
- ❌ SecurityConfig libera todos os endpoints (`permitAll`)

---

## Stack

| Item | Versão |
|------|--------|
| Spring Boot | 3.5.9 |
| Spring Cloud | 2025.0.1 |
| Spring Security | Gerenciada pelo Boot |
| Spring Data JPA + Hibernate | Gerenciada pelo Boot |
| PostgreSQL | 5433 |
| Flyway | Gerenciada pelo Boot |
| Java | 21 |

---

## Modelo de Dados

### Tabela: `users`
```sql
id       UUID PRIMARY KEY DEFAULT gen_random_uuid()
email    VARCHAR UNIQUE NOT NULL
password VARCHAR NOT NULL         -- hash BCrypt
enabled  BOOLEAN DEFAULT true
```

### Tabela: `roles`
```sql
id   UUID PRIMARY KEY DEFAULT gen_random_uuid()
name VARCHAR UNIQUE NOT NULL     -- PASTOR | SECRETARIO | TESOUREIRO
```

### Tabela: `user_roles`
```sql
user_id UUID REFERENCES users(id)
role_id UUID REFERENCES roles(id)
PRIMARY KEY (user_id, role_id)
```

---

## Principais Endpoints

### POST /auth/login
```
Request:
  { "email": "string", "password": "string" }

Response 200:
  {
    "userId": "uuid",
    "email": "string",
    "roles": ["PASTOR", "SECRETARIO", "TESOUREIRO"]
  }

Response 401: (implícito — não documentado)
  Quando credenciais inválidas
```

### POST /user
```
Request:
  { "email": "string", "password": "string", "role": "PASTOR|SECRETARIO|TESOUREIRO" }

Response 201:
  {
    "id": "uuid",
    "email": "string",
    "roles": ["PASTOR"],
    "enabled": true
  }
```

### DELETE /user/email/{email}
```
Response 200: (sem body)
  Soft delete — marca enabled=false
```

---

## Fluxo Interno

### Login
```
POST /auth/login
  └── AuthController.login(LoginRequestDTO)
       └── AuthService.login(email, password)
            ├── UserRepository.findByEmail(email)
            │     └── [UserNotFoundException se não encontrado]
            ├── BCryptPasswordEncoder.matches(password, user.password)
            │     └── [RuntimeException se senha inválida]
            └── retorna LoginResponseDTO { userId, email, roles }
```

### Criação de Usuário
```
POST /user
  └── UserController.create(CreateUserRequestDTO)
       └── UserService.createUser(dto)
            ├── UserRepository.existsByEmail(email) → [exceção se duplicado]
            ├── RoleRepository.findByName(dto.role) → Role entity
            ├── BCryptPasswordEncoder.encode(dto.password)
            ├── User.builder()...build()
            └── UserRepository.save(user) → UserResponseDTO
```

---

## Dependências

- **PostgreSQL** (`authdb` na porta 5433) — obrigatório
- **Eureka Server** (8761) — para registro e descoberta
- **Gateway** — recebe requisições roteadas por `/auth/**` e `/user/**`

---

## Problemas Encontrados

| Problema | Severidade | Descrição |
|----------|------------|-----------|
| Login não gera JWT | 🔴 Crítico | Sem token, o gateway não pode validar identidade |
| Sem @Valid nos DTOs | 🟠 Alto | Email inválido ou senha curta são aceitos |
| Sem tratamento global de exceções | 🟠 Alto | Stacktraces expostos ao cliente |
| Senha sem política de força | 🟡 Médio | Qualquer string vira senha válida |
| Sem HTTPS configurado | 🟡 Médio | Credenciais trafegam em texto claro sem TLS |
| Credenciais DB hardcoded | 🔴 Crítico | `postgres/postgres` no código |
| Sem auditoria de login | 🟡 Médio | Tentativas de acesso não são registradas |

---

## Melhorias Sugeridas

### Prioridade Alta

1. **Implementar geração de JWT:**
   ```java
   // JwtService.java
   public String generateToken(User user) {
       return Jwts.builder()
           .subject(user.getId().toString())
           .claim("email", user.getEmail())
           .claim("roles", user.getRoles().stream().map(r -> r.getName().name()).toList())
           .issuedAt(new Date())
           .expiration(new Date(System.currentTimeMillis() + 3600_000))
           .signWith(secretKey)
           .compact();
   }
   ```

2. **Atualizar LoginResponseDTO para incluir o token:**
   ```json
   {
     "accessToken": "eyJ...",
     "tokenType": "Bearer",
     "expiresIn": 3600,
     "userId": "uuid",
     "email": "...",
     "roles": [...]
   }
   ```

3. **Adicionar @Valid nos controllers e anotações nos DTOs**

4. **Implementar GlobalExceptionHandler**

### Prioridade Média

5. Adicionar Refresh Token endpoint
6. Implementar lockout por tentativas excessivas (Redis counter)
7. Adicionar log de auditoria de logins
8. Externalizar credenciais DB via variáveis de ambiente

---

## Nível de Criticidade

🔴 **Alta** — É o guardião da identidade de todo o sistema. Sem JWT, toda a cadeia de autenticação é inexistente. Deve ser o primeiro serviço a ser corrigido depois do gateway.
