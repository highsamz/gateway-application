# 06 — Melhorias Recomendadas antes de Produção

---

## Bloco 1 — Segurança (BLOQUEADORES)

### 1.1 Implementar Geração de JWT no auth-service

**Por que:** O login atual retorna dados do usuário sem token. Não há como autenticar requisições subsequentes.

**O que fazer:**
1. Adicionar dependência `jjwt` ou usar `spring-security-oauth2-jose`
2. Criar `JwtService` com métodos `generateToken(User)` e `validateToken(String)`
3. Retornar `{ "accessToken": "...", "tokenType": "Bearer", "expiresIn": 3600 }` no login
4. Adicionar Refresh Token para renovação de sessão sem re-login

**Impacto:** Habilita autenticação real em todo o ecossistema.

---

### 1.2 Habilitar Validação de JWT no Gateway

**Por que:** Com o gateway liberando tudo, qualquer requisição sem credencial chega aos microsserviços.

**O que fazer:**
```java
// SecurityConfig do gateway
http.oauth2ResourceServer(oauth2 ->
    oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter()))
)
.authorizeExchange(exchanges -> exchanges
    .pathMatchers("/auth/login").permitAll()
    .anyExchange().authenticated()
)
```

**E configurar a chave de validação:**
```properties
spring.security.oauth2.resourceserver.jwt.secret=<chave-secreta>
```

---

### 1.3 Propagar Identidade para Downstream

**Por que:** Os microsserviços precisam saber quem é o usuário autenticado sem re-validar o JWT.

**O que fazer:**  
Criar um `GatewayFilter` que extrai claims do JWT validado e injeta headers:
```java
// Após validação do JWT no gateway
exchange.mutate().request(request ->
    request.header("X-User-Id", userId)
           .header("X-User-Email", email)
           .header("X-User-Roles", roles)
).build()
```

---

### 1.4 Configurar CORS no Gateway

**Por que:** Sem CORS, o frontend web não conseguirá fazer requisições ao gateway de outro domínio.

**O que fazer:**
```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("https://noiva-de-cristo.app"));
    config.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    return new UrlBasedCorsConfigurationSource() {{ registerCorsConfiguration("/**", config); }};
}
```

---

### 1.5 Remover Credenciais Hardcoded

**Por que:** Senhas no código-fonte são uma vulnerabilidade crítica de segurança.

**O que fazer:**
```properties
# application.properties — usar variáveis de ambiente
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/memberdb}
spring.datasource.username=${DB_USER:postgres}
spring.datasource.password=${DB_PASSWORD:postgres}
```

E definir essas variáveis via Docker, Kubernetes Secrets ou .env local (não comitado).

---

### 1.6 Implementar Rate Limiting no Gateway

**Por que:** Sem rate limiting, qualquer script pode fazer milhares de requisições e derrubar os serviços.

**O que fazer:**
```yaml
# application.yml do gateway
spring.cloud.gateway.routes:
  - id: auth-service
    filters:
      - name: RequestRateLimiter
        args:
          redis-rate-limiter.replenishRate: 10
          redis-rate-limiter.burstCapacity: 20
```

---

## Bloco 2 — Resiliência

### 2.1 Configurar Circuit Breakers no Gateway

**Por que:** Sem circuit breaker, uma falha em um microsserviço pode travar o gateway inteiro.

**O que fazer:**
```yaml
filters:
  - name: CircuitBreaker
    args:
      name: member-service-cb
      fallbackUri: forward:/fallback/members
      statusCodes:
        - SERVICE_UNAVAILABLE
        - GATEWAY_TIMEOUT
```

E criar endpoints de fallback com respostas degradadas.

---

### 2.2 Configurar Retry no Gateway

**Por que:** Falhas transitórias (timeout curto, restart de pod) causam erro desnecessário ao usuário.

```yaml
filters:
  - name: Retry
    args:
      retries: 3
      statuses: BAD_GATEWAY, SERVICE_UNAVAILABLE
      methods: GET
      backoff:
        firstBackoff: 100ms
        maxBackoff: 500ms
```

---

### 2.3 Alta Disponibilidade do Eureka

**Por que:** Eureka em instância única é SPOF.

**O que fazer:**  
Configurar Eureka com peer-to-peer replication (ao menos 2 instâncias) ou migrar para Kubernetes Service Discovery (elimina necessidade do Eureka).

---

## Bloco 3 — Observabilidade

