# Spec 001 - Recuperacao de senha

Status: implementada em 2026-11-22. Critérios de aceite validados por testes unitários e de integração (MongoDB embarcado (dev/test) e Testcontainers (2 testes de integracao)).

## Contexto
Hoje o usuario so consegue trocar a senha estando autenticado (`PUT /api/v1/me/password`). Quem esqueceu a senha fica sem saida. A API tambem nao tem um canal para entregar um token de redefinicao.

## Objetivo
Permitir que o usuario solicite a redefinicao de senha e defina uma nova senha com um token opaco, de uso unico e curta duracao, sem revelar se um e-mail esta cadastrado.

## Fora de escopo
- Envio real de e-mail (SMTP ou provedor transacional). Esta entrega deixa a porta `PasswordResetNotifier` pronta, mas so implementa o canal de log em dev/test.
- Verificacao de e-mail, tela de recuperacao, i18n e CAPTCHA.
- Revogacao imediata de access tokens ja emitidos (ver Limitacoes).
- Multiplos tokens validos por usuario. Vale sempre o ultimo solicitado.
- Retencao e expiracao dos eventos de auditoria.

## Requisitos funcionais
- RF1: `POST /auth/forgot-password` recebe `{ "email": "..." }`. O e-mail passa por trim e lowercase antes da busca.
- RF2: Se existir usuario ativo, gera um token opaco de 256 bits e grava apenas o hash SHA-256 e a expiracao (15 min). Uma nova solicitacao invalida o token pendente anterior.
- RF3: A resposta e `204 No Content` para usuario ativo, inexistente ou inativo. Nao ha corpo e nenhum token e devolvido na resposta HTTP.
- RF4: `POST /auth/reset-password` recebe `{ "token": "...", "newPassword": "..." }` e, com token valido, grava a nova senha com BCrypt, incrementa `tokenVersion` e limpa o token pendente.
- RF5: O token e de uso unico. O consumo e atomico: em duas requisicoes concorrentes com o mesmo token, no maximo uma altera a senha.
- RF6: Token desconhecido, expirado, ja usado ou de usuario inativo retorna `400 INVALID_OR_EXPIRED_TOKEN`. Token expirado encontrado e limpo.
- RF7: Qualquer troca de senha (autenticada ou por reset), desativacao do usuario e alteracao de e-mail limpam o token pendente.
- RF8: A entrega usa a porta `PasswordResetNotifier`. Com `app.security.password-reset.delivery=log` (dev/test), o token bruto vai para o log com o marcador `PASSWORD_RESET_TOKEN`. Com `none` (padrao em prod), o endpoint responde `503 PASSWORD_RESET_UNAVAILABLE` e nao grava token.
- RF9: Falha do notifier nao deixa token pendente e responde `503 PASSWORD_RESET_UNAVAILABLE`.
- RF10: No perfil demo, os dois endpoints retornam `403 READ_ONLY_DEMO`. Login, refresh e logout continuam funcionando.
- RF11: Auditoria registra `PASSWORD_RESET_REQUESTED` e `PASSWORD_RESET_COMPLETED` para usuario existente e ativo, com usuario alvo e IP. O ator deve ser `system` / `SYSTEM`, nao `anonymousUser`. Os detalhes nunca incluem o token ou o hash.
- RF12: A nova senha segue a regra atual: 8 a 72 caracteres. O token tem no maximo 100 caracteres no corpo.
- RF13: As duas rotas entram no `permitAll` do `SecurityConfig`. Sem isso, `anyRequest().authenticated()` bloquearia o fluxo publico.

