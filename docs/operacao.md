# Operação

## Variáveis obrigatórias em prod

| Variável | Para que serve |
|---|---|
| `MONGODB_URI` | Conexão com o MongoDB. Sem ela a aplicação não sobe. |
| `JWT_SECRET` | Segredo HS256 com no mínimo 32 caracteres. Sem ele a aplicação não sobe. |
| `APP_BOOTSTRAP_ADMIN_EMAIL` | E-mail do primeiro ADMIN. Opcional, usado só em banco vazio. |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | Senha do primeiro ADMIN (mínimo 8 caracteres). |
| `CORS_ALLOWED_ORIGINS` | Origens de navegador liberadas, separadas por vírgula. Vazio desliga o CORS. |
| `PORT` | Porta HTTP (padrão 8080). |

O bootstrap de admin só roda fora dos perfis `dev`, `test` e `demo`, só quando o banco
está vazio e só se as duas variáveis estiverem preenchidas. Depois de criar o primeiro
admin, remova as variáveis do ambiente.

No `docker compose`, os dois valores já vêm preenchidos para uso local
(`admin@local.dev` / `admin12345`, sobrescreva com `BOOTSTRAP_ADMIN_EMAIL` e
`BOOTSTRAP_ADMIN_PASSWORD`). Fora do Compose, sem eles a API sobe e não há como logar
em um banco vazio, porque criar usuários exige um `ADMIN`.

## Demo pública

O perfil `demo` repete a configuração de `prod` e acrescenta:

| Variável | Para que serve |
|---|---|
| `DEMO_ADMIN_EMAIL` | E-mail do admin de demo (padrão `demo@eliascampos.dev`). |
| `DEMO_ADMIN_PASSWORD` | Senha pública do admin de demo (padrão `demo-readonly-2026`). |

Com `app.demo.read-only: true` (definido em `application-demo.yml`),
`POST`/`PUT`/`PATCH`/`DELETE` em `/api/v1/**` retornam `403 READ_ONLY_DEMO` antes de
chegar ao controller. Login, refresh, logout, Swagger e leitura seguem abertos.
Os usuários de exemplo são criados por `DemoDataSeeder` apenas com o banco vazio.
Detalhes e deploy: [demo.md](demo.md) e [deploy-render.md](deploy-render.md).

## Primeira subida

1. Suba o MongoDB e defina `MONGODB_URI`, `JWT_SECRET`, `APP_BOOTSTRAP_ADMIN_*`.
2. A aplicação cria o admin e loga o e-mail em nível `WARN`.
3. Faça login, guarde o `refreshToken` e remova `APP_BOOTSTRAP_ADMIN_*` do ambiente.
4. Crie os demais usuários por `POST /api/v1/users` (exige `ADMIN`).

## Backup

```bash
docker compose exec -T mongo mongodump --archive=/tmp/backup.gz --gzip --db usuarios
docker compose cp mongo:/tmp/backup.gz ./backup-$(date +%F).gz
```

Restauração:

```bash
docker compose cp ./backup-2026-11-18.gz mongo:/tmp/restore.gz
docker compose exec -T mongo mongorestore --archive=/tmp/restore.gz --gzip --drop
```

Os dados ficam no volume `livro-da-guilda-mongo-data`; `docker compose down` sem `-v`
preserva o volume.

## Rotação do JWT_SECRET

Trocar o segredo invalida **todos** os access e refresh tokens de uma vez (todos os
clientes precisam logar de novo, sem downtime: suba a nova versão e reinicie).
Fazer isso se houver suspeita de vazamento ou a cada ciclo de rotação definido.

## Health e observabilidade

- `GET /actuator/health` é público e não expõe detalhes em prod; use o `Liveness`/`Readiness` do orquestrador.
- O compose já tem healthcheck da API e do Mongo.
- Logs vão para stdout; colete com o runtime (Docker, systemd, etc.).

## Limites conhecidos

- **Rate limit do login**: 5 tentativas por e-mail + IP em 15 minutos.
- **Rate limit da recuperação de senha**: 5 solicitações por e-mail + IP
  e 20 por IP, em janela de 1 hora. Toda solicitação conta, inclusive
  para e-mail inexistente. Tambem ha limite de 10 solicitacoes por conta (e-mail).
- **Rate limit do refresh**: 30 tentativas por IP por minuto.
- Por padrão os contadores são locais, um por instância. Para mais de uma réplica,
  defina `APP_RATE_LIMIT_REDIS_ENABLED=true` e `APP_RATE_LIMIT_REDIS_URI=redis://host:6379`
  (opcionalmente com `:senha` e `/db`). Os três limiters passam a compartilhar os
  contadores no Redis, com `INCR` e TTL atomics. Sem essas variaveis a demo continua
  funcionando sem Redis.
- **Refresh token**: rotativo e de sessão única por usuário (ver README). Não há
  revogação por dispositivo.
- **CORS**: desligado por padrão; libere só as origens que consomem a API.
