# 05 — Riscos Arquiteturais e Técnicos

---

## Risco 1 — Ausência de JWT e Autenticação Real

**Severidade:** 🔴 CRÍTICO

**Descrição:**  
O `auth-service` realiza verificação de senha com BCrypt, mas retorna apenas dados do usuário (userId, email, roles) — **não gera nem assina um JWT**. O gateway possui a dependência `spring-boot-starter-oauth2-resource-server`, porém a `SecurityConfig` do gateway libera todos os endpoints sem validação (`anyExchange().permitAll()`).

**Situação atual:**
```java
// SecurityConfig do gateway — estado atual
.authorizeExchange(exchanges -> exchanges
    .anyExchange().permitAll()  // ← Tudo liberado
)
```

**Impacto técnico:**  
Qualquer usuário ou script pode chamar qualquer endpoint do sistema sem nenhuma credencial. Os dados de membros, transações financeiras e usuários estão completamente expostos.

**Risco futuro:**  
Em produção, isso representa violação da LGPD (dados pessoais de membros) e comprometimento total do sistema.

**Benefício da correção:**  
Implementar geração de JWT no auth-service + validação no gateway fecha o perímetro de segurança. Apenas tokens válidos e assinados conseguem acessar os microsserviços.

---

## Risco 2 — Credenciais Hardcoded no Código-Fonte

**Severidade:** 🔴 CRÍTICO

**Descrição:**  
Todas as strings de conexão com o banco de dados contêm usuário e senha em texto plano nos arquivos `application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/memberdb
spring.datasource.username=postgres
spring.datasource.password=postgres
```

Ocorre nos três serviços com banco (auth, member, financial).

**Impacto técnico:**  
Se o repositório for exposto (acidentalmente público ou acesso indevido), as credenciais de banco são comprometidas imediatamente.

**Risco futuro:**  
Credenciais de produção em código-fonte são uma das principais causas de vazamento de dados em empresas.

**Benefício da correção:**  
Migrar para variáveis de ambiente (`${DB_PASSWORD}`) ou um serviço de secrets (HashiCorp Vault, AWS Secrets Manager) elimina esse vetor de ataque.

---

## Risco 3 — CORS Não Configurado

**Severidade:** 🔴 CRÍTICO

**Descrição:**  
Nenhum serviço configura CORS (Cross-Origin Resource Sharing). Quando um frontend (browser) tentar consumir a API de outra origem, receberá erros de CORS.

**Impacto técnico:**  
Bloqueio completo de qualquer frontend web que não esteja no mesmo domínio/porta que o gateway.

**Risco futuro:**  
O frontend ficará inutilizável em ambiente de produção ou staging com domínios separados.

**Benefício da correção:**  
Configurar CORS no gateway centraliza a política para todos os serviços:
```java
http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
```

---

## Risco 4 — SPOF no Eureka (Single Point of Failure)

**Severidade:** 🟠 ALTO

**Descrição:**  
Há uma única instância do `discovery-server`. Se ela cair, o gateway perde a capacidade de resolver os endereços dos microsserviços. Embora o Spring Cloud Gateway faça cache local por um período, reconexões e novas instâncias de serviço não serão descobertas.

**Impacto técnico:**  
Indisponibilidade total do ecossistema em caso de falha do Eureka.

**Risco futuro:**  
Em produção, qualquer restart do discovery-server causa janela de instabilidade.

**Benefício da correção:**  
Configurar Eureka em modo cluster (peer-to-peer) com ao menos 2 réplicas. Em Kubernetes, o Service Discovery nativo é uma alternativa superior.

---

## Risco 5 — Circuit Breaker Não Configurado

**Severidade:** 🟠 ALTO

**Descrição:**  
O gateway possui `resilience4j-spring-boot3` como dependência, mas nenhum `CircuitBreakerFilter`, `RetryGatewayFilterFactory` ou configuração de `resilience4j` foi implementada.