## Requisitos nao funcionais
- RNF1: Token com 32 bytes de `SecureRandom`, codificado em Base64 URL sem padding. Hash SHA-256, nao BCrypt: o token ja tem entropia suficiente e o hash rapido evita custo desnecessario.
- RNF2: O token nunca aparece em URL, resposta HTTP, auditoria ou log fora de dev/test. Ele viaja apenas no corpo do POST.
- RNF3: O hash fica indexado com indice unico e esparso (`unique = true, sparse = true`), permitindo busca O(1). O mapeamento nao grava null por padrao, entao o indice esparso nao conflita entre usuarios sem token.
- RNF4: `Clock` injetavel no servico para testar expiracao sem esperar 15 minutos.
- RNF5: Rate limit no forgot-password em duas dimensoes: 5 por hora por `email + IP` e 20 por hora por IP. Toda solicitacao conta, inclusive para e-mail inexistente ou inativo, para nao criar um oraculo de existencia. Excedeu: `429 TOO_MANY_ATTEMPTS` com `Retry-After`.
- RNF6: A mensagem do 429 nao pode falar em "login" neste endpoint. Usar texto neutro, por exemplo "Muitas solicitacoes. Tente novamente mais tarde."
- RNF7: O rate limit usa o `RateLimitStore` compartilhado com login e refresh. Padrao em memoria; com `APP_RATE_LIMIT_REDIS_ENABLED=true`, os contadores ficam no Redis. Aplicar limpeza de chaves expiradas e limite de chaves para evitar crescimento sem controle.
- RNF8: O endpoint de reset nao precisa de rate limit proprio: o token tem 256 bits e o BCrypt so e pago depois de o token ser validado. Erros de validacao do corpo sao rejeitados antes do consumo.
- RNF9: Swagger documenta os dois endpoints, os corpos, os status 204, 400, 403, 429 e 503, com exemplos reais. Nenhum schema de resposta inclui token. A descricao da demo deve incluir as rotas de recuperacao, e o exemplo de 429 nao pode falar em login.
- RNF10: Testes de integracao rodam com MongoDB embarcado (dev/test) e Testcontainers (2 testes de integracao) e `mvn verify` no CI.
- RNF11: Compatibilidade: os novos campos sao opcionais, nao alteram `UserResponse` e o indice e criado no start pela migração versionada `IndexMigration`.

## Endpoints
| Metodo | Rota | Descricao | Auth | Sucesso |
|--------|------|-----------|------|---------|
| POST | /auth/forgot-password | Solicita o token de redefinicao | publico | 204 |
| POST | /auth/reset-password | Define a nova senha com o token | publico | 204 |

## Fluxo
### Solicitar
1. Filtro de demo: no perfil demo, `403 READ_ONLY_DEMO` antes de qualquer processamento.
2. Validar o corpo (`email` obrigatorio, formato valido, ate 160 caracteres).
3. Normalizar o e-mail com trim e lowercase.
4. Aplicar o rate limit por `email + IP` e por IP. Se excedeu, 429.
5. Verificar se ha canal de entrega. Sem canal, 503 e nenhum token e gravado.
6. Buscar usuario ativo.
7. Se nao existir ou estiver inativo, responder 204 sem gravar nada.
8. Gerar o token, gravar hash e expiracao (substituindo o anterior) e entregar pelo notifier.
9. Registrar `PASSWORD_RESET_REQUESTED` e responder 204.

### Redefinir
1. Filtro de demo: no perfil demo, `403 READ_ONLY_DEMO` antes de qualquer processamento.
2. Validar o corpo (`token` obrigatorio ate 100 caracteres, `newPassword` de 8 a 72).
3. Calcular o hash do token e buscar o usuario.
4. Se nao achar, estiver expirado ou o usuario estiver inativo, limpar quando aplicavel e responder 400.
5. Gerar o hash BCrypt da nova senha.
6. Executar a troca condicional: mesmo usuario, mesmo hash, expiracao no futuro e usuario ativo. A atualizacao grava a senha, incrementa `tokenVersion` e remove os campos de reset.
7. Se a atualizacao condicional nao alterar nenhum documento, responder 400 (corrida ou token ja consumido).
8. Registrar `PASSWORD_RESET_COMPLETED` e responder 204.

## Regras de negocio
- So existe um token pendente por usuario. Pedir de novo substitui o anterior.
- O token vale 15 minutos por padrao (`app.security.password-reset.token-ttl`) e nao e um JWT. E um segredo opaco.
- Usuario inativo nao solicita e nao usa token.
- A resposta do forgot nao diferencia e-mail existente, inexistente ou inativo.
- O reset nao autentica o usuario nem devolve tokens. Ele apenas troca a senha.
- `tokenVersion` e incrementado no reset, entao refresh tokens antigos param de valer.
- Access tokens emitidos antes do reset continuam validos ate expirar (1 h). Ver Limitacoes.
- Auditoria de solicitacao so existe para usuario ativo encontrado. E-mails inexistentes nao viram eventos, para nao permitir encher a colecao com dados arbitrarios.
- O IP da auditoria e o mesmo ja capturado pelo `AuditService`.
- O token bruto so existe em memoria durante a requisicao e no log de dev/test.
- O fluxo publico nao pode gravar ator `anonymousUser`. Adicionar `AuditService.recordSystem(...)` ou tratar `AnonymousAuthenticationToken` como `system`.

