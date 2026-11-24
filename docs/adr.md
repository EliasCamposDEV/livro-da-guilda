# Decisoes de arquitetura (ADR)

## ADR-1: subject do JWT = id imutavel
- Decisao: o `sub` do access e do refresh token e o id do usuario, nao o e-mail.
- Motivo: reuso de e-mail apos exclusao e mudanca de e-mail nao invalidam tokens de terceiros.
- Consequencia: o filtro de seguranca consulta o usuario no banco a cada requisicao para validar existencia e papel.

## ADR-2: sessao unica por usuario
- Decisao: um novo login rotaciona `tokenVersion` e derruba refresh tokens anteriores, controlado por `app.security.single-session` (padrao `true`; `false` na demo).
- Motivo: reduz janela de abuso de tokens roubados; na demo compartilhada evita derrubar outros visitantes.

## ADR-3: auditoria fail-open
- Decisao: falhas de gravacao de auditoria sao registradas em log e metrica (`audit.write.failures`) e nao mascaram a operacao principal.
- Motivo: disponibilidade da operacao administrativa; risco aceito de perda de trilha em falha de Mongo.

## ADR-4: TTL do access token
- Decisao: access token com 1 hora, com validacao de usuario ativo/papel a cada requisicao (consulta ao banco).
- Motivo: equilibrio entre UX e revogacao imediata; refresh rotativo de 7 dias.

## ADR-5: SpotBugs Medium com excecoes explicitas
- Decisao: `mvn verify` falha em qualquer achado Medium; excecoes ficam em `config/spotbugs-exclude.xml`.
- Motivo: copiar singletons injetados pelo Spring quebraria o container, e copiar o `User` do `AppUserDetails` perderia identidade e versao do documento. A validacao fail-fast do `JwtService` e intencional e a classe nao tem finalizer.
- Consequencia: DTOs com mapas e listas usam copias defensivas de verdade; apenas esses casos de framework sao suprimidos, com justificativa no arquivo de filtro.