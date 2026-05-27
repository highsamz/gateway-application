# 01 — Stack e Dependências

---

## 1. Linguagem e Plataforma

| Item | Versão | Observação |
|------|--------|------------|
| Java | 21 (LTS) | Usado em todos os serviços |
| Maven | Wrapper incluído | Build tool padrão |

---

## 2. Frameworks por Serviço

| Serviço | Spring Boot | Spring Cloud | Web Stack |
|---------|-------------|--------------|-----------|
| discovery-server | 3.5.8 | 2025.0.0 | Spring Web (servlet) |
| gateway-application | 3.4.4 | 2024.0.0 | WebFlux (reativo) |
| auth-service | 3.5.9 | 2025.0.1 | Spring Web (servlet) |
| member-service | 3.3.2 | 2023.0.3 | Spring Web (servlet) |
| financial-service | 3.5.9 | 2025.0.1 | Spring Web (servlet) |

> ⚠️ **Inconsistência crítica:** versões de Spring Boot e Spring Cloud divergem entre os serviços (3.3.2 até 3.5.9).  
> Isso pode causar incompatibilidades de comportamento, principalmente em serialização JSON, tratamento de erros e integração com Eureka.

---

## 3. Bibliotecas Principais

### 3.1 Infraestrutura / Cloud

| Biblioteca | Serviços que usam | Finalidade |
|-----------|-------------------|------------|
| `spring-cloud-starter-netflix-eureka-server` | discovery-server | Servidor de registro Eureka |
| `spring-cloud-starter-netflix-eureka-client` | gateway, auth, member, financial | Registro no Eureka |
| `spring-cloud-starter-gateway` | gateway | Roteamento reativo |
| `spring-cloud-starter-loadbalancer` | gateway | Balanceamento de carga client-side |
| `spring-boot-starter-webflux` | gateway | Stack reativa (Netty) |
| `spring-boot-starter-web` | auth, member, financial | Stack servlet (Tomcat) |

### 3.2 Persistência

| Biblioteca | Serviços que usam | Finalidade |
|-----------|-------------------|------------|
| `spring-boot-starter-data-jpa` | auth, member, financial | ORM com Hibernate |
| `postgresql` | auth, member, financial | Driver JDBC PostgreSQL |
| `flyway-core` | auth, member, financial | Migração de banco de dados |

### 3.3 Segurança

| Biblioteca | Serviços que usam | Finalidade |
|-----------|-------------------|------------|
| `spring-boot-starter-security` | auth, member, financial, gateway | Spring Security |
| `spring-boot-starter-oauth2-resource-server` | gateway | Validação JWT (não configurado) |
| `spring-security-crypto` | auth | BCrypt para hash de senhas |

### 3.4 Observabilidade

| Biblioteca | Serviços que usam | Finalidade |
|-----------|-------------------|------------|
| `spring-boot-starter-actuator` | gateway (e possivelmente outros) | Health checks, métricas |
| `micrometer-tracing-bridge-brave` | gateway | Bridge Micrometer → Brave |
| `zipkin-reporter-brave` | gateway | Envio de traces ao Zipkin |

### 3.5 Utilitários

| Biblioteca | Serviços que usam | Finalidade |
|-----------|-------------------|------------|
| `lombok` | todos | Redução de boilerplate |
| `springdoc-openapi-starter-webmvc-ui` | member-service | Swagger UI / OpenAPI 3 |
| `resilience4j-spring-boot3` | gateway | Circuit breaker, retry, rate limit |

### 3.6 Armazenamento de Arquivos

| Biblioteca | Serviço | Finalidade |
|-----------|---------|------------|
| `minio` v8.5.7 | financial-service | Storage de anexos (S3-compatible) |

---

## 4. Bancos de Dados

| Serviço | Banco | Porta | Tabelas principais |
|---------|-------|-------|--------------------|
| auth-service | `authdb` (PostgreSQL) | 5433 | users, roles, user_roles |
| member-service | `memberdb` (PostgreSQL) | 5432 | member |
| financial-service | `financialdb` (PostgreSQL) | 5434 | transactions, attachments |

### Estratégia de Migração

Todos os serviços com banco usam **Flyway** para versionamento do schema. Nenhum usa Liquibase.

---

## 5. Ferramentas de Infraestrutura

| Ferramenta | Papel no ecossistema | Status |
|-----------|----------------------|--------|
| **Eureka** (Netflix OSS) | Service Discovery | Configurado e em uso |
| **Spring Cloud Gateway** | API Gateway | Configurado e em uso |
| **Spring Cloud Load Balancer** | Balanceamento client-side | Configurado e em uso |
| **Resilience4j** | Circuit breaker, retry, rate limit | Dependência presente, **não configurado** |
| **Zipkin** | Distributed tracing | Dependência presente, **não configurado** |
| **MinIO** | Object storage (S3-compatible) | Dependência presente no financial-service |
| **Docker** | Containerização | Presente apenas no member-service |

---

## 6. Dependências com Riscos

| Dependência | Risco | Recomendação |
|-------------|-------|--------------|
| `spring-boot-devtools` no gateway | Nunca deve ir para produção — recarrega classloader e vaza dados | Remover ou colocar em `<scope>runtime</scope>` condicional ao perfil dev |
| Credenciais PostgreSQL hardcoded (`postgres/postgres`) | Exposição de acesso a banco em código-fonte | Migrar para variáveis de ambiente ou Vault |
| Versão `minio` 8.5.7 | Verificar vulnerabilidades conhecidas | Atualizar e auditar com `mvn dependency:tree` |
| Inconsistência de versões Spring Cloud | Comportamentos divergentes entre serviços | Unificar em uma única BOM version em um parent POM |