## Modelo de dados
`User` (colecao `usuarios`):
- `passwordResetTokenHash`: String, opcional, `@Indexed(unique = true, sparse = true)`.
- `passwordResetExpiresAt`: Instant, opcional.
- Metodos: `startPasswordReset(hash, expiresAt)` e `clearPasswordReset()`.
- `changePassword` e `changeStatus(false)` limpam os campos de reset.
- `UserService.update` chama `clearPasswordReset()` quando o e-mail muda.
- `UserResponse` permanece inalterado.

Sem migracao: documentos antigos ficam sem os campos e o indice esparso aceita varios documentos sem o campo.

## Configuracao
| Propriedade | Padrao | Uso |
|-------------|--------|-----|
| `app.security.password-reset.delivery` | `none` | `log` em dev/test, `none` em prod |
| `app.security.password-reset.token-ttl` | `PT15M` | Validade do token |

- `application-dev.yml` e `application-test.yml`: `delivery: log`.
- `application-demo.yml`: mantem `delivery: none`; o filtro de demo bloqueia antes.
- `application-prod.yml`: herda `none` e responde 503 ate existir um provedor de e-mail.
- Limites do rate limit: constantes nesta entrega (5/h por email + IP, 20/h por IP). Se houver necessidade operacional, mover para configuracao.

## Erros esperados
| Status | Codigo | Quando |
|--------|--------|--------|
| 400 | VALIDATION_ERROR | Corpo invalido: e-mail, token ou senha fora das regras |
| 400 | INVALID_OR_EXPIRED_TOKEN | Token desconhecido, expirado, ja usado ou usuario inativo |
| 403 | READ_ONLY_DEMO | Perfil demo, antes de qualquer escrita |
| 429 | TOO_MANY_ATTEMPTS | Rate limit do forgot-password, com `Retry-After` |
| 503 | PASSWORD_RESET_UNAVAILABLE | Sem canal de entrega ou falha do notifier |
| 500 | INTERNAL_ERROR | Erro inesperado |

## Testes esperados
### Servico e token
- Token gerado tem 32 bytes, e o banco guarda apenas o hash SHA-256, nunca o valor bruto.
- Token expirado e rejeitado; token valido dentro da janela e aceito.
- Duas chamadas concorrentes com o mesmo token: so uma altera a senha.
- Nova solicitacao substitui o token anterior e invalida o primeiro.
- Troca de senha autenticada, desativacao e alteracao de e-mail limpam o token pendente.

### Integracao (MockMvc + MongoDB embarcado (dev/test) e Testcontainers (2 testes de integracao))
- E-mail ativo: forgot retorna 204, cria hash e expiracao; reset com o token retorna 204, troca a senha, incrementa `tokenVersion` e limpa o token.
- Login com a senha antiga falha; login com a nova funciona.
- E-mail inexistente: 204, nenhum usuario ou token criado.
- Usuario inativo: 204, nenhum token criado; reset com token de usuario inativo retorna 400.
- Token invalido, expirado e reutilizado: 400 `INVALID_OR_EXPIRED_TOKEN`.
- Nova senha com 7 caracteres: 400 `VALIDATION_ERROR` e o token continua valido.
- Reset nao devolve tokens e nao autentica a sessao.
- Resposta do forgot nao contem token, hash nem indicacao de existencia.
- Rate limit: a sexta solicitacao para o mesmo `email + IP` retorna 429 com `Retry-After`; e-mail inexistente tambem conta; a 21a solicitacao do mesmo IP, com e-mails diferentes, retorna 429.
- Limpar o rate limiter entre testes para isolar cenarios.
- `delivery=none`: 503 e nenhum token gravado.
- Auditoria: eventos `PASSWORD_RESET_REQUESTED` e `PASSWORD_RESET_COMPLETED` com alvo, IP e ator `system`, sem token ou hash nos detalhes.
- Demo: forgot e reset retornam 403 `READ_ONLY_DEMO`; login, refresh e logout continuam funcionando.
- Swagger: `/v3/api-docs` contem as duas rotas e os exemplos de 400, 403, 429 e 503.

### CI
- `mvn -B verify` verde com os testes de MongoDB embarcado (dev/test) e Testcontainers (2 testes de integracao) no GitHub Actions.

