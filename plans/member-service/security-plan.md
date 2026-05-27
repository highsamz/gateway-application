# Plano de Segurança — member-service

---

## Estado Atual

```java
// SecurityConfig atual — mesmo padrão dos outros serviços
http.csrf().disable()
    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
    .httpBasic(Customizer.withDefaults());
```

Qualquer pessoa pode criar, editar, listar ou excluir membros sem autenticação.

---

## [SEC-01] Remover Credenciais Hardcoded

```properties
# Antes:
spring.datasource.password=postgres

# Depois:
spring.datasource.password=${DB_PASSWORD}
```

Ver [implementation-plan.md](./implementation-plan.md) Task 1.

---

## [SEC-MBR-01] Autorização por Papel via X-User-Roles

### Matriz de permissões para member-service

| Endpoint | PASTOR | SECRETARIO | TESOUREIRO |
|----------|--------|------------|------------|
| `POST /members` | ✅ | ✅ | ❌ |
| `GET /members/**` | ✅ | ✅ | ❌ |
| `PUT /members/{id}` | ✅ | ✅ | ❌ |
| `DELETE /members/{id}` | ✅ | ❌ | ❌ |

A proteção primária vem do gateway (`SecurityConfig` com `pathMatchers`). O member-service pode adicionar defesa em profundidade via interceptor:

```java
@Component
public class MemberRoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String rolesHeader = request.getHeader("X-User-Roles");

        if (rolesHeader == null || rolesHeader.isBlank()) {
            response.sendError(401, "Missing authentication");
            return false;
        }

        List<String> roles = Arrays.asList(rolesHeader.split(","));

        // Apenas PASTOR e SECRETARIO acessam membros
        if (!roles.contains("PASTOR") && !roles.contains("SECRETARIO")) {
            response.sendError(403, "Insufficient permissions for member management");
            return false;
        }

        // DELETE apenas para PASTOR
        if ("DELETE".equals(request.getMethod()) && !roles.contains("PASTOR")) {
            response.sendError(403, "Only PASTOR can deactivate members");
            return false;
        }

        return true;
    }
}
```

```java
// WebMvcConfig.java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Autowired
    private MemberRoleInterceptor memberRoleInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(memberRoleInterceptor)
                .addPathPatterns("/members/**")
                .excludePathPatterns("/actuator/**");
    }
}
```

---

## Dados Sensíveis na Entidade Member

O modelo de dados do member-service contém dados pessoais protegidos pela LGPD:

| Campo | Classificação LGPD | Proteção |
|-------|-------------------|----------|
| nome | Dado pessoal | Controle de acesso |
| dataNascimento | Dado pessoal | Controle de acesso |
| cpf | Dado pessoal sensível | Controle de acesso + nunca logar |
| rg | Dado pessoal | Controle de acesso |
| email | Dado pessoal | Controle de acesso |
| telefone | Dado pessoal | Controle de acesso |

### Regras de Log para Dados LGPD

```java
// NUNCA fazer:
log.info("Creating member: {}", member);  // expõe CPF nos logs!

// Fazer:
log.info("Creating member id={} igreja={}", member.getId(), member.getIgreja());
```

### Mascaramento de CPF em logs

```java
private String maskCpf(String cpf) {
    if (cpf == null || cpf.length() < 11) return "***";
    return cpf.substring(0, 3) + ".***.***-" + cpf.substring(9);
}
```

---

## Proteção do Banco de Dados

Em produção:
1. Criar usuário PostgreSQL específico para o member-service:
```sql
CREATE USER memberuser WITH PASSWORD 'senha-forte';
GRANT CONNECT ON DATABASE memberdb TO memberuser;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO memberuser;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO memberuser;
-- NÃO dar DELETE real — o soft delete usa UPDATE
```

2. Backup diário do banco (dados LGPD têm obrigação de proteção):
```bash
pg_dump -h localhost -U memberuser memberdb | gzip > backup_members_$(date +%Y%m%d).sql.gz
```

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
                .anyRequest().permitAll()  // proteção real vem do gateway + interceptor
            );
        return http.build();
    }
}
```
