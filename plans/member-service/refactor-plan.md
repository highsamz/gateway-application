# Plano de Refatoração — member-service

---

## Estado Atual

O member-service é o mais maduro em termos de qualidade. A maioria das refatorações aqui são incrementais, não estruturais.

---

## [REFACTOR-MBR-01] Padronizar GlobalExceptionHandler

### Estado Atual (positivo)
O member-service é o ÚNICO serviço do ecossistema com `GlobalExceptionHandler` implementado. Este é o padrão que os outros serviços devem seguir.

### O que verificar e melhorar

```java
// Casos que o handler atual provavelmente NÃO cobre ainda:
// 1. DataIntegrityViolationException (duplicidade de CPF/email após MBR-01)
// 2. HttpMessageNotReadableException (JSON inválido no body)
// 3. MethodNotAllowedException

@ExceptionHandler(DataIntegrityViolationException.class)
public ResponseEntity<ApiError> handleDuplicate(DataIntegrityViolationException e) {
    if (e.getMessage().contains("uq_member_cpf")) {
        return ResponseEntity.status(409).body(new ApiError(409, "DUPLICATE_CPF", "CPF already registered"));
    }
    // ...
}

@ExceptionHandler(HttpMessageNotReadableException.class)
public ResponseEntity<ApiError> handleMalformedJson(HttpMessageNotReadableException e) {
    return ResponseEntity.status(400).body(new ApiError(400, "MALFORMED_JSON", "Invalid request body"));
}
```

---

## [REFACTOR-MBR-02] Extrair Interface para MemberService

### Problema
`MemberService` é uma classe concreta sem interface. Viola DIP do SOLID.

```java
public interface MemberService {
    MemberResponseDTO create(MemberRequestDTO dto);
    MemberResponseDTO findById(UUID id);
    Page<MemberResponseDTO> findAll(Pageable pageable);
    MemberResponseDTO update(UUID id, MemberRequestDTO dto);
    void delete(UUID id);
}

@Service
public class MemberServiceImpl implements MemberService { ... }
```

---

## [REFACTOR-MBR-03] Mover getIdade() para MemberResponseDTO ou MemberMapper

### Problema atual
O método `getIdade()` está na entidade `Member.java`, calculando a idade com `Period.between()`. Lógica de apresentação não deveria estar na entidade.

```java
// Entidade Member — atualmente:
public int getIdade() {
    return Period.between(dataNascimento, LocalDate.now()).getYears();
}
```

### Solução
Mover o cálculo para o Mapper ao construir o `MemberResponseDTO`:

```java
// MemberMapper.java
public MemberResponseDTO toResponse(Member member) {
    int idade = member.getDataNascimento() != null
        ? Period.between(member.getDataNascimento(), LocalDate.now()).getYears()
        : 0;
    return new MemberResponseDTO(
        member.getId(),
        member.getNome(),
        // ...
        idade  // calculado no mapper, não na entidade
    );
}
```

---

## [REFACTOR-MBR-04] gruposMinisterios como Lista (em vez de TEXT livre)

### Problema atual
O campo `gruposMinisterios` é armazenado como `TEXT` livre no banco:
```java
private String gruposMinisterios; // ex: "Louvor, Jovens, Missões"
```

Isso dificulta:
- Filtrar membros por grupo específico
- Garantir consistência nos nomes dos grupos
- Relatórios por ministério

### Solução Futura (não obrigatório para MVP)
Criar tabela `ministerios` e relação ManyToMany:
```sql
CREATE TABLE ministerios (
    id UUID PRIMARY KEY,
    nome VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE member_ministerios (
    member_id UUID REFERENCES member(id),
    ministerio_id UUID REFERENCES ministerios(id),
    PRIMARY KEY (member_id, ministerio_id)
);
```

> Esta refatoração requer migração de dados (converter o texto livre em registros). Adiar para quando houver testes de integração.

---

## [REFACTOR-MBR-05] Soft Delete Inconsistente com Outros Serviços

### Problema
Cada serviço usa um campo diferente para soft delete:
- member-service: `status` (enum ATIVO/INATIVO)
- financial-service: `active` (boolean)
- auth-service: `enabled` (boolean)

### Impacto
Em uma futura query cross-service ou relatório consolidado, essa inconsistência cria confusão.

### Decisão
**Manter como está para o member-service** — o enum `Status` com `ATIVO/INATIVO` é semanticamente mais rico que um boolean e não há urgência em padronizar agora.

**Documentar a divergência** para decisão futura quando houver uma sprint de padronização.

---

## [REFACTOR-MBR-06] Validação de CPF por Algoritmo

### Problema
A validação atual (se houver) aceita qualquer string de 11 dígitos como CPF válido. CPFs como "00000000000" ou "11111111111" passam.

### Solução Futura

```java
public class CpfValidator implements ConstraintValidator<ValidCpf, String> {
    @Override
    public boolean isValid(String cpf, ConstraintValidatorContext context) {
        if (cpf == null || cpf.isBlank()) return true; // @NotBlank cuida do nulo
        String cleaned = cpf.replaceAll("[^0-9]", "");
        if (cleaned.length() != 11) return false;
        if (cleaned.chars().distinct().count() == 1) return false; // "111...1" inválido
        // implementar algoritmo de dígito verificador
        return validateDigits(cleaned);
    }
}
```

---

## Ordem de Execução

```
Sprint 1: REFACTOR-MBR-01 (melhorar ExceptionHandler existente)
Sprint 2: REFACTOR-MBR-02 (Interface MemberService) + REFACTOR-MBR-03 (getIdade no Mapper)
Sprint 3+: REFACTOR-MBR-04 (ministerios como lista) + REFACTOR-MBR-06 (validação CPF)
```
