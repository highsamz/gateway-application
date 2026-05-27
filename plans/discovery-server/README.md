# discovery-server — Plano de Evolução

## Objetivo do Serviço

Registro e descoberta de serviços (Service Registry) via Netflix Eureka. Ponto de referência para todos os outros serviços do ecossistema encontrarem uns aos outros.

**Porta:** 8761 | **Stack:** Spring Boot 3.5.8 + Spring Cloud 2025.0.0 | **Banco:** Nenhum

---

## Estado Atual (confirmado pela leitura do código)

**Extremamente simples — como deveria ser:**
- Classe main com `@EnableEurekaServer`
- `application.properties` com:
  - `eureka.client.register-with-eureka=false`
  - `eureka.client.fetch-registry=false`
  - Porta 8761
- Sem autenticação
- Sem clustering (instância única)

**O serviço funciona corretamente para o ambiente de desenvolvimento.**

---

## Principais Riscos

| Risco | Severidade | Bloqueador para Produção? |
|-------|------------|--------------------------|
| SPOF — instância única | 🟠 Alto | Recomendado resolver |
| Dashboard sem autenticação | 🟡 Médio | Sim (expõe topologia interna) |
| URL localhost hardcoded nos clientes | 🟡 Médio | Sim para Docker/K8s |
| Sem HTTPS | 🟡 Médio | Sim em produção |

---

## Prioridades do Serviço

### Prioridade 1 — Bloqueadores para Produção

1. Proteger o dashboard com autenticação básica
2. Configurar URL via env var em todos os clientes (já documentado em cada serviço como SEC-01 adjacente)

### Prioridade 2 — Alta Disponibilidade

3. Eureka em modo peer-to-peer (2 instâncias) — ou migrar para K8s Service Discovery

### Prioridade 3 — Longo Prazo

4. HTTPS no dashboard
5. Migração para Kubernetes Service Discovery (elimina o Eureka completamente)

---

## Roadmap Resumido

```
Sprint 1: Autenticação básica no dashboard
Sprint 2: HA com peer-to-peer (2 instâncias)
Longo prazo: Avaliação de migração para K8s
```

---

## Posição no Ecossistema

O discovery-server é o **primeiro serviço a inicializar** e o **último a ser desligado**. Sem ele:
- O gateway não consegue resolver `lb://member-service`, `lb://auth-service`, `lb://financial-service`
- Novos pods de qualquer serviço não conseguem se registrar

Em produção, sua disponibilidade é mais crítica que a de qualquer microsserviço de negócio.