### 3.1 Implementar Correlation ID

**Por que:** Sem correlation ID, impossível rastrear uma requisição através de múltiplos serviços nos logs.

**O que fazer:**  
Criar `GlobalFilter` no gateway que injeta header `X-Correlation-Id` (UUID) em todas as requisições. Cada serviço downstream propaga esse header no log de cada linha.

---

### 3.2 Configurar Distributed Tracing (OpenTelemetry + Zipkin)

**Por que:** As dependências Brave/Zipkin estão presentes no gateway mas sem configuração. Sem tracing, diagnóstico de latência é impossível.

**O que fazer:**
```properties
# gateway application.properties
management.tracing.sampling.probability=1.0
spring.zipkin.base-url=http://zipkin:9411
```

E adicionar tracing nos demais serviços também.

---

### 3.3 Logs Estruturados (JSON)

**Por que:** Logs em texto livre são difíceis de indexar e pesquisar. Logs JSON são processáveis por ELK/Loki/CloudWatch.

**O que fazer:**  
Adicionar `logstash-logback-encoder` e configurar `logback-spring.xml` com `LogstashEncoder`.

---

### 3.4 Health Checks em Todos os Serviços

**Por que:** O gateway e orquestradores precisam saber se um serviço está saudável para rotear tráfego.

**O que fazer:**  
Adicionar `spring-boot-starter-actuator` em todos os serviços e configurar:
```properties
management.endpoints.web.exposure.include=health,info,metrics
management.endpoint.health.show-details=always
```

---

### 3.5 Métricas com Prometheus + Grafana

**Por que:** Sem métricas, impossível detectar degradação de performance antes de virar incidente.

**O que fazer:**  
Adicionar `micrometer-registry-prometheus` e configurar Grafana com dashboards de latência, throughput e error rate.

---

## Bloco 4 — Qualidade de Código

### 4.1 Tratamento Global de Exceções em Todos os Serviços

**Por que:** Apenas `member-service` tem `GlobalExceptionHandler`. Os demais retornam stacktraces.

**O que fazer:**  
Criar `GlobalExceptionHandler` padronizado com `@RestControllerAdvice` em `auth-service` e `financial-service`, retornando `ApiErrorResponse` consistente.

---

### 4.2 Validação de Entrada com Jakarta Validation

**Por que:** Dados inválidos são aceitos e persistidos sem nenhuma rejeição.

**O que fazer:**
```java
// DTOs com anotações de validação
public record CreateUserRequestDTO(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8) String password,
    @NotNull RoleName role
) {}

// Controllers com @Valid
@PostMapping("/user")
public ResponseEntity<UserResponseDTO> create(@Valid @RequestBody CreateUserRequestDTO dto) { ... }
```

---

### 4.3 Padronização de Responses HTTP

**Por que:** Cada serviço retorna dados em formatos ligeiramente diferentes. O cliente precisa tratar cada caso.

**O que fazer:**  
Definir um envelope padrão:
```json
{
  "data": { ... },
  "timestamp": "2026-05-27T10:00:00Z",
  "correlationId": "uuid"
}
```

Ou (mais simples) garantir que todos os erros seguem o mesmo schema `ApiErrorResponse`.

---

### 4.4 Unificar Versões Spring Boot/Cloud em Parent POM

**Por que:** Versões diferentes causam comportamentos divergentes.

**O que fazer:**  
Criar `ecosystem-parent/pom.xml`:
```xml
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

---

### 4.5 Implementar Interfaces nos Services

**Por que:** Services concretos sem interface dificultam testes unitários com mocks e violam DIP do SOLID.

**O que fazer:**
```java
public interface MemberService {
    MemberResponseDTO create(MemberRequestDTO dto);
    // ...
}

public class MemberServiceImpl implements MemberService { ... }
```

---

## Bloco 5 — Infraestrutura e DevOps

### 5.1 Docker Compose completo para o Ecossistema

**Por que:** Apenas o `member-service` tem Docker. Não há como subir todo o ecossistema com um único comando.

**O que fazer:**  
Criar `docker-compose.yml` na raiz do ecossistema com todos os serviços, bancos, Eureka, MinIO e Zipkin.

---

### 5.2 Dockerizar Todos os Serviços

**O que fazer:**  
Criar `Dockerfile` padronizado (multi-stage build) para cada serviço:
```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:21-jre-alpine
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

### 5.3 CI/CD Pipeline

