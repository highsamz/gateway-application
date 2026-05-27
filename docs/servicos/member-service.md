# Serviço: member-service

**Criticidade:** 🟠 Alta (dados pessoais de membros — LGPD)

---

## Objetivo

Gerenciar o cadastro completo de membros da congregação, incluindo dados pessoais, eclesiásticos e de contato, com suporte a paginação, soft delete e consulta individual.

---

## Responsabilidades

### Corretas (implementadas)
- CRUD completo de membros
- Soft delete (status ATIVO/INATIVO)
- Paginação com ordenação por `createdAt DESC`
- Cálculo automático de idade (`Period.between()`)
- Tratamento global de exceções (`GlobalExceptionHandler`)
- Documentação automática via Swagger (SpringDoc)

### Ausentes / Incorretas
- ❌ Sem controle de autorização — qualquer requisição cria/edita membros
- ❌ Sem validação de unicidade (CPF, email duplicado permitido)
- ❌ Sem validação de formato de CPF/RG
- ❌ Sem campo `updatedAt` para auditoria de modificações
- ❌ SecurityConfig libera todos os endpoints

---

## Stack

| Item | Versão |
|------|--------|
| Spring Boot | **3.3.2** (desatualizado vs demais) |
| Spring Cloud | **2023.0.3** (desatualizado vs demais) |
| Spring Data JPA | Gerenciada pelo Boot |
| PostgreSQL | 5432 |
| Flyway | Gerenciada pelo Boot |
| SpringDoc OpenAPI | Presente |
| Docker | Dockerfile + docker-compose presentes |
| Java | 21 |

---

## Modelo de Dados

### Tabela: `member`
```sql
id                UUID PRIMARY KEY DEFAULT gen_random_uuid()
nome              VARCHAR NOT NULL
sexo              VARCHAR NOT NULL                  -- MASCULINO | FEMININO | OUTRO
data_nascimento   DATE NOT NULL
igreja            VARCHAR
data_filiacao     DATE
telefone          VARCHAR
email             VARCHAR
rg                VARCHAR
cpf               VARCHAR
grupos_ministerios TEXT                              -- texto livre, sem normalização
observacoes       TEXT
status            VARCHAR DEFAULT 'ATIVO'           -- ATIVO | INATIVO
created_at        TIMESTAMP DEFAULT now()
```

---

## Principais Endpoints

### POST /members
```
Request:  MemberRequestDTO
Response 201: MemberResponseDTO
```

### GET /members/{id}
```
Response 200: MemberResponseDTO
Response 404: ApiErrorResponse { status: 404, message: "Membro não encontrado" }
```

### GET /members?page=0&size=20&sort=createdAt,DESC
```
Response 200: Page<MemberResponseDTO>
  {
    "content": [...],
    "totalElements": 150,
    "totalPages": 8,
    "size": 20
  }
```

### PUT /members/{id}
```
Request:  MemberRequestDTO (todos os campos)
Response 200: MemberResponseDTO
Response 404: ApiErrorResponse
```

### DELETE /members/{id}
```
Response 200: (sem body) — soft delete, status muda para INATIVO
```

---

## DTOs

### MemberRequestDTO (entrada)
```
nome, sexo (Sexo enum), dataNascimento,
igreja, dataFiliacao, telefone, email,
rg, cpf, gruposMinisterios, observacoes
```

### MemberResponseDTO (saída)
```
id, nome, sexo, dataNascimento, idade (calculado),
igreja, dataFiliacao, telefone, email,
rg, cpf, gruposMinisterios, observacoes
```

---

## Fluxo Interno

### Criação
```
POST /members
  └── MemberController.create(MemberRequestDTO)
       └── MemberService.create(dto)
            ├── MemberMapper.toEntity(dto) → Member
            ├── Member.status = ATIVO (default)
            ├── MemberRepository.save(member)
            └── MemberMapper.toResponseDTO(saved) → MemberResponseDTO
```

### Deleção
```
DELETE /members/{id}
  └── MemberController.delete(id)
       └── MemberService.delete(id)
            ├── MemberRepository.findById(id) → [MemberNotFoundException se ausente]
            ├── member.setStatus(Status.INATIVO)
            └── MemberRepository.save(member)
```

---

## Dependências

- **PostgreSQL** (`memberdb` na porta 5432) — obrigatório
- **Eureka Server** (8761) — para registro e descoberta
- **Gateway** — recebe requisições roteadas por `/members/**`

---

## Problemas Encontrados

| Problema | Severidade | Descrição |
|----------|------------|-----------|
| Versão Spring desatualizada | 🟠 Alto | 3.3.2 vs 3.5.x dos demais — comportamentos divergentes |
| CPF/email duplicado | 🟠 Alto | Dois membros com mesmo CPF podem ser cadastrados |
| Sem @Valid nos DTOs | 🟠 Alto | Dados inválidos são persistidos |
| Sem campo updatedAt | 🟡 Médio | Impossível saber quando um registro foi modificado |
| grupos_ministerios como TEXT | 🟡 Médio | Campo livre sem normalização — buscas e relatórios serão imprecisos |
| Credenciais DB hardcoded | 🔴 Crítico | `postgres/postgres` no código |
| Sem autorização por role | 🟠 Alto | Secretário e Tesoureiro veem e editam os mesmos dados |

---

## Melhorias Sugeridas

### Prioridade Alta

1. **Atualizar Spring Boot para 3.4.4** (alinhar com gateway)
2. **Adicionar constraint de unicidade no CPF e email:**
   ```sql
   ALTER TABLE member ADD CONSTRAINT uniq_member_cpf UNIQUE (cpf);
   ALTER TABLE member ADD CONSTRAINT uniq_member_email UNIQUE (email);
   ```
3. **Adicionar @Valid e anotações de validação nos DTOs:**
   ```java
   public record MemberRequestDTO(
       @NotBlank String nome,
       @NotNull Sexo sexo,
       @NotNull LocalDate dataNascimento,
       @Pattern(regexp = "\\d{11}") String cpf,
       @Email String email
   ) {}
   ```
4. **Adicionar `updatedAt` com `@UpdateTimestamp`**

### Prioridade Média

5. Normalizar `grupos_ministerios` em tabela separada (N:M)
6. Implementar autorização baseada em roles (SECRETARIO gerencia membros)
7. Adicionar Actuator para health check
8. Externalizar credenciais DB via variáveis de ambiente

---

## Nível de Criticidade

🟠 **Alta** — Contém dados pessoais de membros (nome, CPF, RG, email, telefone). Sujeito à LGPD. A ausência de autenticação/autorização significa que qualquer pessoa pode ler ou modificar esses dados.
