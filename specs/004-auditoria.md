# Spec 004 - Auditoria de ações administrativas

Status: implementada em 2026-11-22. Critérios de aceite validados por testes unitários e de integração (Testcontainers).

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
- RF3: `GET /api/v1/audit` (ADMIN) lista eventos com filtros: `action`, `actorEmail`, `targetUserId`, `from`, `to` (ISO-8601), paginação (`page`, `size`, `sort`).
- RF4: IP capturado do request (`getRemoteAddr`).
- RF5: Detalhes nunca incluem token, hash ou senha.
- RF6: Ações auditadas: `USER_CREATED`, `USER_UPDATED`, `USER_STATUS_CHANGED`, `USER_DELETED`, `PASSWORD_CHANGED`, `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_COMPLETED`, `USER_LOGGED_OUT`.

## Requisitos nao funcionais
- RNF1: Eventos persistidos em coleção `audit_events` (MongoDB).
- RNF2: Busca por `actorEmail` é case-insensitive com escaping seguro (sem injeção de regex).
- RNF3: Paginação padrão: size 20, sort `timestamp` desc.
- RNF4: Filtros combináveis (AND).
- RNF5: Erros de auditoria não devem mascarar a operação principal? (atualmente propagam 500; ver Limitações).

## Endpoints
| Método | Rota | Descrição | Auth | Sucesso |
|--------|------|-----------|------|---------|
| GET | /api/v1/audit | Lista eventos com filtros | ADMIN | 200 |

## Fluxo
### Registro
1. Serviço de negócio chama `AuditService.record(action, target, details)` (rotas autenticadas) ou `recordSystem(action, target, details)` (rotas públicas).
2. `record` obtém ator do `SecurityContext` (ou `system` se anônimo).
3. `recordSystem` grava ator `system`/`SYSTEM` sem consultar o SecurityContext.
4. IP obtido de `RequestContextHolder`; salva o evento.

### Listagem
1. Aplica filtros (action, actorEmail regex case-insensitive, targetUserId, from/to).
2. Pagina e ordena; retorna `PageResponse<AuditEventResponse>`.

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
| 401 | INVALID_CREDENTIALS | Token ausente/inválido |
| 403 | ACCESS_DENIED | Sem papel ADMIN |
| 400 | INVALID_REQUEST | Parâmetro inválido (ex.: sort) |

## Testes esperados
- Eventos são criados nas ações administrativas.
- `PASSWORD_RESET_REQUESTED`/`COMPLETED` com ator `system`, IP e alvo corretos, sem token/hash nos detalhes.
- Listagem filtra por action, actorEmail, targetUserId, período.
- Paginação e ordenação funcionam.
- `USER` recebe 403.

## Critérios de aceite
- [x] Eventos registrados para todas as ações administrativas.
- [x] Ator correto em rotas públicas (`system`).
- [x] Listagem filtrada e paginada para ADMIN.
- [x] Detalhes sem segredos.
- [x] IP capturado.

## Limitações e riscos
- Sem política de retenção; coleção pode crescer indefinidamente.
- Erro ao salvar evento propaga 500 e pode mascarar a operação principal.
- Sem transação entre operação principal e auditoria.
- Contagem/página podem divergir sob escritas concorrentes.

## Decisões
- `recordSystem` separado de `record`: evita gravar `anonymousUser` em rotas públicas.
- IP via `RequestContextHolder`: funciona em controllers MVC padrão.