**Impacto técnico:**  
Se o `financial-service` ou qualquer outro serviço ficar lento ou cair, o gateway continuará tentando rotear requisições indefinidamente, acumulando threads bloqueadas e potencialmente derrubando o próprio gateway (efeito cascata).

**Risco futuro:**  
Uma falha em um microsserviço pode derrubar toda a plataforma.

**Benefício da correção:**  
Configurar circuit breakers por rota:
```yaml
filters:
  - name: CircuitBreaker
    args:
      name: financial-service-cb
      fallbackUri: forward:/fallback/financial
```

---

## Risco 6 — `createdBy` com UUID Aleatório no financial-service

**Severidade:** 🟠 ALTO

**Descrição:**  
No `TransactionMapper` do financial-service, o campo `createdBy` (que deveria identificar quem criou a transação) está sendo preenchido com `UUID.randomUUID()`:

```java
// Código atual — financial-service TransactionMapper
.createdBy(UUID.randomUUID())  // TODO: pegar do contexto de segurança
```

**Impacto técnico:**  
Nenhuma transação financeira possui rastreabilidade de autoria. O campo `createdBy` é inútil. Auditoria financeira é impossível.

**Risco futuro:**  
Dados financeiros sem rastreabilidade violam boas práticas de contabilidade e auditoria.

**Benefício da correção:**  
Implementar propagação do `X-User-Id` pelo gateway e uso correto no service.

---

## Risco 7 — Inconsistência de Versões Spring Boot/Cloud

**Severidade:** 🟡 MÉDIO

**Descrição:**  
Os serviços usam versões diferentes e incompatíveis de Spring Boot e Spring Cloud:

| Serviço | Spring Boot | Spring Cloud |
|---------|-------------|--------------|
| discovery-server | 3.5.8 | 2025.0.0 |
| gateway | 3.4.4 | 2024.0.0 |
| auth-service | 3.5.9 | 2025.0.1 |
| member-service | **3.3.2** | **2023.0.3** |
| financial-service | 3.5.9 | 2025.0.1 |

**Impacto técnico:**  
O `member-service` usa Spring Boot 3.3.2 com Spring Cloud 2023.0.3, enquanto os demais usam versões de 2025. Isso pode causar comportamentos diferentes em serialização JSON, tratamento de erros do Eureka e compatibilidade de filtros do gateway.

**Risco futuro:**  
Bugs difíceis de diagnosticar que só aparecem em integrações entre serviços.

**Benefício da correção:**  
Criar um `parent-pom` compartilhado com `dependencyManagement` definindo versões únicas para todo o ecossistema.

---

## Risco 8 — Ausência de Tratamento Global de Exceções (parcial)

**Severidade:** 🟡 MÉDIO

**Descrição:**  
Apenas o `member-service` possui `GlobalExceptionHandler` com `@RestControllerAdvice`. O `auth-service` e o `financial-service` não possuem. Erros não tratados retornam stacktraces completas ou respostas padrão do Spring sem estrutura definida.

**Impacto técnico:**  
Respostas de erro inconsistentes entre serviços. Stacktraces expostos vazam informações de implementação para o cliente.

**Benefício da correção:**  
Implementar `GlobalExceptionHandler` padronizado em todos os serviços, retornando um objeto de erro consistente:
```json
{
  "timestamp": "2026-05-27T10:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Email já cadastrado",
  "path": "/user"
}
```

---

## Risco 9 — Ausência de Observabilidade

**Severidade:** 🟡 MÉDIO

**Descrição:**  
- Nenhum serviço está enviando logs estruturados
- Distributed tracing (Zipkin/Jaeger) tem dependência no gateway mas não está configurado
- Não há Correlation ID propagado entre serviços
- Métricas de aplicação não estão exportadas para nenhum sistema (Prometheus, Grafana)

**Impacto técnico:**  
Quando houver falha em produção, será impossível rastrear o fluxo de uma requisição através dos serviços. Diagnóstico de problemas será manual e lento.

**Risco futuro:**  
MTTD (Mean Time To Detect) e MTTR (Mean Time To Recover) altos. Problemas silenciosos não serão detectados até que usuários reclamem.

