# Plano de Segurança — discovery-server

---

## Estado Atual

O discovery-server não tem nenhuma autenticação. O dashboard em `http://localhost:8761` está acessível publicamente.

---

## [SEC-DS-01] Autenticar o Dashboard Eureka

### Por que é crítico
O dashboard do Eureka mostra:
- Quais serviços estão rodando
- Em quais IPs e portas
- Quantas instâncias de cada serviço
- Status de saúde de cada instância

Um atacante com acesso a essas informações consegue mapear toda a topologia interna do sistema antes de atacar.

### Implementação

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Clientes Eureka usam POST para se registrar — CSRF interfere
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
            @Value("${eureka.auth.username:eureka}") String username,
            @Value("${eureka.auth.password}") String encodedPassword) {
        UserDetails user = User.withUsername(username)
                .password(encodedPassword)
                .roles("SYSTEM")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

```properties
# application.properties
eureka.auth.username=${EUREKA_USER:eureka}
# Senha já em BCrypt — gerar com: spring.security.user.password.encode("senha")
eureka.auth.password=${EUREKA_PASSWORD_BCRYPT}
```

> Alternativa mais simples: usar `spring.security.user.name` e `spring.security.user.password` com senha em plain text (aceitável para desenvolvimento, mas use BCrypt em produção).

---

## [SEC-DS-02] Isolar o Eureka na Rede Docker

Em Docker Compose, o Eureka não precisa estar exposto na porta pública. Apenas o gateway precisa acessá-lo de fora da rede Docker.

```yaml
# docker-compose.yml
services:
  discovery-server:
    # Não expor porta 8761 para o host em produção
    # ports:
    #   - "8761:8761"  ← comentar em produção
    networks:
      - internal

  gateway-application:
    ports:
      - "8080:8080"  # único ponto de entrada público
    networks:
      - internal

networks:
  internal:
    driver: bridge
```

Em produção, o dashboard do Eureka só seria acessível via VPN ou bastion host.

---

## [SEC-DS-03] HTTPS para o Eureka

Em produção, todo tráfego para o Eureka deve ser HTTPS, pois as credenciais de autenticação básica são enviadas em cada registro de serviço.

**Opção 1: Terminar TLS no load balancer/proxy** (mais simples)
- Configurar Nginx/Traefik na frente do Eureka
- O Eureka recebe HTTP internamente (rede privada)
- O proxy termina TLS e repassa para o Eureka

**Opção 2: TLS direto no Eureka**
```properties
server.ssl.key-store=${SSL_KEYSTORE_PATH}
server.ssl.key-store-password=${SSL_KEYSTORE_PASSWORD}
server.ssl.key-store-type=PKCS12
```

Todos os clientes Eureka precisam confiar no certificado:
```properties
eureka.client.tls.enabled=true
eureka.client.tls.trust-store=${SSL_TRUSTSTORE_PATH}
```

---

## [SEC-DS-04] Proteger contra Registro de Serviços Não Autorizados

Em uma rede não confiável, qualquer serviço poderia registrar-se no Eureka e aparecer como um serviço legítimo (ex.: um serviço falso chamado `auth-service`).

**Mitigação de curto prazo:** Autenticação básica (Task 1) — apenas serviços com as credenciais corretas conseguem se registrar.

**Mitigação de longo prazo:** mTLS — cada serviço tem um certificado, e o Eureka verifica o certificado antes de aceitar o registro.

---

## Configuração de Produção Resumida

```properties
# discovery-server application.properties (produção)
spring.security.user.name=${EUREKA_USER}
spring.security.user.password=${EUREKA_PASSWORD}

eureka.server.enable-self-preservation=true
eureka.server.expected-client-renewal-interval-seconds=30
eureka.server.eviction-interval-timer-in-ms=60000

management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=when-authorized
```
