# Serviço: discovery-server

**Criticidade:** 🔴 Alta (infraestrutura base de todo o ecossistema)

---

## Objetivo

Prover o registro centralizado de serviços (Service Registry) para o ecossistema de microsserviços, permitindo que o gateway e os demais serviços se descubram por nome lógico em vez de endereço IP/porta fixos.

---

## Responsabilidades

- Manter o catálogo de serviços registrados e suas instâncias
- Responder a consultas de descoberta (ex: "qual o endereço de `member-service`?")
- Prover o dashboard web de monitoramento do Eureka (porta 8761)
- Detectar e remover instâncias inativas via heartbeat

---

## Stack

| Item | Versão |
|------|--------|
| Spring Boot | 3.5.8 |
| Spring Cloud | 2025.0.0 |
| Netflix Eureka Server | Gerenciada pelo Spring Cloud |
| Java | 21 |

---

## Principais Endpoints

| Endpoint | Finalidade |
|----------|------------|
| `GET http://localhost:8761` | Dashboard web do Eureka |
| `GET http://localhost:8761/eureka/apps` | Lista de todos os serviços registrados (XML/JSON) |
| `GET http://localhost:8761/eureka/apps/{appName}` | Detalhes de um serviço específico |

---

## Configuração

```properties
server.port=8761
spring.application.name=discovery-server
eureka.client.register-with-eureka=false   # não se registra em si mesmo
eureka.client.fetch-registry=false          # não busca registry de outro Eureka
```

---

## Fluxo Interno

```
1. Serviço inicia → POST /eureka/apps/{nome}
2. Eureka armazena instância (IP, porta, health URL)
3. Serviço envia heartbeat a cada 30s → PUT /eureka/apps/{nome}/{instanceId}
4. Gateway consulta registry → GET /eureka/apps
5. Spring Cloud Load Balancer resolve lb://nome para IP:porta real
6. Se serviço parar de enviar heartbeat por 90s → Eureka remove instância
```

---

## Dependências

- Nenhuma dependência de outros serviços do ecossistema
- Deve ser o **primeiro serviço a iniciar**

---

## Problemas Encontrados

| Problema | Severidade | Descrição |
|----------|------------|-----------|
| SPOF | 🟠 Alto | Instância única. Se cair, todo o ecossistema perde descoberta dinâmica |
| Sem autenticação | 🟡 Médio | Dashboard e API do Eureka estão acessíveis publicamente |
| URL hardcoded nos clientes | 🟡 Médio | Todos os clientes apontam para `localhost:8761`, quebra em Docker/K8s |

---

## Melhorias Sugeridas

1. **Peer-to-peer Eureka:** Configurar 2 instâncias com replicação mútua para eliminar SPOF
2. **Basic Auth no Eureka:** Adicionar `spring.security.user` para proteger o dashboard
3. **Variável de ambiente para URL:** Clientes devem usar `${EUREKA_URL:http://localhost:8761/eureka}`
4. **Health check endpoint:** Adicionar Actuator para monitoramento externo

---

## Nível de Criticidade

🔴 **Alta** — É o primeiro elo da cadeia. Sua indisponibilidade afeta a capacidade do gateway de rotear qualquer requisição.
