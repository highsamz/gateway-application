# Débitos Técnicos — gateway-application

---

## [TD-GW-01] SecurityConfig com anyExchange().permitAll()

**Tipo:** Segurança crítica  
**Prioridade:** 🔴 Crítico — bloqueia produção  
**Esforço para remover:** 4-8 horas

**Descrição:**  
A `SecurityConfig` atual libera todos os endpoints sem autenticação. É o maior débito técnico do ecossistema inteiro.

**Causa:** A dependência `oauth2-resource-server` foi adicionada mas a configuração nunca foi completada.

**Impacto futuro se não corrigido:**  
Dados de membros (LGPD) e transações financeiras expostos publicamente. Em produção, é violação imediata de compliance.

**Estratégia de remoção:** Ver [security-plan.md](./security-plan.md) — Task GW-01 e GW-02.

---

## [TD-GW-02] spring-boot-devtools sem scope em produção

**Tipo:** Configuração incorreta  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 5 minutos

**Descrição:**  
A dependência `spring-boot-devtools` está no `pom.xml` sem scope `runtime` e sem a flag `<optional>true</optional>`. Em produção, o devtools recarrega o classloader a cada mudança de arquivo, consome CPU adicional e pode causar comportamento imprevisível.

**Correção:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
    <scope>runtime</scope>
    <optional>true</optional>
</dependency>
```

---

## [TD-GW-03] Eureka URL hardcoded para localhost

**Tipo:** Configuração de infraestrutura  
**Prioridade:** 🟠 Alto — bloqueia deploy em Docker/K8s  
**Esforço para remover:** 30 minutos

**Descrição:**
```properties
# application.properties atual — problemático
eureka.client.service-url.defaultZone=http://localhost:8761/eureka
```

Em Docker Compose, o `localhost` dentro do container do gateway não é o host da máquina — é o próprio container. Isso faz o gateway não conseguir registrar-se no Eureka ao subir em Docker.

**Correção:**
```properties
eureka.client.service-url.defaultZone=http://${EUREKA_USER:eureka}:${EUREKA_PASSWORD:changeme}@${EUREKA_HOST:localhost}:8761/eureka
```

---

## [TD-GW-04] Ausência de Circuit Breaker nas rotas

**Tipo:** Resiliência  
**Prioridade:** 🟠 Alto  
**Esforço para remover:** 4-8 horas

**Descrição:**  
A dependência `resilience4j-spring-boot3` está presente mas nenhum `CircuitBreakerFilter` está configurado nas rotas. Falha em qualquer microsserviço causa efeito cascata — threads bloqueadas se acumulam no gateway até causar OOM ou timeout generalizado.

**Estratégia de remoção:** Ver [implementation-plan.md](./implementation-plan.md) — Task 4.

---

## [TD-GW-05] Typo histórico no nome do pacote

**Tipo:** Cosmético / código legado  
**Prioridade:** 🟢 Baixo — não afeta funcionamento  
**Esforço para remover:** 2-4 horas (renomear pacote + todos os imports)

**Descrição:**  
O pacote raiz é `br.com.gateway_apllication` (com dois `l`). É um typo histórico que foi propagado para todos os arquivos.

**Risco de correção:**  
Renomear pacote é uma refatoração estrutural. Se feita incorretamente pode quebrar o build. Recomendado adiar até existirem testes automatizados.

**Estratégia de remoção:** Com cobertura de testes > 70%, usar IntelliJ IDEA `Refactor → Rename` no pacote — propaga automaticamente para todos os imports e arquivos.

---

## [TD-GW-06] application.properties vs application.yml

**Tipo:** Manutenibilidade  
**Prioridade:** 🟢 Baixo  
**Esforço para remover:** 1-2 horas

**Descrição:**  
O `application.properties` usa índices numéricos para configurar as rotas do gateway (`routes[0]`, `routes[1]`, `routes[2]`). À medida que mais rotas e filtros são adicionados, o formato `.properties` se torna ilegível.

**Estratégia de remoção:** Migrar para `application.yml` ao implementar o Circuit Breaker (GW-04), que requer configuração hierárquica mais complexa.

---

## [TD-GW-07] Zero testes automatizados

**Tipo:** Qualidade  
**Prioridade:** 🟡 Médio (impacta manutenção futura)  
**Esforço para remover:** 2-3 dias (ongoing)

**Descrição:**  
O arquivo `GatewayApllicationApplicationTests.java` existe mas está vazio (`@SpringBootTest` sem nenhum teste). Sem testes, qualquer mudança na `SecurityConfig` ou nos filtros pode introduzir regressões silenciosas.

**Testes mínimos necessários:**
1. `SecurityConfigTest`: confirmar que `/auth/login` é público, `/members` requer autenticação
2. `CorrelationIdFilterTest`: confirmar que header `X-Correlation-Id` é injetado
3. `JwtPropagationFilterTest`: confirmar que `X-User-Id` chega corretamente do JWT

**Estratégia:** Usar `WebTestClient` (já disponível com WebFlux) + `@SpringBootTest(webEnvironment = RANDOM_PORT)`.

---

## Resumo Priorizado

| ID | Débito | Prioridade | Esforço | Quando Remover |
|----|--------|------------|---------|---------------|
| TD-GW-01 | permitAll() em produção | 🔴 Crítico | M | Sprint 1 |
| TD-GW-03 | Eureka localhost hardcoded | 🟠 Alto | P | Sprint 1 |
| TD-GW-04 | Sem Circuit Breaker | 🟠 Alto | M | Sprint 1 |
| TD-GW-02 | devtools sem scope | 🟡 Médio | Mínimo | Imediato |
| TD-GW-07 | Zero testes | 🟡 Médio | G | Sprint 4+ |
| TD-GW-06 | properties vs yaml | 🟢 Baixo | P | Sprint 1 (junto com GW-04) |
| TD-GW-05 | Typo no pacote | 🟢 Baixo | M | Após testes |
