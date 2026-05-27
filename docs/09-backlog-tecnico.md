# 09 — Backlog Técnico Priorizado

> Itens ordenados por impacto em segurança, estabilidade e qualidade.  
> Cada item inclui: problema, impacto, risco futuro e benefício da correção.

---

## 🔴 Alta Prioridade (Bloqueadores para Produção)

---

### [AUTH-01] Implementar geração de JWT no auth-service

**Serviço:** auth-service  
**Esforço estimado:** Médio (1–2 dias)

**Problema:**  
O endpoint `POST /auth/login` verifica credenciais mas retorna apenas dados do usuário — nunca gera um token de acesso assinado.

**Impacto técnico:**  
Toda a cadeia de autenticação está quebrada. Não há como identificar o usuário em requisições subsequentes.

**Risco futuro:**  
Em produção, qualquer pessoa pode chamar qualquer endpoint sem credenciais. Violação imediata de LGPD e segurança.

**Benefício:**  
Com JWT emitido, o gateway pode validar tokens offline (sem chamada ao auth-service a cada request), o que garante escalabilidade e segurança.

**Referências:** [06-melhorias-recomendadas.md §1.1](./06-melhorias-recomendadas.md)

---

### [GW-01] Habilitar validação de JWT no gateway

**Serviço:** gateway-application  
**Esforço estimado:** Pequeno (4–8 horas)

**Problema:**  
`SecurityConfig` do gateway faz `anyExchange().permitAll()`. A dependência `oauth2-resource-server` existe mas não está ativa.

**Impacto técnico:**  
Todos os endpoints dos microsserviços estão acessíveis sem autenticação.

**Risco futuro:**  
Dados pessoais de membros (LGPD) e dados financeiros expostos publicamente.

**Benefício:**  
Ativa o perímetro de segurança do ecossistema com uma mudança mínima de configuração.

---

### [GW-02] Configurar CORS no gateway

**Serviço:** gateway-application  
**Esforço estimado:** Pequeno (2–4 horas)

**Problema:**  
CORS não está configurado. Qualquer frontend de domínio diferente receberá erro de CORS.

**Impacto técnico:**  
Frontend web fica inutilizável em produção ou staging com domínios separados.

**Risco futuro:**  
Bloqueio completo do frontend ao tentar acessar a API.

**Benefício:**  
Com CORS configurado corretamente no gateway, todos os microsserviços ficam cobertos automaticamente.

---

### [SEC-01] Remover credenciais hardcoded

**Serviços:** auth-service, member-service, financial-service  
**Esforço estimado:** Pequeno (2–4 horas)

**Problema:**  
Strings `postgres/postgres` e URLs de banco estão literalmente no código-fonte em `application.properties`.

**Impacto técnico:**  
Qualquer pessoa com acesso ao repositório tem acesso ao banco de dados.

**Risco futuro:**  
Em repositório público ou acesso indevido: comprometimento total dos dados.

**Benefício:**  
Variáveis de ambiente e secrets management eliminam esse vetor de ataque.

---

### [FIN-01] Corrigir `createdBy` no financial-service

**Serviço:** financial-service  
**Esforço estimado:** Pequeno (4 horas, dependente de GW-01 e AUTH-01)

**Problema:**  
`TransactionMapper` usa `UUID.randomUUID()` em vez do ID do usuário autenticado.

**Impacto técnico:**  
100% das transações financeiras são irrastreáveis. Auditoria financeira impossível.

**Risco futuro:**  
Fraudes e erros não podem ser atribuídos a nenhum responsável.

**Benefício:**  
Com X-User-Id propagado pelo gateway, auditoria completa de cada lançamento.

---

### [GW-03] Propagar identidade do usuário (X-User-Id, X-User-Roles) para downstream

**Serviço:** gateway-application  
**Esforço estimado:** Pequeno (4–8 horas)

**Problema:**  
Gateway não extrai claims do JWT e não injeta headers de identidade nos serviços downstream.

**Impacto técnico:**  
Microsserviços não sabem quem fez cada operação. Autorização por papel é impossível.

**Benefício:**  
Microsserviços recebem identidade confiável sem precisar validar JWT individualmente.

---

### [AUTH-02] Implementar Refresh Token

**Serviço:** auth-service  
**Esforço estimado:** Médio (1 dia)

**Problema:**  
Sem refresh token, o usuário precisa fazer login novamente cada vez que o JWT expira.

**Impacto técnico:**  
UX ruim. JWT de longa duração é necessário para compensar, o que aumenta risco de token roubado.

**Benefício:**  
Tokens de curta duração (15-60 min) com refresh token de longa duração é o padrão seguro.

---

### [GW-04] Configurar Circuit Breaker no gateway

