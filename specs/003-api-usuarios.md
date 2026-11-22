# Spec 003 - API de usuários

Status: implementada em 2026-11-22. Critérios de aceite validados por testes unitários e de integração (Testcontainers).

## Contexto
O "livro da guilda" registra membros (usuários) e seus papéis. A API precisa de CRUD completo com validação, paginação, busca e filtros, além de troca de senha e ativação/desativação. Apenas `ADMIN` pode gerenciar usuários; o próprio usuário pode trocar sua senha.

## Objetivo
Expor `/api/v1/users` (CRUD, paginação, busca, filtros) e `/api/v1/me/password` (troca de senha do autenticado), com validação, respostas padronizadas e documentação OpenAPI.

## Fora de escopo
- Auto-cadastro público de usuários.
- Upload de avatar, perfis estendidos.
- Exclusão lógica (soft delete) — exclusão é física.
- Gestão de papéis além de USER/ADMIN.

## Requisitos funcionais
- RF1: `POST /api/v1/users` cria usuário (nome, e-mail, papel, senha). E-mail normalizado para minúsculas; protegido por índice único.
- RF2: `GET /api/v1/users` lista com paginação (`page`, `size` máx. 50, `sort`), busca (`search` em nome/e-mail, case-insensitive) e filtros (`role`, `active`).
- RF3: `GET /api/v1/users/{id}` busca por id.
- RF4: `PUT /api/v1/users/{id}` atualiza nome, e-mail e papel. Não altera senha.
- RF5: `PATCH /api/v1/users/{id}/status` ativa/desativa (`active`).
- RF6: `DELETE /api/v1/users/{id}` remove permanentemente (204).
- RF7: `PUT /api/v1/me/password` troca a senha do autenticado (exige senha atual).
- RF8: Usuário inativo não autentica.
- RF9: Alteração de e-mail limpa token de redefinição pendente (spec 001).
- RF10: Alteração de papel invalida refresh tokens anteriores.
- RF11: Desativação limpa token de redefinição pendente.
- RF12: Não é possível desativar/excluir o próprio usuário nem o último admin ativo (regra de negócio, 400).
- RF13: E-mail duplicado retorna 409 `EMAIL_ALREADY_EXISTS`.

## Requisitos nao funcionais
- RNF1: Senha armazenada em BCrypt (custo padrão).
- RNF2: Validação via Jakarta Bean Validation nos DTOs (nome, e-mail, papel, senha 8-72).
- RNF3: Respostas de erro em `application/problem+json` com `code`.
- RNF4: Paginação padrão: size 10, sort `createdAt` desc; máx. 50 por página.
- RNF5: Busca usa `Pattern.quote` (sem injeção de regex).
- RNF6: Índice único de e-mail criado na subida (`auto-index-creation`).
- RNF7: `UserResponse` não expõe senha nem campos de reset.
- RNF8: Auditoria registra criação, atualização, mudança de status, exclusão, troca de senha e logout.

## Endpoints
| Método | Rota | Descrição | Auth | Sucesso |
|--------|------|-----------|------|---------|
| GET | /api/v1/users | Lista paginada com busca/filtros | ADMIN | 200 |
| GET | /api/v1/users/{id} | Busca por id | ADMIN | 200 |
| POST | /api/v1/users | Cria usuário | ADMIN | 201 (+Location) |
| PUT | /api/v1/users/{id} | Atualiza | ADMIN | 200 |
| PATCH | /api/v1/users/{id}/status | Ativa/desativa | ADMIN | 200 |
| DELETE | /api/v1/users/{id} | Remove | ADMIN | 204 |
| PUT | /api/v1/me/password | Troca própria senha | autenticado | 204 |

## Fluxo
### Criação
1. Valida corpo (nome, e-mail, papel, senha).
2. Normaliza e-mail; verifica duplicidade (409 se existir).
3. Codifica senha em BCrypt; salva.
4. Registra auditoria `USER_CREATED`; retorna 201 com `Location`.