## Criterios de aceite
- [x] `POST /auth/forgot-password` responde 204 para e-mail ativo, inexistente e inativo, sem revelar qual caso ocorreu.
- [x] Token tem 256 bits, e armazenado apenas como hash SHA-256 com validade de 15 min e indice unico esparso.
- [x] Nova solicitacao invalida o token pendente anterior.
- [x] `POST /auth/reset-password` com token valido responde 204, troca a senha, incrementa `tokenVersion` e limpa o token.
- [x] Token desconhecido, expirado, reutilizado ou de usuario inativo responde 400 `INVALID_OR_EXPIRED_TOKEN`.
- [x] Senha invalida responde 400 `VALIDATION_ERROR` sem consumir o token.
- [x] Troca de senha autenticada, desativacao e alteracao de e-mail limpam o token pendente.
- [x] Rate limit 5/h por `email + IP` e 20/h por IP responde 429 com `Retry-After`, inclusive para e-mail inexistente, com mensagem neutra.
- [x] Perfil demo responde 403 `READ_ONLY_DEMO` nos dois endpoints, sem afetar login, refresh e logout.
- [x] `delivery=none` responde 503 e nao grava token; `delivery=log` so registra o token em dev/test e nunca na resposta HTTP.
- [x] Auditoria registra solicitacao e conclusao com alvo, IP e ator `system`, sem token ou hash.
- [x] Swagger documenta as duas rotas e os status 204, 400, 403, 429 e 503 com exemplos reais, sem token no schema de resposta.
- [x] Testes unitarios e de integracao com MongoDB embarcado (dev/test) e Testcontainers (2 testes de integracao) passam localmente e no CI.
- [x] README explica como obter o token no log em dev e por que a demo retorna 403.

## Limitacoes e riscos
- Rate limit em memoria por padrao: sem Redis, cada replica tem sua propria janela. Com Redis habilitado, os contadores sao compartilhados.
- Access tokens continuam validos por ate 1 h apos o reset. Revogacao imediata exigiria checar `tokenVersion` em cada access token, o que e uma mudanca separada.
- O canal `log` grava o token bruto. Ele existe apenas em dev/test e nunca deve ser ligado em prod. Logs de dev precisam de controle de acesso.
- Sem e-mail real, a feature nao atende usuarios finais em prod; o 503 e proposital e evita prometer o que nao existe.
- O rate limit por `email + IP` evita que um atacante esgote a cota global de uma vitima, mas nao impede spam distribuido com muitos IPs. CAPTCHA fica fora de escopo.
- Nao ha mitigacao forte de timing: a resposta e identica, mas o tempo pode variar entre e-mail existente e inexistente. Para este escopo, aceitamos o risco residual.
- A auditoria nao tem politica de retencao e pode crescer indefinidamente.
- O token expirado so e limpo quando alguem tenta usa-lo ou quando a senha, o e-mail ou o status muda. Isso e aceitavel porque o campo e pequeno e o token nao vale mais.

## Decisoes de revisao
- SHA-256 em vez de BCrypt para o token: o segredo tem 256 bits, entao o custo lento do BCrypt nao agrega seguranca e atrapalha a busca por indice.
- Campos no `User` em vez de colecao separada: so existe um token pendente, o ciclo de vida e simples e a exclusao do usuario ja remove os dados. Uma colecao separada so faria sentido com historico ou TTL proprio.
- Filtro de demo estendido: as rotas ficam em `/auth`, fora do `/api/v1/**`, que o filtro atual cobre. Sem essa mudanca, a demo publica permitiria redefinir a senha do admin demo.
- `PasswordResetNotifier` com 503 quando nao ha canal: evita gerar token que ninguem recebe e deixa o ponto de extensao para SMTP.
- Rate limit conta todas as solicitacoes e usa `email + IP` mais IP: reduz enumeracao e spam sem permitir que um atacante zere a cota global de um e-mail so.
- Consumo atomico por atualizacao condicional: garante uso unico mesmo com duas requisicoes simultaneas.
- Ator `system` explicito na auditoria: o `AuditService` atual gravaria `anonymousUser` em rota publica, o que sujaria o historico.
- Nao refatorar o `LoginRateLimiter` nesta entrega: o novo limiter segue o mesmo padrao, mas conta todas as solicitacoes, e um refactor conjunto aumentaria o risco de regressao no login.
