# 02 — Padrões Arquiteturais

---

## 1. Padrões Identificados

### 1.1 Microsserviços ✅

**Status:** Implementado corretamente.

Cada serviço possui:
- Responsabilidade de domínio única (auth, membros, finanças)
- Banco de dados próprio (Database per Service)
- Deploy independente
- Processo de registro e descoberta via Eureka

**Observação:** A separação de domínios é clara e bem delimitada.

---

### 1.2 API Gateway ✅ / ⚠️

**Status:** Implementado, porém com falhas críticas de segurança.

O `gateway-application` implementa o padrão API Gateway corretamente em termos de roteamento e balanceamento de carga. Contudo, a **camada de autenticação/autorização não está ativa**, tornando o gateway um simples proxy reverso sem valor de segurança.

**O que funciona:**
- Roteamento via predicados de path
- Load balancing com `lb://`
- Integração com Eureka para descoberta dinâmica

**O que falta:**
- Validação de JWT
- Propagação de identidade (headers de usuário) para downstream
- Filtros de autorização por rota
- Rate limiting
- CORS

---

### 1.3 Service Discovery (Eureka) ✅

**Status:** Implementado e funcional.

Todos os serviços registram-se no `discovery-server` (Eureka). O gateway usa `lb://` para resolução dinâmica de endereços. Configuração consistente de `prefer-ip-address=true`.

---

### 1.4 Database per Service ✅

**Status:** Implementado corretamente.

Cada serviço possui banco de dados isolado em porta diferente:
- `memberdb` na porta 5432
- `authdb` na porta 5433
- `financialdb` na porta 5434

---

### 1.5 Arquitetura em Camadas (Layered Architecture) ✅

**Status:** Implementado em todos os serviços.

Todos os serviços seguem a separação em camadas:
```
Controller (HTTP)
    └── Service (Regras de negócio)
         └── Repository (Acesso a dados)
              └── Entity (Modelo de domínio)
```

Com uso de DTOs para entrada/saída, evitando exposição direta de entidades.

---

### 1.6 DTO Pattern ✅

**Status:** Implementado em todos os serviços.

Todos os serviços utilizam DTOs separados para:
- Request: `*RequestDTO` (entrada de dados)
- Response: `*ResponseDTO` (saída de dados)

As entidades JPA nunca são expostas diretamente nas APIs, o que é correto.

---

### 1.7 Mapper Pattern ✅

**Status:** Implementado em todos os serviços.

Cada serviço possui classes `*Mapper` responsáveis pela conversão entre DTOs e entidades. Implementadas manualmente (sem MapStruct), o que gera código repetitivo mas funcional.

---

### 1.8 Repository Pattern ✅

**Status:** Implementado em todos os serviços.

Uso de `JpaRepository` do Spring Data. Todos os repositórios são interfaces, delegando a implementação ao Spring Data JPA. Consultas customizadas via métodos de naming convention (`findByEmail`, `findByActiveTrue`, etc.).

---

### 1.9 Soft Delete ✅

**Status:** Implementado em todos os serviços com persistência.

| Serviço | Campo de soft delete |
|---------|---------------------|
| auth-service | `enabled` (Boolean) |
| member-service | `status` (ATIVO/INATIVO) |
| financial-service | `active` (Boolean) |

**Risco:** Inconsistência no campo usado — cada serviço inventou sua própria convenção. Em uma query cross-service isso seria problemático.

---

### 1.10 Circuit Breaker (Resilience4j) ⚠️

**Status:** Dependência presente no gateway, **não configurada**.

O `resilience4j-spring-boot3` está no `pom.xml` do gateway mas nenhum `@CircuitBreaker`, `@Retry` ou configuração em `application.properties` foi identificada. Isso significa que o sistema não possui resiliência real — uma falha em qualquer microsserviço derrubará o fluxo do gateway sem fallback.

---

### 1.11 SOLID ⚠️

**Status:** Parcialmente aplicado.

| Princípio | Status | Observação |
|-----------|--------|------------|
| Single Responsibility | ✅ | Controllers, Services e Repositories bem separados |
| Open/Closed | ⚠️ | Mappers manuais precisam ser alterados para cada novo campo |
| Liskov Substitution | ✅ | Uso de interfaces do Spring Data |
| Interface Segregation | ⚠️ | Services não possuem interfaces, dificultando testes e substituição |
| Dependency Inversion | ⚠️ | Services são injetados diretamente por implementação concreta, não por interface |

---

### 1.12 Clean Architecture / Hexagonal ❌

**Status:** Não implementado.

O projeto não separa domínio de infraestrutura — entidades JPA anotadas com `@Entity` estão misturadas com a lógica de domínio. Não há camadas de `domain`, `application`, `infrastructure` explícitas. Isso é aceitável para o estágio atual, mas limita testabilidade e evolução.

---

### 1.13 Event-Driven ❌

**Status:** Não implementado.

Não há Kafka, RabbitMQ ou qualquer mensageria configurada. Toda comunicação é síncrona via HTTP. Não há eventos de domínio.

---

### 1.14 CQRS ❌

**Status:** Não implementado. Não necessário para o estágio atual.

---

## 2. Resumo de Maturidade dos Padrões

| Padrão | Status | Prioridade de melhoria |
|--------|--------|------------------------|
| Microsserviços | ✅ Implementado | Baixa |
| API Gateway (roteamento) | ✅ Implementado | Baixa |
| API Gateway (segurança) | ❌ Incompleto | **Alta** |
| Service Discovery | ✅ Implementado | Baixa |
| Database per Service | ✅ Implementado | Baixa |
| Layered Architecture | ✅ Implementado | Baixa |
| DTO Pattern | ✅ Implementado | Baixa |
| Repository Pattern | ✅ Implementado | Baixa |
| Soft Delete | ⚠️ Inconsistente | Média |
| Circuit Breaker | ❌ Não configurado | **Alta** |
| SOLID (completo) | ⚠️ Parcial | Média |
| Event-Driven | ❌ Ausente | Baixa (futuro) |
| Observabilidade | ❌ Não configurada | **Alta** |
