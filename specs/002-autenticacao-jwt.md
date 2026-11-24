# Spec 002 - Autenticacao e JWT

Status: implementada em 2026-11-22. Critérios de aceite validados por testes unitários e de integração (Testcontainers).

## Contexto
A API precisa autenticar clientes (humanos ou outros sistemas) e autorizar acesso aos recursos protegidos por papel (`ADMIN`). O cadastro de usuários (spec 003) depende deste fluxo para emitir tokens. Sem autenticação, toda a API de escrita ficaria exposta.

## Objetivo
Fornecer login com e-mail/senha, emissão de access token (1 h) e refresh token (7 dias) rotativo, logout, troca de senha do usuário autenticado e proteção contra força bruta no login.

## Fora de escopo
- OAuth2/OIDC, SSO, MFA.
- Revogação imediata de access tokens (ver Limitações).
- Rate limit distribuído (Redis) entre réplicas.
- Blacklist de tokens.

## Requisitos funcionais
- RF1: `POST /auth/login` recebe `{ "email", "password" }`. E-mail é normalizado (trim + lowercase) antes da busca. Retorna `accessToken` (1 h) e `refreshToken` (7 dias).
- RF2: Usuário com `active: false` não autentica (401).
- RF3: Papel vem do campo `role`; token de `USER` recebe 403 em `/api/v1/users/**` e `/api/v1/audit`.
- RF4: `POST /auth/refresh` troca um refresh token válido por um novo par (access + refresh). O refresh é rotativo: cada uso invalida o anterior. Só o refresh mais recente de cada usuário vale; um novo login (ou refresh) derruba os anteriores.
- RF5: Um refresh token não é aceito como access token nas rotas protegidas (401).
- RF6: `POST /auth/logout` (exige access token) invalida os refresh tokens do usuário (incrementa `tokenVersion`).
- RF7: `PUT /api/v1/me/password` exige a senha atual e derruba os refresh tokens anteriores (incrementa `tokenVersion`).
- RF8: Senha atual errada devolve 400 `INVALID_PASSWORD`.
- RF9: Login bloqueia após 5 tentativas erradas por e-mail + IP em 15 minutos (429 `TOO_MANY_ATTEMPTS` com `Retry-After`).
- RF10: Refresh token inválido/expirado/usado/usuário desativado/invalidado por outro login devolve 401 `INVALID_REFRESH_TOKEN`.
- RF11: `/auth/login`, `/auth/refresh`, `/auth/forgot-password`, `/auth/reset-password`, Swagger, health check e `/` são públicos; demais rotas exigem autenticação; `/api/v1/users/**` e `/api/v1/audit/**` exigem `ADMIN`.

## Requisitos nao funcionais
- RNF1: JWT HS256, segredo mínimo de 32 caracteres (`JWT_SECRET` obrigatória em prod; default de dev não pode ser usado em prod).
- RNF2: Access token expira em 1 h (`app.security.jwt.ttl-seconds`); refresh em 7 dias (`refresh-ttl-seconds`).
- RNF3: Refresh token carrega claim `ver` com `tokenVersion`; a validação compara com o valor atual do usuário.
- RNF4: Rate limit de login em memória, por instância, por e-mail + IP, janela de 15 min, 5 tentativas. Serializado por chave para evitar corrida.
- RNF5: CORS desligado por padrão; libere origens com `CORS_ALLOWED_ORIGINS`.
- RNF6: CSRF desabilitado (API stateless por token).
- RNF7: Sessão stateless (`SessionCreationPolicy.STATELESS`).
- RNF8: Access tokens já emitidos continuam válidos até expirar (1 h) mesmo após logout/troca de senha/alteração de papel (limitação documentada).

## Endpoints
| Método | Rota | Descrição | Auth | Sucesso |
|--------|------|-----------|------|---------|
| POST | /auth/login | Autentica e emite par de tokens | público | 200 |
| POST | /auth/refresh | Troca refresh por novo par | público | 200 |
| POST | /auth/logout | Invalida refresh tokens | Bearer access | 204 |
| PUT | /api/v1/me/password | Troca a própria senha | Bearer access | 204 |