**Por que:** Sem automação, deploys são manuais e propensos a erro.

**O que fazer:**  
Criar pipeline GitHub Actions (ou GitLab CI) que:
1. Compila e executa testes
2. Analisa cobertura de código (JaCoCo)
3. Faz build da imagem Docker
4. Publica no registry (DockerHub, ECR, GCR)
5. Deploy automatizado (ambiente de staging)

---

### 5.4 Versionamento de API

**Por que:** Sem versão na URL (`/v1/members`), qualquer breaking change quebra todos os clientes.

**O que fazer:**  
Adicionar prefixo de versão nas rotas do gateway e nos controllers:
```
/v1/auth/**
/v1/user/**
/v1/members/**
/v1/transactions/**
```

---

### 5.5 Config Server

**Por que:** Configurações distribuídas em múltiplos `application.properties` são difíceis de gerenciar e auditadas separadamente.

**O que fazer:**  
Adicionar `spring-cloud-config-server` ao ecossistema, centralizando configurações em um repositório Git.

---

## Bloco 6 — Funcionalidades Incompletas

### 6.1 Implementar Upload/Download de Anexos (financial-service)

**O que fazer:**  
1. Configurar cliente MinIO com endpoint/credenciais via variáveis de ambiente
2. Criar `AttachmentService` com métodos de upload e download
3. Expor endpoints `POST /transactions/{id}/attachments` e `GET /transactions/{id}/attachments/{attachmentId}`

---

### 6.2 Corrigir `createdBy` no financial-service

**O que fazer:**  
Ler o header `X-User-Id` propagado pelo gateway e usar no `TransactionMapper`:
```java
public Transaction toEntity(CreateTransactionRequestDTO dto, UUID userId) {
    return Transaction.builder()
        .createdBy(userId)  // do header, não randomUUID()
        // ...
        .build();
}
```

---

### 6.3 Refresh Token no auth-service

**Por que:** Sem refresh token, o usuário precisa fazer login novamente sempre que o JWT expirar.

**O que fazer:**  
Implementar endpoint `POST /auth/refresh` que aceita um refresh token válido e retorna um novo access token.

---

## Resumo de Prioridade

| # | Melhoria | Bloco | Prioridade |
|---|----------|-------|------------|
| 1.1 | Gerar JWT no auth-service | Segurança | 🔴 Alta |
| 1.2 | Validar JWT no gateway | Segurança | 🔴 Alta |
| 1.3 | Propagar identidade nos headers | Segurança | 🔴 Alta |
| 1.4 | Configurar CORS | Segurança | 🔴 Alta |
| 1.5 | Remover credenciais hardcoded | Segurança | 🔴 Alta |
| 6.2 | Corrigir createdBy | Funcional | 🔴 Alta |
| 4.1 | GlobalExceptionHandler em todos | Qualidade | 🟠 Média |
| 4.2 | Validação de entrada @Valid | Qualidade | 🟠 Média |
| 2.1 | Circuit Breaker no gateway | Resiliência | 🟠 Média |
| 3.1 | Correlation ID | Observabilidade | 🟠 Média |
| 3.2 | Tracing OpenTelemetry/Zipkin | Observabilidade | 🟠 Média |
| 3.3 | Logs estruturados JSON | Observabilidade | 🟠 Média |
| 4.4 | Unificar versões Spring | Qualidade | 🟠 Média |
| 5.1 | Docker Compose completo | DevOps | 🟠 Média |
| 5.2 | Dockerizar todos os serviços | DevOps | 🟠 Média |
| 1.6 | Rate Limiting | Segurança | 🟡 Baixa |
| 2.2 | Retry no gateway | Resiliência | 🟡 Baixa |
| 3.4 | Health Checks | Observabilidade | 🟡 Baixa |
| 3.5 | Prometheus + Grafana | Observabilidade | 🟡 Baixa |
| 4.3 | Padronização de responses | Qualidade | 🟡 Baixa |
| 4.5 | Interfaces nos Services | Qualidade | 🟡 Baixa |
| 5.3 | CI/CD Pipeline | DevOps | 🟡 Baixa |
| 5.4 | Versionamento de API | DevOps | 🟡 Baixa |
| 5.5 | Config Server | DevOps | 🟡 Baixa |
| 6.1 | Upload/Download de anexos | Funcional | 🟡 Baixa |
| 6.3 | Refresh Token | Segurança | 🟡 Baixa |
