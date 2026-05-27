# Débitos Técnicos — discovery-server

---

## [TD-DS-01] Dashboard sem Autenticação (Principal)

**Tipo:** Segurança  
**Prioridade:** 🟡 Médio — exposição da topologia interna  
**Esforço para remover:** 2-4 horas

**Descrição:**  
O dashboard Eureka em `http://localhost:8761` está acessível sem autenticação. Em produção exposta, qualquer pessoa pode visualizar quais serviços estão rodando, em quais IPs/portas e seu estado de saúde.

**Causa:** O discovery-server não tem `spring-boot-starter-security` no `pom.xml`.

**Impacto em produção:** Vazamento de topologia interna facilita ataques direcionados.

**Estratégia de remoção:** Ver [security-plan.md](./security-plan.md) Task 1.

---

## [TD-DS-02] SPOF — Instância Única

**Tipo:** Disponibilidade  
**Prioridade:** 🟠 Alto — risco de downtime total  
**Esforço para remover:** 4-8 horas

**Descrição:**  
Uma única instância do discovery-server. Se o processo cair, o gateway perde a capacidade de resolver nomes de serviços (`lb://auth-service`).

**Causa:** Instância única é aceitável em desenvolvimento; o SPOF torna-se problema em produção.

**Impacto:** Qualquer restart do Eureka causa janela de instabilidade em todo o ecossistema.

**Estratégia:** Configurar peer-to-peer com 2 instâncias. Ver [implementation-plan.md](./implementation-plan.md) Task 2.

**Alternativa de longo prazo:** Em Kubernetes, migrar para Service Discovery nativo — elimina o Eureka completamente.

---

## [TD-DS-03] Sem Health Checks

**Tipo:** Operacional  
**Prioridade:** 🟡 Médio  
**Esforço para remover:** 30 minutos

**Descrição:**  
Sem `spring-boot-starter-actuator`, o Docker Compose e Kubernetes não conseguem verificar se o Eureka está pronto antes de subir os outros serviços.

**Causa:** Actuator não foi adicionado ao `pom.xml`.

**Impacto:** Outros serviços tentam se registrar antes do Eureka estar completamente inicializado, causando falhas na inicialização que se autocorrigem mas geram ruído nos logs.

---

## [TD-DS-04] Sem Dockerfile

**Tipo:** Infraestrutura  
**Prioridade:** 🟠 Alto — necessário para deploy  
**Esforço para remover:** 1 hora

**Descrição:**  
Não há Dockerfile no discovery-server. Sem ele, não é possível incluir o Eureka em um docker-compose.yml ou em um deploy em Kubernetes.

**Nota:** O member-service é o único serviço com Dockerfile no ecossistema. Os demais (incluindo o discovery-server) precisam ter Dockerfiles criados.

---

## [TD-DS-05] URL do Eureka Hardcoded para localhost nos Clientes

**Tipo:** Configuração de infraestrutura  
**Prioridade:** 🟠 Alto — bloqueia deploy em Docker/K8s  
**Esforço para remover:** 30 minutos por serviço (total: 2 horas)

**Descrição:**  
Todos os clientes Eureka têm hardcoded:
```properties
eureka.client.service-url.defaultZone=http://localhost:8761/eureka
```

Em Docker, `localhost` dentro de um container é o próprio container, não o host. Isso faz os serviços não conseguirem se registrar no Eureka ao subir em Docker.

**Estratégia:** Cada serviço deve usar:
```properties
eureka.client.service-url.defaultZone=http://${EUREKA_USER}:${EUREKA_PASSWORD}@${EUREKA_HOST:localhost}:8761/eureka
```

---

## Resumo Priorizado

| ID | Débito | Prioridade | Esforço | Sprint |
|----|--------|------------|---------|--------|
| TD-DS-04 | Sem Dockerfile | 🟠 Alto | P | Sprint 1 (junto INFRA-01) |
| TD-DS-05 | Eureka localhost nos clientes | 🟠 Alto | P | Sprint 1 |
| TD-DS-01 | Dashboard sem auth | 🟡 Médio | P | Sprint 1 |
| TD-DS-03 | Sem health checks | 🟡 Médio | Mínimo | Sprint 1 |
| TD-DS-02 | SPOF instância única | 🟠 Alto | M | Sprint 3 (HA) |

> Note: TD-DS-05 (Eureka localhost) também é um débito técnico de TODOS os clientes Eureka (auth-service, member-service, financial-service, gateway-application). Cada serviço deve corrigir sua própria configuração como parte do [SEC-01] de cada um.
