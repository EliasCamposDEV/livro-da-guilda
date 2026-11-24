# Spec 004 - Auditoria de ações administrativas

Status: implementada em 2026-11-22. Critérios de aceite validados por testes unitários e de integração (MongoDB embarcado (dev/test) e Testcontainers (4 testes de integracao)).

## Contexto
Operações administrativas (criação, edição, ativação, exclusão de usuários, troca de senha, logout, recuperação de senha) precisam ser rastreáveis para compliance e investigação. A auditoria registra quem fez, sobre quem, quando, de onde (IP) e detalhes da ação.

## Objetivo
Persistir eventos de auditoria em coleção dedicada e expor listagem paginada/filtrada para `ADMIN`, com ator correto mesmo em rotas públicas (recuperação de senha).

## Fora de escopo
- Retenção/expurgo de eventos.
- Exportação (CSV/PDF) da auditoria.
- Alertas em tempo real.
- Imutabilidade criptográfica dos eventos.

## Requisitos funcionais
- RF1: Toda ação administrativa gera um `AuditEvent` com: ação, ator (e-mail + papel), usuário afetado (id + e-mail), detalhes, IP, timestamp.
- RF2: Em rotas públicas (recuperação de senha), o ator é `system`/`SYSTEM`, nunca `anonymousUser`.
- RF3: `GET /api/v1/audit` (ADMIN) lista eventos com filtros: `action`, `actorEmail`, `targetUserId`, `from`, `to` (ISO-8601) e paginação por cursor (`cursor`, `limit` de 1 a 50).
- RF4: IP capturado do request (`getRemoteAddr`).
- RF5: Detalhes nunca incluem token, hash ou senha.
- RF6: Ações auditadas: `USER_CREATED`, `USER_UPDATED`, `USER_STATUS_CHANGED`, `USER_DELETED`, `PASSWORD_CHANGED`, `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_COMPLETED`, `USER_LOGGED_OUT`.

## Requisitos nao funcionais
- RNF1: Eventos persistidos em coleção `auditoria` (MongoDB).
- RNF2: Busca por `actorEmail` usa igualdade em e-mail normalizado (minúsculas), sem regex.
- RNF3: Paginação por keyset: `limit` padrão 20, ordem fixa `timestamp` desc e `_id` desc, `nextCursor` opaco. Sem `count` na listagem.
- RNF4: Filtros combináveis (AND).
- RNF5: Falhas de auditoria são registradas em log e não mascaram a operação principal.

## Endpoints
| Método | Rota | Descrição | Auth | Sucesso |
|--------|------|-----------|------|---------|
| GET | /api/v1/audit | Lista eventos com filtros e cursor | ADMIN | 200 |

## Fluxo
### Registro
1. Serviço de negócio chama `AuditService.record(action, target, details)` (rotas autenticadas) ou `recordSystem(action, target, details)` (rotas públicas).
2. `record` obtém ator do `SecurityContext` (ou `system` se anônimo).
3. `recordSystem` grava ator `system`/`SYSTEM` sem consultar o SecurityContext.
4. IP obtido de `RequestContextHolder`; salva o evento.

### Listagem
1. Aplica filtros (action, actorEmail, targetUserId, from/to) e, se houver cursor, `timestamp`/`_id` menores que os do cursor.
2. Busca `limit + 1` eventos ordenados por `timestamp` e `_id` decrescentes; descarta o extra para saber se há próxima página.
3. Retorna `CursorPage<AuditEventResponse>` com `content` e `nextCursor` (nulo na última página).

## Regras de negocio
- Auditoria de solicitação de reset só existe para usuário ativo encontrado (e-mail inexistente não gera evento).
- Detalhes nunca incluem token/hash/senha.
- Ator em rotas públicas é `system`, não `anonymousUser`.
- Eventos não são alterados após criação.

## Modelo de dados
- `AuditEvent`: `id`, `actorEmail`, `actorRole`, `action` (enum), `targetUserId`, `targetUserEmail`, `details` (map), `ipAddress`, `timestamp`.

## Configuracao
Nenhuma propriedade específica. Usa o MongoDB padrão da aplicação.

## Erros esperados
| Status | Código | Quando |
|--------|--------|--------|
| 401 | UNAUTHORIZED | Token ausente/inválido |
| 403 | ACCESS_DENIED | Sem papel ADMIN |
| 400 | INVALID_REQUEST | Cursor opaco inválido ou `limit` fora de 1 a 50 |

## Testes esperados
- Eventos são criados nas ações administrativas.
- `PASSWORD_RESET_REQUESTED`/`COMPLETED` com ator `system`, IP e alvo corretos, sem token/hash nos detalhes.
- Listagem filtra por action, actorEmail, targetUserId, período.
- Paginação por cursor percorre todos os eventos sem repetir e não expõe contagem total.
- Cursor inválido e `limit` fora de 1 a 50 retornam 400.
- `USER` recebe 403.

## Critérios de aceite
- [x] Eventos registrados para todas as ações administrativas.
- [x] Ator correto em rotas públicas (`system`).
- [x] Listagem filtrada e paginada por cursor para ADMIN (sem `count`).
- [x] Detalhes sem segredos.
- [x] IP capturado.

## Limitações e riscos
- Sem política de retenção; coleção pode crescer indefinidamente.
- Erro ao salvar evento é logado e contabilizado em `audit.write.failures`; a operação principal não é mascarada (risco aceito).
- Sem transação entre operação principal e auditoria.
- Sem total de registros: clientes percorrem `nextCursor` até ficar nulo.

## Decisões
- `recordSystem` separado de `record`: evita gravar `anonymousUser` em rotas públicas.
- IP via `RequestContextHolder`: funciona em controllers MVC padrão.