**Serviço:** gateway-application  
**Esforço estimado:** Pequeno (4–8 horas)

**Problema:**  
Resilience4j está como dependência mas sem nenhuma configuração de circuit breaker nas rotas.

**Impacto técnico:**  
Falha em um microsserviço pode travar o gateway por acúmulo de threads bloqueadas (efeito cascata).

**Risco futuro:**  
Uma falha localizada derruba todo o ecossistema.

**Benefício:**  
Circuit breaker abre após N falhas, retorna fallback imediato e permite recuperação gradual.

---

## 🟠 Média Prioridade (Importantes, não bloqueiam o MVP)

---

### [QUAL-01] Implementar GlobalExceptionHandler em auth-service e financial-service

**Esforço estimado:** Pequeno (4 horas cada)

**Problema:**  
Apenas member-service tem `@RestControllerAdvice`. Os outros dois expõem stacktraces.

**Impacto técnico:**  
Respostas de erro inconsistentes. Informações internas vazam para o cliente.

**Benefício:**  
Respostas padronizadas `ApiErrorResponse { status, message, timestamp }` em todos os serviços.

---

### [QUAL-02] Adicionar @Valid e Jakarta Validation nos DTOs

**Serviços:** todos  
**Esforço estimado:** Médio (1 dia)

**Problema:**  
DTOs aceitam qualquer dado sem validação. Emails inválidos, CPFs malformados, amounts negativos são persistidos.

**Benefício:**  
Rejeição imediata de dados inválidos com HTTP 400 e mensagem clara antes de chegar ao banco.

---

### [OBS-01] Implementar Correlation ID

**Serviço:** gateway-application  
**Esforço estimado:** Pequeno (4 horas)

**Problema:**  
Sem correlation ID, impossível rastrear uma requisição através dos logs de múltiplos serviços.

**Benefício:**  
Cada requisição recebe um UUID único propagado em todos os logs, permitindo rastreio completo.

---

### [OBS-02] Configurar OpenTelemetry / Zipkin

**Serviços:** todos  
**Esforço estimado:** Médio (1–2 dias)

**Problema:**  
Dependências de tracing estão no gateway mas não configuradas. Nenhum serviço emite traces.

**Benefício:**  
Visibilidade completa de latência e falhas em cada hop do ecossistema.

---

### [OBS-03] Logs estruturados em JSON

**Serviços:** todos  
**Esforço estimado:** Pequeno (4 horas)

**Problema:**  
Logs em texto livre são difíceis de indexar e pesquisar.

**Benefício:**  
Logs JSON indexáveis em ELK Stack, Loki ou CloudWatch. Alertas automatizados por padrão.

---

### [INFRA-01] Dockerizar todos os serviços + Docker Compose unificado

**Esforço estimado:** Médio (1 dia)

**Problema:**  
Apenas member-service tem Dockerfile. Não é possível subir o ecossistema completo com um único comando.

**Benefício:**  
`docker-compose up` sobe todos os serviços, bancos, Eureka, MinIO e Zipkin de uma vez.

---

### [QUAL-03] Unificar versões Spring Boot/Cloud via parent POM

**Esforço estimado:** Pequeno (4 horas)

**Problema:**  
Versões divergentes entre serviços (3.3.2 até 3.5.9) causam comportamentos diferentes.

**Benefício:**  
Consistência de comportamento. Uma única atualização de versão cobre todos os serviços.

---

### [MBR-01] Adicionar constraint de unicidade para CPF e email em member-service

**Serviço:** member-service  
**Esforço estimado:** Pequeno (2 horas)

**Problema:**  
Dois membros com mesmo CPF podem ser cadastrados. Dados duplicados comprometem integridade.

**Benefício:**  
Integridade referencial garantida em nível de banco de dados.

---

### [FIN-02] Implementar endpoints de upload/download de anexos

**Serviço:** financial-service  
**Esforço estimado:** Alto (2–3 dias)

**Problema:**  
Schema de `attachments` existe no banco, mas sem endpoint exposto e MinIO não configurado.

**Benefício:**  
Comprovantes de transações ficam acessíveis, suportando auditoria financeira real.

---

### [GW-05] Configurar Rate Limiting

**Serviço:** gateway-application  
**Esforço estimado:** Médio (1 dia — requer Redis)

**Problema:**  
Sem limite de requisições, qualquer script pode sobrecarregar os serviços.

**Benefício:**  
Proteção contra DDoS e abuso de API.

---

## 🟡 Baixa Prioridade (Melhorias de qualidade e futuro)

---

### [INFRA-02] Configurar CI/CD Pipeline

**Esforço estimado:** Alto (2–3 dias)

**Problema:**  
Deploys manuais são propensos a erro e sem rastreabilidade.

