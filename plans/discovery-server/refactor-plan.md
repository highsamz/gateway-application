# Plano de Refatoração — discovery-server

---

## Estado Atual

O discovery-server é extremamente simples: uma classe main com `@EnableEurekaServer` e um `application.properties`. Não há código para refatorar no sentido tradicional.

As "refatorações" aqui são melhorias de configuração.

---

## [REFACTOR-DS-01] Externalizar Todas as Configurações via Env Vars

### Estado atual
```properties
# application.properties
spring.application.name=discovery-server
eureka.client.register-with-eureka=false
eureka.client.fetch-registry=false
server.port=8761
```

### Estado alvo
```properties
spring.application.name=discovery-server
server.port=${SERVER_PORT:8761}

eureka.client.register-with-eureka=${EUREKA_REGISTER_WITH_EUREKA:false}
eureka.client.fetch-registry=${EUREKA_FETCH_REGISTRY:false}

# Para modo peer-to-peer (HA):
eureka.instance.hostname=${EUREKA_HOSTNAME:localhost}
eureka.client.service-url.defaultZone=${EUREKA_PEER_URL:http://localhost:8761/eureka}

# Server configuration
eureka.server.enable-self-preservation=${EUREKA_SELF_PRESERVATION:true}
eureka.server.eviction-interval-timer-in-ms=${EUREKA_EVICTION_INTERVAL:60000}

management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always
```

---

## [REFACTOR-DS-02] Múltiplos Profiles para Desenvolvimento e Produção

Criar profiles Spring para separar comportamento em ambientes diferentes:

**`application-dev.properties`** (desenvolvimento local):
```properties
eureka.server.enable-self-preservation=false
eureka.server.eviction-interval-timer-in-ms=5000
```

**`application-prod.properties`** (produção):
```properties
eureka.server.enable-self-preservation=true
eureka.server.eviction-interval-timer-in-ms=60000
```

**`application-ha.properties`** (alta disponibilidade com peer-to-peer):
```properties
eureka.client.register-with-eureka=true
eureka.client.fetch-registry=true
eureka.instance.hostname=${EUREKA_HOSTNAME}
eureka.client.service-url.defaultZone=${EUREKA_PEER_URL}
```

---

## Não Refatorar

As seguintes coisas NÃO devem ser refatoradas no discovery-server:
- A classe main com `@EnableEurekaServer` — é o padrão correto e mínimo necessário
- A ausência de lógica de negócio — é um serviço de infraestrutura, deve ser simples

O objetivo do discovery-server é ser simples, confiável e estável. Complexidade desnecessária aqui é um anti-padrão.
