# Livro da Guilda

[![CI](https://github.com/EliasCamposDEV/livro-da-guilda/actions/workflows/ci.yml/badge.svg)](https://github.com/EliasCamposDEV/livro-da-guilda/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/github/license/EliasCamposDEV/livro-da-guilda)](LICENSE)

API REST de usuários com JWT, papéis e auditoria. Java 21, Spring Boot 4.1.3, MongoDB.

O nome vem de RPG: o livro da guilda registra quem é membro, qual papel tem e o que pode fazer. É a mesma ideia de um cadastro de usuários com `ADMIN` e `USER`.

**[Demo](https://livro-da-guilda.onrender.com/swagger-ui.html)** (somente leitura) · **[Swagger local](http://localhost:8080/swagger-ui.html)**

## Demo

| | |
|---|---|
| Swagger UI | `https://livro-da-guilda.onrender.com/swagger-ui.html` |
| Login | `demo@eliascampos.dev` / `demo-readonly-2026` |
| Dados | 7 usuários fictícios criados na primeira subida |
| Escrita | bloqueada com `403 READ_ONLY_DEMO` |
| Postman | [coleção](postman/livro-da-guilda.postman_collection.json) · [environment](postman/demo.postman_environment.json) |

Para entrar, faça `POST /auth/login`, copie o `accessToken`, clique em **Authorize** e cole só o token, sem "Bearer". `GET` funciona; `POST`, `PUT`, `PATCH` e `DELETE` respondem `403`. A raiz do site redireciona para o Swagger, então dá para mandar o link para alguém sem explicar nada.

A demo é somente leitura de propósito. Para ver o fluxo de escrita completo, rode localmente. A coleção do Postman funciona nos dois modos. O plano gratuito do Render hiberna por inatividade, então o primeiro acesso pode levar de 30 a 60 segundos.

## Rodar

```bash
mvn spring-boot:run
```

O perfil `dev` sobe um MongoDB embarcado (a primeira execução baixa o binário). A API fica em http://localhost:8080.

Com MongoDB real, na porta 27018:

```powershell
$env:SPRING_PROFILES_ACTIVE="prod"
$env:MONGODB_URI="mongodb://localhost:27018/usuarios"
mvn spring-boot:run
```

`MONGODB_URI` é obrigatória em `prod`; sem ela, a aplicação não sobe. Em produção, use um usuário MongoDB com privilégios mínimos, leitura e escrita na base da aplicação, nunca o root. Precisa de JDK 21: o `mvn` falha se o `JAVA_HOME` apontar para outra versão.

O projeto não lê `.env`. O Spring Boot só enxerga variáveis do ambiente ou do shell. O `.env.example` serve de referência, e o Docker Compose usa um `.env` na raiz para substituir `${...}` dentro do `compose`.

## Docker

```bash
docker compose up --build
```

API em http://127.0.0.1:8081; o MongoDB fica só na rede interna do Compose. Para inspecionar o banco: `docker compose exec mongo mongosh usuarios`.

Na primeira subida, com o banco vazio, defina `APP_BOOTSTRAP_ADMIN_EMAIL` e `APP_BOOTSTRAP_ADMIN_PASSWORD` no ambiente (ou `BOOTSTRAP_ADMIN_EMAIL` e `BOOTSTRAP_ADMIN_PASSWORD` no `.env` do Compose) para criar o primeiro `ADMIN`. Sem isso não há usuário para logar.

Os exemplos de `curl` abaixo usam a porta `8080`, do perfil `dev`. Com Docker, troque por `8081`.

## Autenticação

`/api/v1/users/**` exige o papel `ADMIN`. Login, Swagger e health check são públicos.

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@email.com","password":"admin12345"}'
```

A resposta traz `accessToken` (1 hora) e `refreshToken` (7 dias). Use o access token nas demais chamadas:

```bash
curl http://localhost:8080/api/v1/users -H "Authorization: Bearer <accessToken>"
```

Quando o access token expirar, troque o refresh por um par novo:

```bash
curl -X POST http://localhost:8080/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<refreshToken>"}'
```

O refresh é rotativo: cada uso devolve um par novo e invalida o anterior. Só o refresh mais recente de cada usuário vale; um novo login ou refresh derruba os anteriores. O access token já emitido continua válido até expirar.

O refresh devolve `401` com `code: INVALID_REFRESH_TOKEN` se estiver expirado ou adulterado, se for um access token, se já tiver sido usado, se o usuário foi desativado ou se outro login invalidou aquela sessão. Um refresh token não é aceito como access token nas rotas protegidas.

Para encerrar a sessão, use o access token em `/auth/logout` (204). O refresh emitido deixa de valer:

```bash
curl -X POST http://localhost:8080/auth/logout -H "Authorization: Bearer <accessToken>"
```

Trocar a própria senha exige a senha atual e também derruba os refresh tokens anteriores:

```bash
curl -X PUT http://localhost:8080/api/v1/me/password \
  -H "Authorization: Bearer <accessToken>" \
  -H "Content-Type: application/json" \
  -d '{"currentPassword":"admin12345","newPassword":"novaSenha123"}'
```

Senha atual errada devolve `400` com `code: INVALID_PASSWORD`.

- O login usa os usuários do próprio banco, com senha em hash BCrypt. Usuário com `active: false` não autentica.
- O papel vem do campo `role`: token de `USER` recebe `403` em `/api/v1/users/**`.
- Em `dev`, um admin é criado na primeira subida: `admin@email.com` / `admin12345`.
- Em `prod`, defina `APP_BOOTSTRAP_ADMIN_EMAIL` e `APP_BOOTSTRAP_ADMIN_PASSWORD` para criar o primeiro `ADMIN` em banco vazio.
- O perfil `demo` cria usuários fictícios e recusa escrita em `/api/v1/**` com `403 READ_ONLY_DEMO`.
- `JWT_SECRET`, com no mínimo 32 caracteres e HS256, é obrigatório em `prod`; sem ele, a aplicação não sobe.
- `APP_SINGLE_SESSION` controla se login e refresh rotacionam o `tokenVersion` e derrubam os refresh anteriores. O padrão é `true`; na demo, `false`.
- `/auth/login` bloqueia após 5 tentativas erradas por e-mail e IP em 15 minutos (`429 TOO_MANY_ATTEMPTS`), em memória e por instância.
- CORS é desligado por padrão. Libere origens com `CORS_ALLOWED_ORIGINS`, separadas por vírgula.
- Deploy, backup e rotação de segredo: [docs/operacao.md](docs/operacao.md).

## Recuperação de senha

Fluxo público, sem autenticação, para quem esqueceu a senha:

```bash
curl -X POST http://localhost:8080/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email":"maria.silva@email.com"}'
```

A resposta é sempre `204 No Content`, mesmo para e-mail inexistente ou inativo, para não revelar cadastros. O token de 256 bits é entregue pelo canal configurado: em `dev` e `test` (`delivery: log`), o token é escrito no log com o marcador `PASSWORD_RESET_TOKEN`; em `prod` (`delivery: none`), o endpoint responde `503 PASSWORD_RESET_UNAVAILABLE` e não cria token.

Com o token, redefina a senha:

```bash
curl -X POST http://localhost:8080/auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{"token":"<token>","newPassword":"novaSenha123"}'
```

O token é de uso único e vale 15 minutos. O reset troca a senha, incrementa `tokenVersion` (invalida os refresh tokens anteriores) e limpa o token pendente. Access tokens já emitidos continuam válidos até expirar. Na demo pública, os dois endpoints retornam `403 READ_ONLY_DEMO`.

## Endpoints

Base: `/api/v1/users`

| Método | Rota | Descrição |
|---|---|---|
| POST | `/api/v1/users` | Cria usuário |
| GET | `/api/v1/users` | Lista com paginação e filtros |
| GET | `/api/v1/users/{id}` | Busca por ID |
| PUT | `/api/v1/users/{id}` | Atualiza |
| PATCH | `/api/v1/users/{id}/status` | Ativa ou desativa |
| DELETE | `/api/v1/users/{id}` | Remove |
| PUT | `/api/v1/me/password` | Troca a senha do usuário autenticado |
| GET | `/api/v1/audit` | Lista eventos de auditoria (ADMIN) |
| POST | `/auth/forgot-password` | Solicita token de redefinição (público) |
| POST | `/auth/reset-password` | Redefine a senha com o token (público) |
| POST | `/auth/logout` | Invalida os refresh tokens do usuário autenticado |

Parâmetros de listagem: `page`, `size` (máximo 50), `sort`, `search`, `role` (USER/ADMIN) e `active` (true/false). A busca é por prefixo em nome e e-mail normalizados, atendida por índice. O `sort` aceita apenas `name`, `email`, `role`, `active`, `createdAt` e `updatedAt`, todos indexados.

`GET /api/v1/audit` usa paginação por cursor: `cursor` (opaco, da resposta anterior) e `limit` (padrão 20, máximo 50). A resposta traz `content` e `nextCursor`, sem contagem total. Ordem fixa: `timestamp` e `_id` decrescentes.

Exemplo de criação:

```bash
curl -X POST http://localhost:8080/api/v1/users \
  -H "Authorization: Bearer <accessToken>" \
  -H "Content-Type: application/json" \
  -d '{"name":"Maria Silva","email":"maria.silva@email.com","role":"USER","password":"senha12345"}'
```

Erros retornam `application/problem+json` com `code`: `VALIDATION_ERROR`, `INVALID_REQUEST`, `RESOURCE_NOT_FOUND`, `EMAIL_ALREADY_EXISTS`, `INVALID_CREDENTIALS`, `ACCESS_DENIED`, `UNAUTHORIZED`, `CONFLICT`, `INVALID_REFRESH_TOKEN`, `INVALID_PASSWORD`, `INVALID_OR_EXPIRED_TOKEN`, `TOO_MANY_ATTEMPTS`, `PASSWORD_RESET_UNAVAILABLE`, `READ_ONLY_DEMO` e `INTERNAL_ERROR`.

## Testes

```bash
mvn test
```

Os testes cobrem CRUD, paginação, busca e filtros, validação, erros 404 e 405, autenticação e autorização, emissão e rotação de refresh, recusa de token do tipo errado ou já rotacionado, troca de senha, logout, bloqueio por excesso de tentativas, recuperação de senha e o modo demo.

`mvn verify` roda o SpotBugs com limite `Medium` e falha em qualquer achado. O JaCoCo exige pelo menos 80% de cobertura de instruções; as exceções documentadas ficam em `config/spotbugs-exclude.xml` (ver ADR-5). O CI publica o relatório de cobertura e roda CodeQL, varredura de dependências e Trivy na imagem Docker.

## Documentação

- [docs/demo.md](docs/demo.md): demo pública, credenciais e como rodar localmente.
- [docs/deploy-render.md](docs/deploy-render.md): deploy no Render com MongoDB Atlas.
- [docs/operacao.md](docs/operacao.md): variáveis, backup, rotação de segredo e limites.
- [specs/](specs/): specs numeradas (001 recuperação de senha, 002 autenticação/JWT, 003 API de usuários, 004 auditoria, 005 demo/deploy).
- [postman/](postman/): coleção e environment para Postman/Insomnia.
