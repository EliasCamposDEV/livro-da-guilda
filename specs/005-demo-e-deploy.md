# Spec 005 - Demo pública e deploy

Status: implementada em 2026-11-22. Critérios de aceite validados por testes unitários e de integração (MongoDB embarcado (dev/test) e Testcontainers (2 testes de integracao)).

## Contexto
A API precisa ser demonstrável publicamente sem risco de alteração de dados. A demo é somente leitura: visitante pode logar e ler, mas não escreve. Além disso, a aplicação precisa ser publicável em plataforma gratuita (Render) com documentação clara.

## Objetivo
Prover perfil `demo` com dados fictícios e bloqueio de escrita, landing page redirecionando para o Swagger, e documentação de deploy/backup/operação.

## Fora de escopo
- Multi-tenancy.
- Ambiente de staging separado.
- CI/CD além do GitHub Actions (build + test).

## Requisitos funcionais
- RF1: Perfil `demo` cria usuários fictícios na primeira subida (admin demo + 6 usuários).
- RF2: Um usuário inativo é criado para demonstrar filtro `active=false`.
- RF3: `DemoReadOnlyFilter` bloqueia `POST`/`PUT`/`PATCH`/`DELETE` em `/api/v1/**` e em `/auth/forgot-password`, `/auth/reset-password` com 403 `READ_ONLY_DEMO`.
- RF4: `GET`, `HEAD`, `OPTIONS` permanecem liberados (leitura).
- RF5: Login, refresh e logout continuam funcionando na demo.
- RF6: Raiz `/` redireciona para `/swagger-ui.html` (apenas no perfil demo).
- RF7: Swagger UI habilitado com try-it-out, persistência de autorização.
- RF8: CI (`mvn -B verify`) roda build + testes (incluindo MongoDB embarcado (dev/test) e Testcontainers (2 testes de integracao)) no GitHub Actions.
- RF9: Deploy documentado para Render (Dockerfile, render.yaml, docs/deploy-render.md).
- RF10: Backup e rotação de segredo documentados (docs/operacao.md).

## Requisitos nao funcionais
- RNF1: Filtro de demo ativo apenas com `app.demo.read-only=true` (perfil demo).
- RNF2: Filtro tem precedência alta (ordem) e usa caminho normalizado/decodificado (sem bypass por encoding).
- RNF3: Credenciais demo são públicas por design e documentadas.
- RNF4: Render hiberna por inatividade (primeiro request pode levar 30-60s).
- RNF5: `JWT_SECRET` e `MONGODB_URI` obrigatórios em prod (falha ao subir sem eles).
- RNF6: `show-details: never` em prod/demo; `always` em dev/test.

## Endpoints
| Método | Rota | Descrição | Auth | Sucesso |
|--------|------|-----------|------|---------|
| GET | / | Redireciona para Swagger (demo) | público | 302 |
| GET | /actuator/health | Health check | público | 200 |
| GET | /swagger-ui.html | Swagger UI | público | 200 |
| GET | /v3/api-docs | OpenAPI JSON | público | 200 |

## Fluxo
### Demo
1. Subida com perfil `demo`: seeder popula usuários fictícios (se banco vazio).
2. `DemoReadOnlyFilter` intercepta escritas e retorna 403 `READ_ONLY_DEMO` antes do controller.
3. Visitante loga com credenciais demo, copia token, usa Swagger com try-it-out.

### Deploy (Render)
1. Build via Dockerfile (JDK 21, mvn).
2. Variáveis: `JWT_SECRET`, `MONGODB_URI`, `APP_BOOTSTRAP_ADMIN_*` (prod), `CORS_ALLOWED_ORIGINS`.
3. Health check em `/actuator/health`.
4. Primeiro acesso pode levar ~1 min (free tier).

## Regras de negocio
- Demo é somente leitura por design; escrita retorna 403 sem alterar o banco.
- Credenciais demo são públicas e documentadas (login + Swagger).
- Perfil `dev` cria admin `admin@email.com`/`admin12345` se banco vazio.
- Perfil prod não cria usuários sem `APP_BOOTSTRAP_ADMIN_*`.

## Modelo de dados
- Demo: 7 usuários (1 admin demo, 5 users, 1 admin extra, 1 inativo). Todos com a mesma senha demo.

## Configuracao
| Propriedade | Padrão | Uso |
|-------------|--------|-----|
| `app.demo.read-only` | false | Ativa filtro somente leitura (perfil demo) |
| `app.demo.admin.email/password` | - | Credenciais do admin demo |
| `app.security.password-reset.delivery` | none (prod) | Demo mantém `none` |
| `JWT_SECRET` | - | Obrigatório em prod |
| `MONGODB_URI` | localhost:27018 | Obrigatório em prod |
| `CORS_ALLOWED_ORIGINS` | vazio | Origens liberadas |

## Erros esperados
| Status | Código | Quando |
|--------|--------|--------|
| 403 | READ_ONLY_DEMO | Escrita na demo |
| 401 | INVALID_CREDENTIALS | Login inválido |
| 503 | (Render) | Instância hibernando |

## Testes esperados
- Demo: login e leitura funcionam; escrita retorna 403 sem alterar banco.
- `DemoReadOnlyFilterTest`: paths bloqueados, encoding, métodos seguros.
- CI verde com MongoDB embarcado (dev/test) e Testcontainers (2 testes de integracao).

## Critérios de aceite
- [x] Perfil demo com dados fictícios.
- [x] Escrita bloqueada (403) sem alterar banco.
- [x] Login/refresh/logout funcionam na demo.
- [x] Landing redireciona para Swagger.
- [x] CI verde.
- [x] Deploy documentado.

## Limitações e riscos
- Render hiberna por inatividade (primeiro request lento).
- Credenciais demo públicas (por design).
- Filtro de demo por path pode ser burlado se novos endpoints de escrita forem adicionados sem atualizar a lista (mitigado por `startsWith("/api/v1/")`).

## Decisões
- Demo somente leitura por design: permite compartilhar link sem risco.
- Dados fictícios com mesma senha: simplifica a experiência do visitante.
- Swagger habilitado em `dev` e `demo`; desabilitado em `prod` por decisão de segurança.
