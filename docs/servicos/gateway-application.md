# Serviço: gateway-application

**Criticidade:** 🔴 Alta (único ponto de entrada do ecossistema)

---

## Objetivo

Ser o ponto de entrada único (API Gateway) de todo o ecossistema, responsável por rotear requisições para os microsserviços corretos, aplicar políticas de segurança e transversalidades como rate limiting, CORS e observabilidade.

---

## Responsabilidades

### Corretas (implementadas)
- Roteamento de requisições por path predicate
- Load balancing client-side via Spring Cloud Load Balancer
- Integração com Eureka para descoberta dinâmica de serviços
- Stack reativa (WebFlux/Netty) para alta concorrência

### Ausentes (deveria implementar)
- Validação de JWT em cada requisição
- Propagação de identidade (X-User-Id, X-User-Roles) para downstream
- CORS
- Rate Limiting
- Circuit Breaker por rota
- Correlation ID
- Logs estruturados

---

## Stack

| Item | Versão |
|------|--------|
| Spring Boot | 3.4.4 |
| Spring Cloud | 2024.0.0 |
| Spring Cloud Gateway | 4.x (WebFlux) |
| Java | 21 |
| Web Stack | Reactive (Netty) |

---

## Roteamento Configurado

| ID da Rota | Path | Destino | Load Balance |
|------------|------|---------|--------------|
| `auth-service` | `/auth/**`, `/user/**` | `lb://auth-service` | ✅ |
| `member-service` | `/members/**` | `lb://member-service` | ✅ |
| `financial-service` | `/transactions/**` | `lb://financial-service` | ✅ |

**Discovery automático habilitado** — qualquer serviço no Eureka também fica acessível por `/{nome-do-servico}/**`.

---

## Fluxo Interno

```
1. Requisição HTTP chega na porta 8080
2. Spring Cloud Gateway avalia predicados de rota (path matching)
3. Rota correspondente encontrada → seleciona filtros e URI destino
4. Load Balancer resolve lb://nome → instância real (IP:porta via Eureka)
5. Requisição encaminhada ao microsserviço
6. Resposta devolvida ao cliente
```

**Fluxo esperado (com segurança implementada):**
```
1. Requisição chega
2. SecurityWebFilterChain verifica JWT no header Authorization
3. Se inválido → 401 Unauthorized (sem rotear)
4. Se válido → extrai claims, injeta X-User-Id e X-User-Roles
5. Segue o fluxo de roteamento normal
```

---

## Dependências

- **Eureka Server** (discovery-server:8761) — obrigatório para load balancing
- **auth-service** — (futuro) para validação de JWT offline ou delegada

---

## Problemas Encontrados

| Problema | Severidade | Descrição |
|----------|------------|-----------|
| SecurityConfig libera tudo | 🔴 Crítico | `anyExchange().permitAll()` — nenhuma requisição é autenticada |
| CSRF desabilitado sem compensação | 🔴 Crítico | CSRF off sem autenticação stateless real |
| CORS ausente | 🔴 Crítico | Frontend web não conseguirá fazer chamadas de outro domínio |
| JWT dependency sem uso | 🟠 Alto | `oauth2-resource-server` está no pom mas não está configurado |
| Resilience4j sem config | 🟠 Alto | Circuit breaker disponível mas sem nenhuma rota configurada |
| devtools em produção | 🟡 Médio | `spring-boot-devtools` não deve estar em produção |
| URL Eureka hardcoded | 🟡 Médio | `localhost:8761` quebra em qualquer outro ambiente |
| Typo no nome do pacote | 🟢 Baixo | `gateway_apllication` (duplo 'l') — não afeta funcionalidade |

---

## Melhorias Sugeridas

### Prioridade Alta
1. Habilitar validação JWT:
   ```java
   http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
   .authorizeExchange(ex -> ex
       .pathMatchers("/auth/login").permitAll()
       .anyExchange().authenticated()
   )
   ```

2. Criar filtro de propagação de identidade:
   ```java
   @Component
   public class AuthHeaderFilter implements GlobalFilter {
       public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
           // extrai JWT claims e injeta X-User-Id no header downstream
       }
   }
   ```

3. Configurar CORS centralizado para o ecossistema

### Prioridade Média
4. Configurar Circuit Breaker por rota com fallback
5. Adicionar Rate Limiting com Redis
6. Implementar Correlation ID via GlobalFilter
7. Configurar OpenTelemetry para tracing

### Prioridade Baixa
8. Remover `spring-boot-devtools`
9. Externalizar URL do Eureka via variável de ambiente
10. Configurar Retry para requisições GET com falha transitória

---

## Nível de Criticidade

🔴 **Alta** — É o único ponto de entrada. Falha de segurança aqui compromete todo o ecossistema. A ausência de JWT enforcement significa que toda a plataforma está aberta sem autenticação.