**Benefício:**  
Automação de build, testes, análise de cobertura e deploy em staging a cada push.

---

### [QUAL-04] Implementar testes unitários e de integração

**Serviços:** todos  
**Esforço estimado:** Alto (semanas — ongoing)

**Problema:**  
Arquivos de teste existem mas estão vazios. Sem testes, regressões não são detectadas.

**Benefício:**  
Segurança para refatorar e evoluir sem quebrar funcionalidades existentes.

---

### [QUAL-05] Implementar interfaces nos Services

**Serviços:** todos  
**Esforço estimado:** Pequeno (4–8 horas)

**Problema:**  
Services concretos sem interface dificultam testes com mock e violam DIP do SOLID.

**Benefício:**  
Testabilidade e substituibilidade de implementações.

---

### [API-01] Adicionar versionamento de API (/v1/...)

**Esforço estimado:** Pequeno (4 horas)

**Problema:**  
Sem versão na URL, qualquer breaking change quebra todos os clientes.

**Benefício:**  
`/v1/members` e `/v2/members` podem coexistir durante migrações.

---

### [INFRA-03] Configurar Config Server centralizado

**Esforço estimado:** Alto (2 dias)

**Problema:**  
Configurações distribuídas em múltiplos `application.properties` são difíceis de gerenciar.

**Benefício:**  
Configurações centralizadas em Git. Mudança de configuração sem rebuild de imagem.

---

### [OBS-04] Adicionar Prometheus + Grafana para métricas

**Esforço estimado:** Médio (1–2 dias)

**Problema:**  
Sem métricas, impossível detectar degradação de performance antes de virar incidente.

**Benefício:**  
Dashboards de latência, throughput e error rate. Alertas automáticos.

---

### [AUTH-03] Adicionar auditoria de logins

**Serviço:** auth-service  
**Esforço estimado:** Pequeno (4 horas)

**Benefício:**  
Registro de todas as tentativas de login (sucesso/falha), IP, timestamp. Base para segurança e compliance.

---

### [FIN-03] Implementar relatórios financeiros

**Serviço:** financial-service  
**Esforço estimado:** Médio (1–2 dias)

**Benefício:**  
Extrato mensal, resumo por categoria, exportação CSV para prestação de contas da congregação.

---

## Resumo do Backlog

| ID | Serviço | Título | Prioridade | Esforço |
|----|---------|--------|------------|---------|
| AUTH-01 | auth-service | Gerar JWT no login | 🔴 Alta | Médio |
| GW-01 | gateway | Validar JWT | 🔴 Alta | Pequeno |
| GW-02 | gateway | Configurar CORS | 🔴 Alta | Pequeno |
| SEC-01 | todos | Remover credenciais hardcoded | 🔴 Alta | Pequeno |
| FIN-01 | financial | Corrigir createdBy | 🔴 Alta | Pequeno |
| GW-03 | gateway | Propagar X-User-Id | 🔴 Alta | Pequeno |
| AUTH-02 | auth-service | Refresh Token | 🔴 Alta | Médio |
| GW-04 | gateway | Circuit Breaker | 🔴 Alta | Pequeno |
| QUAL-01 | auth, financial | GlobalExceptionHandler | 🟠 Média | Pequeno |
| QUAL-02 | todos | @Valid nos DTOs | 🟠 Média | Médio |
| OBS-01 | gateway | Correlation ID | 🟠 Média | Pequeno |
| OBS-02 | todos | OpenTelemetry/Zipkin | 🟠 Média | Médio |
| OBS-03 | todos | Logs JSON | 🟠 Média | Pequeno |
| INFRA-01 | todos | Docker Compose unificado | 🟠 Média | Médio |
| QUAL-03 | todos | Parent POM unificado | 🟠 Média | Pequeno |
| MBR-01 | member | Unicidade CPF/email | 🟠 Média | Pequeno |
| FIN-02 | financial | Endpoints de anexos | 🟠 Média | Alto |
| GW-05 | gateway | Rate Limiting | 🟠 Média | Médio |
| INFRA-02 | todos | CI/CD Pipeline | 🟡 Baixa | Alto |
| QUAL-04 | todos | Testes automatizados | 🟡 Baixa | Alto |
| QUAL-05 | todos | Interfaces nos Services | 🟡 Baixa | Pequeno |
| API-01 | todos | Versionamento /v1/ | 🟡 Baixa | Pequeno |
| INFRA-03 | infra | Config Server | 🟡 Baixa | Alto |
| OBS-04 | todos | Prometheus + Grafana | 🟡 Baixa | Médio |
| AUTH-03 | auth-service | Auditoria de logins | 🟡 Baixa | Pequeno |
| FIN-03 | financial | Relatórios financeiros | 🟡 Baixa | Médio |
