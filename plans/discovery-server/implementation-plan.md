# Plano de Implementação — discovery-server

---

## Estado Atual

O discovery-server é extremamente simples e funciona corretamente para o ambiente de desenvolvimento. As implementações aqui são melhorias de segurança e disponibilidade para produção.

---

## Task 1 — Proteger Dashboard com Autenticação Básica

**Esforço:** 2-4 horas  
**Pré-requisito:** Nenhum

**Problema:** O dashboard Eureka em `http://localhost:8761` está acessível publicamente, expondo a topologia interna do ecossistema (quais serviços estão rodando, em quais IPs/portas).

**Mudança no `pom.xml`:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

**Nova classe `SecurityConfig.java`:**
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/eureka/**"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(
            @Value("${eureka.dashboard.username:eureka}") String username,
            @Value("${eureka.dashboard.password}") String password) {
        UserDetails user = User.builder()
                .username(username)
                .password(new BCryptPasswordEncoder().encode(password))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

**Mudanças no `application.properties`:**
```properties
# Remover as propriedades de usuário Spring Security padrão e usar as customizadas
eureka.dashboard.username=${EUREKA_USER:eureka}
eureka.dashboard.password=${EUREKA_PASSWORD}
```

**Atualizar todos os clientes Eureka** (auth-service, member-service, financial-service, gateway):
```properties
# Antes:
eureka.client.service-url.defaultZone=http://localhost:8761/eureka

# Depois:
eureka.client.service-url.defaultZone=http://${EUREKA_USER:eureka}:${EUREKA_PASSWORD:changeme}@${EUREKA_HOST:localhost}:8761/eureka
```

**Variáveis de ambiente necessárias:**
```env
EUREKA_USER=eureka
EUREKA_PASSWORD=<senha forte>
```

---

## Task 2 — Alta Disponibilidade via Peer-to-Peer (Produção)

**Esforço:** 4-8 horas  
**Pré-requisito:** Docker Compose configurado

**Problema:** SPOF — única instância do Eureka. Se cair, o ecossistema perde Service Discovery.

**Configuração peer-to-peer com 2 instâncias:**

```yaml
# docker-compose.yml

eureka-primary:
  build: ./discovery-server
  hostname: eureka-primary
  ports:
    - "8761:8761"
  environment:
    SPRING_PROFILES_ACTIVE: peer1
    EUREKA_USER: ${EUREKA_USER}
    EUREKA_PASSWORD: ${EUREKA_PASSWORD}

eureka-secondary:
  build: ./discovery-server
  hostname: eureka-secondary
  ports:
    - "8762:8761"
  environment:
    SPRING_PROFILES_ACTIVE: peer2
    EUREKA_USER: ${EUREKA_USER}
    EUREKA_PASSWORD: ${EUREKA_PASSWORD}
```

**application-peer1.properties:**
```properties
eureka.instance.hostname=eureka-primary
eureka.client.register-with-eureka=true
eureka.client.fetch-registry=true
eureka.client.service-url.defaultZone=http://${EUREKA_USER}:${EUREKA_PASSWORD}@eureka-secondary:8761/eureka
```

**application-peer2.properties:**
```properties
eureka.instance.hostname=eureka-secondary
eureka.client.register-with-eureka=true
eureka.client.fetch-registry=true
eureka.client.service-url.defaultZone=http://${EUREKA_USER}:${EUREKA_PASSWORD}@eureka-primary:8761/eureka
```

**Clientes Eureka apontam para ambas as instâncias:**
```properties
eureka.client.service-url.defaultZone=\
  http://${EUREKA_USER}:${EUREKA_PASSWORD}@eureka-primary:8761/eureka,\
  http://${EUREKA_USER}:${EUREKA_PASSWORD}@eureka-secondary:8761/eureka
```

---

## Task 3 — Adicionar Actuator (Health Checks)

**Esforço:** 30 minutos

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```properties
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always
```

Necessário para que o Docker Compose possa verificar `service_healthy` antes de subir os demais serviços.

---

## Task 4 — Configurar Eureka Self-Preservation

Em desenvolvimento, o modo de self-preservation pode causar confusão (serviços aparecem como UP mesmo após serem encerrados). Para desenvolvimento, desabilitar:

```properties
# application.properties — apenas para desenvolvimento
eureka.server.enable-self-preservation=${EUREKA_SELF_PRESERVATION:false}
eureka.server.eviction-interval-timer-in-ms=${EUREKA_EVICTION_INTERVAL:5000}
```

Para produção, **manter self-preservation habilitado** (padrão `true`).
