# 04 — Responsabilidades por Serviço

---

## 1. discovery-server

### Domínio Principal
Registro e descoberta de microsserviços no ecossistema.

### Responsabilidades
- Manter o registry de todos os serviços ativos
- Fornecer resolução de endereço por nome lógico (`auth-service`, `member-service`, etc.)
- Prover interface administrativa do Eureka (dashboard web)

### Dependências Externas
- Nenhuma. É o ponto mais fundamental da infraestrutura.

### Avaliação de Responsabilidade
✅ Faz exatamente o que deve fazer. Simples e correto.

### Riscos
- **SPOF (Single Point of Failure):** Há apenas uma instância. Se o Eureka cair, o gateway perde a capacidade de descobrir serviços.
- **Sem autenticação:** O dashboard do Eureka está acessível publicamente.

---

## 2. gateway-application

### Domínio Principal
Ponto de entrada único do ecossistema. Responsável por roteamento, segurança e políticas transversais.

### Responsabilidades Corretas
- Roteamento de requisições para o serviço correto
- Balanceamento de carga entre instâncias
- Integração com Eureka para descoberta dinâmica
- Ponto central para aplicar políticas de segurança

### Responsabilidades Ausentes (deveria ter)
- Validação de JWT em cada requisição
- Propagação de identidade (headers X-User-Id, X-User-Roles) para downstream
- Rate limiting global
- CORS
- Circuit breaker para downstream services
- Correlation ID para rastreabilidade

### Dependências Externas
- Eureka Server (para descoberta)
- auth-service (para validação de JWT — quando implementado)

### Avaliação de Responsabilidade
⚠️ O gateway existe, mas não cumpre seu papel mais importante: **proteger os microsserviços**.  
Atualmente é apenas um proxy reverso com balanceamento.

---

## 3. auth-service

### Domínio Principal
Identidade e acesso: gestão de usuários do sistema e verificação de credenciais.

### Responsabilidades Corretas
- Criar usuários com email/senha e papel (role)
- Verificar credenciais no login
- Aplicar hash de senha com BCrypt
- Soft delete de usuários
- Gerenciar papéis (PASTOR, SECRETARIO, TESOUREIRO)

### Responsabilidades Incorretas / Ausentes

| Problema | Descrição |
|----------|-----------|
| ❌ Não gera JWT | O login retorna `LoginResponseDTO` com dados do usuário, mas **sem token JWT**. A autenticação não é completa. |
| ❌ Sem Refresh Token | Não há mecanismo de renovação de sessão. |
| ❌ Sem validação de formato de email | Email é aceito sem validação `@Valid` ou `@Email`. |
| ❌ Sem lockout de conta | Tentativas repetidas de login não bloqueiam a conta. |
| ❌ Sem log de auditoria | Não há registro de tentativas de login, falhas ou acessos. |

### Papéis de Usuário (Enum RoleName)
```
PASTOR      — Administrador da congregação
SECRETARIO  — Gestão de membros
TESOUREIRO  — Acesso às finanças
```

### Dependências Externas
- PostgreSQL (`authdb` na porta 5433)
- Eureka Server (registro)

### Avaliação de Responsabilidade
⚠️ O domínio está correto, mas a função central — **geração de token de autenticação** — está ausente. Isso é bloqueador para produção.

---

## 4. member-service

### Domínio Principal
Gestão dos membros da congregação (cadastro, consulta, atualização, inativação).

### Responsabilidades Corretas
- CRUD completo de membros
- Soft delete (status ATIVO/INATIVO)
- Paginação de listagens
- Cálculo de idade (`getIdade()` via `Period.between()`)
- Tratamento global de exceções (`GlobalExceptionHandler`)
- Documentação via Swagger (SpringDoc)

### Responsabilidades Ausentes

| Problema | Descrição |
|----------|-----------|
| ❌ Sem controle de autorização | Qualquer pessoa pode criar/editar/excluir membros |
| ❌ Sem validação de CPF/RG | Campos críticos aceitos sem validação de formato |
| ❌ Sem verificação de duplicidade | Dois membros com mesmo CPF ou email podem ser cadastrados |
| ⚠️ `createdAt` auditado, sem `updatedAt` | Não há rastreamento de quando o registro foi modificado |

### Modelo de Dados (campos principais)
```
Member:
  id, nome, sexo (MASCULINO/FEMININO/OUTRO),
  dataNascimento, idade (calculada),
  igreja, dataFiliacao,
  telefone, email, rg, cpf,
  gruposMinisterios (TEXT livre),
  observacoes, status (ATIVO/INATIVO),
  createdAt
```

### Dependências Externas
- PostgreSQL (`memberdb` na porta 5432)
- Eureka Server (registro)

### Avaliação de Responsabilidade
✅ Domínio bem implementado para o estágio atual. A ausência de validações e duplicidade são os principais pontos de melhoria.

---

## 5. financial-service

### Domínio Principal
Gestão de movimentações financeiras da congregação (entradas, saídas, saldo, anexos).

### Responsabilidades Corretas
- CRUD de transações financeiras
- Soft delete (active = false)
- Cálculo de saldo (ENTRADA - SAÍDA)
- Consulta por período
- Categorização de transações
- Rastreamento de criador da transação (`created_by`)

### Responsabilidades Ausentes / Incorretas

| Problema | Descrição |
|----------|-----------|
| ❌ `createdBy` hardcoded com `randomUUID()` | O campo `createdBy` deveria vir do JWT/header, mas usa UUID aleatório (TODO no código) |
| ❌ Sem lógica de anexos funcionando | A entidade `Attachment` e `AttachmentRepository` existem, mas não há endpoint de upload/download |
| ❌ MinIO não está configurado | Dependência presente mas sem configuração de bucket, endpoint ou credenciais |
| ❌ Sem autorização por papel | Qualquer usuário pode criar transações ou consultar o saldo |
| ❌ Sem relatórios | Não há endpoint de extrato, resumo mensal ou exportação |

### Categorias de Transação (Enum TransactionCategory)
```
DIZIMO         — Dízimos recebidos
OFERTA         — Ofertas recebidas
DESPESA_FIXA   — Despesas recorrentes
DESPESA_VARIAVEL — Despesas eventuais
DOACAO         — Doações recebidas
OUTROS         — Outros lançamentos
```

### Tipos de Transação (Enum TransactionType)
```
ENTRADA — Receitas
SAIDA   — Despesas
```

### Dependências Externas
- PostgreSQL (`financialdb` na porta 5434)
- MinIO (armazenamento de anexos — não configurado)
- Eureka Server (registro)

### Avaliação de Responsabilidade
⚠️ Domínio bem estruturado, mas com funcionalidades incompletas críticas (createdBy, upload de anexos).  
O código de anexos está no modelo de dados mas não há API exposta.