### Listagem
1. Aplica filtros (search case-insensitive em nome/e-mail, role, active).
2. Pagina e ordena (padrão `createdAt` desc, máx. 50).
3. Retorna `PageResponse`.

### Atualização
1. Busca usuário (404 se inexistente).
2. Valida e-mail duplicado por outro usuário (409).
3. Atualiza campos; limpa token de reset se e-mail mudou; rotaciona `tokenVersion` se papel mudou.
4. Registra auditoria `USER_UPDATED`.

### Desativação/Exclusão
1. Bloqueia auto-operação e último admin ativo (400).
2. Salva/exclui; registra auditoria.

## Regras de negocio
- E-mail é chave única (case-insensitive via normalização).
- Senha tem 8-72 caracteres; BCrypt com custo padrão.
- `role` é `USER` ou `ADMIN`.
- Usuário inativo não autentica.
- Exclusão é física; auditoria permanece.
- Último admin ativo não pode ser desativado/excluído.

## Modelo de dados
- `User` (coleção `usuarios`): `id`, `name`, `email` (único), `passwordHash`, `role`, `active`, `tokenVersion`, `createdAt`, `updatedAt`, `passwordResetTokenHash` (esparso, spec 001), `passwordResetExpiresAt`.
- `UserResponse`: `id`, `name`, `email`, `role`, `active`, `createdAt`, `updatedAt` (sem senha/hash).

## Configuracao
| Propriedade | Padrão | Uso |
|-------------|--------|-----|
| `app.bootstrap.admin.email/password` | vazio | Cria primeiro ADMIN em banco vazio (perfil não dev/test/demo) |
| `spring.data.web.pageable.max-page-size` | 50 | Tamanho máximo de página |

## Erros esperados
| Status | Código | Quando |
|--------|--------|--------|
| 400 | VALIDATION_ERROR | Corpo inválido |
| 400 | INVALID_REQUEST | Regra de negócio (self/último admin) |
| 401 | INVALID_CREDENTIALS | Token ausente/inválido |
| 403 | ACCESS_DENIED | Sem papel ADMIN |
| 403 | READ_ONLY_DEMO | Demo, em escrita |
| 404 | RESOURCE_NOT_FOUND | Usuário inexistente |
| 409 | EMAIL_ALREADY_EXISTS | E-mail duplicado |
| 405 | METHOD_NOT_ALLOWED | Método HTTP não suportado |

## Testes esperados
- Criação retorna 201 + Location; e-mail normalizado.
- E-mail duplicado (case-insensitive) retorna 409.
- Validação de corpo (400) com detalhes por campo.
- Paginação, busca e filtros funcionam.
- Atualização altera nome/e-mail/papel; e-mail duplicado por outro retorna 409.
- Ativação/desativação altera `active`.
- Exclusão retorna 204 e recurso deixa de existir.
- `USER` recebe 403 em todas as rotas.
- Demo retorna 403 em escrita.
- Troca de senha exige senha atual (400 se errada).

## Critérios de aceite
- [x] CRUD completo com validação.
- [x] Paginação com busca e filtros.
- [x] E-mail único (case-insensitive).
- [x] Respostas padronizadas (problem+json).
- [x] Apenas ADMIN acessa; USER recebe 403.
- [x] Troca de senha do autenticado funciona.
- [x] Demo bloqueia escrita (403).
- [x] Auditoria registra ações.

## Limitações e riscos
- Exclusão física (sem soft delete).
- Sem auto-cadastro público.
- Busca por regex pode degradar com volume grande.
- Contagem/página podem divergir sob escritas concorrentes (sem transação).

## Decisões
- Exclusão física + auditoria: histórico preservado na auditoria.
- Papel apenas USER/ADMIN: simplicidade para o escopo.
- E-mail como subject do JWT: mudança de e-mail invalida refresh indiretamente.