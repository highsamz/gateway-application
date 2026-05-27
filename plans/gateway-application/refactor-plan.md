# Plano de Refatoração — gateway-application

---

## Estado Atual da Estrutura de Código

O gateway tem apenas 2 arquivos Java e está em estágio inicial. As refatorações aqui são tanto correções de problemas existentes quanto preparação estrutural para as novas funcionalidades.

```
src/main/java/br/com/gateway_apllication/
├── GatewayApllicationApplication.java   ← typo no nome do pacote
└── config/
    └── SecurityConfig.java               ← a ser reescrito
```

---

## [REFACTOR-GW-01] Organização de Pacotes

### Problema
O pacote raiz `br.com.gateway_apllication` tem um typo histórico (`apllication` em vez de `application`). Embora seja um typo, refatorar o nome do pacote principal envolve risco de regressão e não agrega valor funcional.

### Decisão
**Não refatorar o nome do pacote** neste momento. O typo é cosmético e a mudança quebraria imports em todas as classes futuras sem benefício funcional imediato.

**Documentar o typo** para correção em uma futura refatoração maior (ex.: quando houver cobertura de testes).

### Estrutura de pacotes recomendada para novos arquivos

```
br.com.gateway_apllication/
├── GatewayApllicationApplication.java
├── config/
│   └── SecurityConfig.java              ← reescrever
├── filter/
│   ├── CorrelationIdFilter.java         ← novo [OBS-01]
│   └── JwtPropagationFilter.java        ← novo [GW-03]
└── controller/
    └── FallbackController.java          ← novo [GW-04]
```

---

## [REFACTOR-GW-02] Migrar application.properties para application.yml

### Problema
O `application.properties` atual usa índices numéricos para rotas (`routes[0]`, `routes[1]`, `routes[2]`) que são difíceis de ler e manter. YAML permite hierarquia clara e comentários.

### application.properties atual (problemático)
```properties
spring.cloud.gateway.routes[0].id=member-service
spring.cloud.gateway.routes[0].uri=lb://member-service
spring.cloud.gateway.routes[0].predicates[0]=Path=/members/**

spring.cloud.gateway.routes[1].id=auth-service
spring.cloud.gateway.routes[1].uri=lb://auth-service
spring.cloud.gateway.routes[1].predicates[0]=Path=/user/**,/auth/**
```

### Vantagem do YAML
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: member-service
          uri: lb://member-service
          predicates:
            - Path=/members/**
          filters:
            - name: CircuitBreaker
              args:
                name: member-service-cb
                fallbackUri: forward:/fallback/members
```

**Estratégia:** Migrar ao implementar o Circuit Breaker (GW-04), já que a configuração de filtros é muito mais limpa em YAML.

---

## [REFACTOR-GW-03] Externalizar Configuração de Roles

### Problema
As roles (`PASTOR`, `SECRETARIO`, `TESOUREIRO`) estarão hardcoded no `SecurityConfig`. Se uma nova role for adicionada, será necessário alterar o código e fazer um novo deploy.

### Solução para MVP
Aceitar o hardcode por enquanto — as roles são parte do domínio e raramente mudam.

### Solução Futura
Extrair para configuração via `@ConfigurationProperties`:

```java
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
    List<String> pastorEndpoints,
    List<String> secretarioEndpoints,
    List<String> tesoureiroEndpoints
) {}
```

---

## [REFACTOR-GW-04] Extrair Configuração de CORS para Properties

### Problema
A lista de origens permitidas deve ser configurável por ambiente sem rebuild.

### Solução

```java
@Value("${app.cors.allowed-origins:http://localhost:3000}")
private String allowedOriginsConfig;

@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(Arrays.asList(allowedOriginsConfig.split(",")));
    // ...
}
```

```yaml
app:
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}
```

---

## [REFACTOR-GW-05] Remover spring-boot-devtools do Classpath de Produção

### Problema
Dependência atual no `pom.xml` sem scope:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
</dependency>
```

### Solução
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
    <scope>runtime</scope>
    <optional>true</optional>
</dependency>
```

A flag `<optional>true</optional>` impede que o `devtools` seja incluído no JAR final quando o projeto é usado como dependência. O Maven Maven não inclui dependências opcionais no JAR de produção.

---

## Ordem de Execução das Refatorações

```
1. [REFACTOR-GW-05] Remover devtools (2 linhas no pom.xml) — fazer imediatamente
2. [REFACTOR-GW-02] Migrar para YAML — fazer junto com GW-04 (Circuit Breaker)
3. [REFACTOR-GW-04] Externalizar CORS — fazer junto com GW-02
4. [REFACTOR-GW-03] Externalizar roles — adiar para pós-MVP
5. [REFACTOR-GW-01] Corrigir typo no pacote — adiar para quando houver testes
```