## Fluxo
### Login
1. `LoginRateLimiter.check(key)` (e-mail + IP); se excedeu, 429.
2. `AuthenticationManager.authenticate`; em falha, `recordFailure` e 401.
3. Em sucesso, `reset` e emissão do par de tokens via `JwtService.issue`.

### Refresh
1. Decodifica e valida o refresh token (tipo, issuer, expiração).
2. Busca usuário por e-mail (subject); deve existir e estar ativo.
3. Compara `ver` com `tokenVersion`; se diferente, 401.
4. Incrementa `tokenVersion` atomicamente (rotativo).
5. Emite novo par.

### Logout / troca de senha
1. Incrementa `tokenVersion` (invalida refresh tokens anteriores).

## Regras de negocio
- Refresh é rotativo: cada uso devolve par novo e invalida o anterior.
- Só o refresh mais recente de cada usuário vale.
- Access token já emitido continua válido até expirar (1 h).
- Troca de senha exige senha atual (`INVALID_PASSWORD` se errada).
- Usuário inativo não autentica.
- Papel vem do campo `role`; `USER` recebe 403 em rotas de admin.

## Modelo de dados
- `User`: `tokenVersion` (int, default 0). Incrementado em login/refresh/logout/troca de senha/reset de senha.
- JWT access: subject = id imutável do usuário, claims `roles`, `type=access`.
- JWT refresh: subject = id imutável do usuário, claims `roles`, `type=refresh`, `ver=tokenVersion`.

## Configuracao
| Propriedade | Padrão | Uso |
|-------------|--------|-----|
| `app.security.jwt.secret` | dev-only (≥32 chars) | Segredo HS256 (obrigatório em prod via `JWT_SECRET`) |
| `app.security.jwt.issuer` | `livro-da-guilda` | Emissor do token |
| `app.security.jwt.ttl-seconds` | 3600 | Validade do access token |
| `app.security.jwt.refresh-ttl-seconds` | 604800 | Validade do refresh token |
| `CORS_ALLOWED_ORIGINS` | vazio | Origens liberadas (CORS desligado por padrão) |

## Erros esperados
| Status | Código | Quando |
|--------|--------|--------|
| 400 | VALIDATION_ERROR | Corpo inválido |
| 401 | INVALID_CREDENTIALS | E-mail/senha inválidos |
| 401 | INVALID_REFRESH_TOKEN | Refresh inválido/expirado/usado/invalidado |
| 400 | INVALID_PASSWORD | Senha atual incorreta |
| 429 | TOO_MANY_ATTEMPTS | 5 falhas em 15 min (com `Retry-After`) |
| 403 | ACCESS_DENIED | Sem papel suficiente |
| 403 | READ_ONLY_DEMO | Demo, em escrita |

## Testes esperados
- Login com credenciais válidas retorna par de tokens.
- Login com credencial inválida retorna 401 e incrementa contador.
- Bloqueio após 5 falhas (429 + Retry-After).
- Refresh rotativo: reuso do token antigo retorna 401.
- Access token não é aceito como refresh (e vice-versa).
- Logout invalida refresh (401 ao reusar).
- Troca de senha invalida refresh antigo.
- Usuário inativo não autentica.
- `USER` recebe 403 em `/api/v1/users/**`.

## Critérios de aceite
- [x] Login emite access (1 h) e refresh (7 dias).
- [x] Refresh rotativo invalida o anterior.
- [x] Logout e troca de senha invalidam refresh tokens anteriores.
- [x] Bloqueio por força bruta (5/15min) com 429 + Retry-After.
- [x] Refresh inválido retorna 401 `INVALID_REFRESH_TOKEN`.
- [x] Usuário inativo não autentica.
- [x] Papel `USER` recebe 403 em rotas de admin.
- [x] CORS desligado por padrão.

## Limitações e riscos
- Access tokens não são revogados imediatamente; continuam válidos até expirar (1 h).
- Rate limit em memória/por instância.
- Segredo JWT default de dev não deve ser usado em prod.

## Decisões
- Refresh rotativo com `tokenVersion`: evita blacklist, simples e stateless.
- Access token stateless: revogação imediata exigiria checar `tokenVersion` em cada request (mudança separada).
- Bloqueio por e-mail + IP (não só IP) para evitar que atacante bloqueie vítima.