**Benefício da correção:**  
OpenTelemetry + Zipkin/Jaeger + log estruturado (Logback JSON) + Prometheus/Grafana formam uma stack de observabilidade completa.

---

## Risco 10 — Ausência de Testes Automatizados

**Severidade:** 🟡 MÉDIO

**Descrição:**  
- Os arquivos de test (`*Tests.java`) existem mas o conteúdo está mínimo ou comentado
- Não há testes unitários de services ou mappers
- Não há testes de integração
- Não há cobertura de código mensurada

**Impacto técnico:**  
Qualquer refatoração ou nova feature pode introduzir regressões sem detecção automatizada.

**Risco futuro:**  
Custo de manutenção aumenta exponencialmente com a base de código. Bugs em produção.

---

## Risco 11 — `spring-boot-devtools` no Gateway

**Severidade:** 🟡 MÉDIO

**Descrição:**  
O gateway tem `spring-boot-devtools` como dependência sem scope. Esta ferramenta recarrega o classloader a cada mudança e **nunca deve ser incluída em produção**.

**Impacto técnico:**  
Vazamento de memória, comportamento imprevisível em ambiente produtivo, aumento de consumo de CPU.

**Benefício da correção:**  
Remover ou restringir ao perfil `dev`:
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-devtools</artifactId>
  <scope>runtime</scope>
  <optional>true</optional>
</dependency>
```

---

## Risco 12 — URL do Eureka Hardcoded para localhost

**Severidade:** 🟡 MÉDIO

**Descrição:**  
Todos os serviços têm:
```properties
eureka.client.service-url.defaultZone=http://localhost:8761/eureka
```

**Impacto técnico:**  
Em Docker, Kubernetes ou qualquer ambiente que não seja a máquina local, os serviços não conseguirão se registrar no Eureka.

**Benefício da correção:**  
```properties
eureka.client.service-url.defaultZone=${EUREKA_URL:http://localhost:8761/eureka}
```

---

## Risco 13 — Funcionalidade de Anexos Incompleta (financial-service)

**Severidade:** 🟡 MÉDIO

**Descrição:**  
O schema do banco tem a tabela `attachments` e existe `AttachmentRepository`, mas não há endpoint de upload/download exposto. O MinIO está como dependência sem configuração de cliente.

**Impacto técnico:**  
Funcionalidade prometida pelo modelo de dados (comprovantes de transações) não está disponível.

---

## Risco 14 — Sem Validação de Entrada com `@Valid`

**Severidade:** 🟡 MÉDIO

**Descrição:**  
Os DTOs de request não usam anotações de validação Jakarta (`@NotNull`, `@Email`, `@Size`, `@Positive`). Os controllers não ativam `@Valid` nas assinaturas.

**Impacto técnico:**  
Dados inválidos (email sem @, valores negativos, campos nulos) são aceitos e persistidos no banco.

**Exemplos de risco:**
- Transação com `amount = -1000`
- Membro com `cpf = "abc"`
- Usuário com `email = "naoéumemail"`

---

## Resumo de Riscos por Severidade

| # | Risco | Severidade |
|---|-------|------------|
| 1 | Ausência de JWT/Autenticação | 🔴 Crítico |
| 2 | Credenciais hardcoded | 🔴 Crítico |
| 3 | CORS não configurado | 🔴 Crítico |
| 4 | SPOF no Eureka | 🟠 Alto |
| 5 | Circuit Breaker ausente | 🟠 Alto |
| 6 | createdBy com UUID aleatório | 🟠 Alto |
| 7 | Versões Spring inconsistentes | 🟡 Médio |
| 8 | GlobalExceptionHandler parcial | 🟡 Médio |
| 9 | Ausência de observabilidade | 🟡 Médio |
| 10 | Ausência de testes | 🟡 Médio |
| 11 | devtools no gateway | 🟡 Médio |
| 12 | URL Eureka hardcoded | 🟡 Médio |
| 13 | Anexos incompletos | 🟡 Médio |
| 14 | Sem validação de entrada | 🟡 Médio |
