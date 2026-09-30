# JEV + Agent — validação conjunta do MVP

Data: 2026-09-30. Base local: `51be8061f0d7c0030bc62b266c9cd4915e39a455`.

## Resultado e limites

Os ajustes locais do roteiro JEV + Agent foram concluídos. O MVP funciona no
cenário de hackathon com integrações simuladas: a E02 é detectada pelo PEEK,
o contexto separa fatos de hipóteses, o agente consulta o MCP, e uma ação
aprovada precisa de verificação determinística posterior.

O operador manteve **TypeSafe / Jev**, com a chave local já configurada,
endpoint System One, modelo jev-latest, timeout de 10s e conexão de 3s.
O adaptador usa state/questions, preserva probabilidades e identifica os
textos compostos pelo PEEK. Os resultados da tabela abaixo registram o ensaio
anterior com HTTP mockado, antes da adaptação TypeSafe. A validação atual está
na seção final deste documento. Nenhum ensaio autoriza uma ocorrência E02;
não houve execução de uma aprovação humana real.

O cliente **Codex real** foi testado separadamente em sessão somente de leitura,
autenticada pela conta ChatGPT, usando apenas a ocorrência fictícia criada pelo
roteiro. Isso valida o cliente MCP, não transforma a resposta mockada do JEV
em inferência real. Nenhum commit, staging, push ou alteração remota foi feito.

## Correções

- `scripts/mcp-e02-demo.mjs` agora cria produto categoria Demo com SKU
  `SKU-E02-DEMO-E02-<uuid>` e `runId` compatível com o reset. Estoque, venda e
  contagem entram pelos adapters mock existentes, com mappings independentes
  para estoque e vendas. Antes, o namespace e a origem canônica de parte dos
  eventos impediam a análise JEV por `NON_SYNTHETIC_CONTEXT`.
- A preparação verifica autenticação e disponibilidade da ferramenta E02
  antes de criar fixtures. `prepare --require-jev` exige AVAILABLE no MCP e
  REST; erro informa o motivo seguro do fallback sem aprovar ou resolver.
  `prepare` continua permitindo o fallback determinístico.
- `docs/codex-mcp.example.toml` inclui `peek_apply_e02_reconciliation` na
  allowlist. Habilitar a ferramenta não equivale a aprovar uma ocorrência.
- A documentação descreve a inicialização conjunta com os perfis `demo,mcp`,
  o mesmo token nos processos e a configuração LLM exclusiva do backend.
- Os arquivos do frontend foram formatados. `.gitattributes` preserva LF
  também em checkouts Windows, de acordo com `.editorconfig` e Prettier.
- Os testes integram JEV, MCP, aprovação sintética de teste, verificação pelo
  motor, preservação das evidências e replay sem efeitos duplicados.

## Verificações executadas

| Verificação | Resultado |
| --- | --- |
| Backend `mvn verify` | 52 unitários + 63 integrações passaram; JAR gerado |
| `JevIntegrationIT` | 10/10 passaram, incluindo três regressões conjuntas novas |
| Frontend `npm run format:check` | Passou em todo o frontend |
| Frontend `npm run lint` | TypeScript passou |
| Frontend `npm test` | 15/15 passaram |
| Frontend `npm run test:fixtures` | 1/1 passou |
| Frontend `npm run build` | Build Next.js passou |
| `node --test scripts/mcp-e02-demo.test.mjs` | 7/7 passaram |
| Frontend `npm run test:e2e` | 3/3 passaram com API Spring Boot/PostgreSQL reais e LLM desativado |
| Roteiro `prepare --require-jev` em `demo,mcp` | MCP AVAILABLE e REST AVAILABLE; esperado 95, físico 93 |
| Smoke Chromium da tela E02 com JEV HTTP mockado | Fatos 95/100/93, hipótese, confiança e evidências visíveis; exceção aberta |
| Cliente Codex CLI 0.149.0 | Três consultas MCP concluídas; resposta separou fatos, hipóteses e ação sujeita a aprovação |
| Observação após o cliente | OPEN, `agentActions=[]`, sem prova de resolução ou reconciliação |
| Inspeção de secrets / Git | Chave fora de fontes versionáveis; `.env` ignorado; HEAD preservado, sem staging |

São **141 testes automatizados** das suítes acima, além dos smokes do roteiro,
da tela e do cliente Codex. Build e testes de navegador foram sequenciais.

No ensaio de cliente, a allowlist temporária continha somente
`peek_get_exception`, `peek_get_operational_context` e
`peek_get_exception_status`. O prompt negou explicitamente aprovação E02.
O Codex informou o saldo esperado 95, contagem 93, delta -2 e tolerância 1;
identificou a confiança 0,6 como ranking autodeclarado, sem probabilidade
calibrada; explicou a proposta e encerrou aguardando autorização humana.
O teste positivo de escrita usa aprovação explicitamente simulada apenas em
`JevIntegrationIT`; não houve chamada positiva ao MCP de uma sessão humana.

## Ambiente e ocorrências da execução

Java 23.0.1 compilando para release 21, Maven 3.9.9, Node 24.13.1,
PostgreSQL nativo 18.4 em loopback:55433, Next.js 16.3.7 e Chromium existente.
Os bancos locais isolados foram `peek_test` e `peek_review_demo`. Docker e
integrações de ERP/marketplace reais não foram necessários.

O sandbox inicialmente impediu o compilador de ler um JAR no cache Maven.
A repetição autorizada passou integralmente. O primeiro smoke da UI com servidor
de produção usou a URL de proxy gravada no build (8080), diferente do backend
do ensaio (8081); foi repetido com o servidor de desenvolvimento e configuração
coerente. Para produção, `PEEK_API_URL` deve estar definido também antes do build.

O modelo `gpt-6.1-sol` configurado no aplicativo foi recusado pelo CLI instalado
com essa autenticação. O ensaio passou usando o padrão do CLI em sessão isolada,
sem alterar a configuração do usuário. A revisão automática inicialmente
bloqueou essa repetição por possível envio de dados operacionais. Após conferir
os UUIDs de teste, o produto fictício e os três eventos mockados, a repetição
restrita a essa ocorrência foi autorizada e concluída.

Uma chave encontrada na documentação foi retirada e preservada no `.env`
local ignorado. Ela não foi usada no ensaio anterior com HTTP mockado; a validação atual da TypeSafe está abaixo. Como apareceu em texto aberto,
recomenda-se sua rotação no fornecedor. Spring Boot não lê `.env`
automaticamente: configure as variáveis no processo que inicia o backend,
sem colocar a chave no frontend, na documentação ou no chat.

Os processos criados exclusivamente para esta validação são encerrados ao
final. Os bancos fictícios e relatórios locais são preservados; o token
temporário de MCP é removido. Avisos de Flyway sobre PostgreSQL 18 e avisos de
ferramentas de teste não impediram as verificações.

## Reproduzir e configurar o fornecedor

1. Execute os gates documentados em
   [frontend_validation.md](frontend_validation.md), com banco isolado.
2. Na raiz, execute `node --test scripts/mcp-e02-demo.test.mjs`.
3. Configure endpoint, modelo compatível e chave LLM no ambiente do backend,
   seguindo [jev_contract.md](jev_contract.md). Use somente fixtures fictícias.
4. Inicie com `demo,mcp` e compartilhe o token local de MCP com o cliente,
   conforme [mcp_agent.md](mcp_agent.md).
5. Execute `node scripts/mcp-e02-demo.mjs prepare --require-jev`.
6. Investigue a ocorrência no Codex. A aprovação humana é uma nova decisão
   explícita nessa ocorrência; `watch` apenas observa a prova produzida pelo
   PEEK, e `deny` confirma que ela permanece aberta sem ação aprovada.

Sucesso com o fornecedor configurado significa transporte/schema aceitos e
análise AVAILABLE para aquela fixture. Não certifica a causa da divergência;
as conclusões continuam sendo hipóteses sustentadas pelas evidências.

## Atualização TypeSafe / Jev — 2026-09-30

A integração local foi adaptada ao protocolo oficial System One. O .env mantém
a mesma chave TypeSafe, endpoint https://api.typesafe.ai/v1/systemone, modelo
jev-latest, habilitação explícita de demo, timeout 10s e conexão 3s.
O antigo modo de schema de Chat Completions foi removido.

Jev avalia uma pergunta Choice entre hipóteses com evidência contextual.
PEEK produz os textos em português usando templates ligados às evidências.
REST preserva a distribuição, a confiança da escolha, o modelo efetivamente
retornado e explanationSource=PEEK_EVIDENCE_TEMPLATES. A UI e a projeção MCP
identificam TYPESAFE_CHOICE_PROBABILITY; o valor não prova a causa.

| Verificação atual | Resultado |
| --- | --- |
| Backend mvn verify | 57 unitários + 63 integrações; zero falhas/erros |
| Empacotamento final e regressões do cliente/contrato | JAR atualizado; 22 testes repetidos passaram |
| Frontend format:check e lint | Passaram |
| Frontend Vitest | 16/16 passaram, incluindo TypeSafe/probabilidade/origem dos textos |
| Fixture API | 1/1 passou |
| Driver E02 | 7/7 passaram |
| Build Next.js | Passou |
| Playwright com Spring Boot/PostgreSQL reais | 3/3 passaram; IA desativada nessa suíte |
| prepare --require-jev com TypeSafe real | REST AVAILABLE, MCP AVAILABLE, sem fallback |
| Consulta REST adicional | TYPESAFE, modelo jev-1.13.0, origem dos textos identificada |
| Observação MCP sem escrita | OPEN, zero agentActions, sem reconciliationEventId |

São **147 testes automatizados distintos** nas suítes completas, além das
22 repetições focadas após o ajuste final de comparação numérica e do ensaio
manual com o fornecedor real. Nenhum teste automatizado depende da chave real.

A ocorrência fictícia foi `8919145b-28f8-4e1e-ab53-43c5a3ea0898`, com esperado
95, físico 93, delta -2 e tolerância 1. TypeSafe retornou **jev-1.13.0** e
priorizou **UNEXPLAINED_DIVERGENCE**, com probabilidade 0,96 naquela consulta.
A hipótese permaneceu separada dos fatos. A chave foi utilizada somente pelo
backend para fixtures sanitizadas; não foi enviada à OpenAI.

Relatórios locais ignorados pelo Git:
`backend/target/typesafe-maven-verify.log`,
`typesafe-package.log`, `typesafe-live-demo.json`,
`typesafe-live-rest-context.json` e `typesafe-live-observation.json`.
Esses resultados validam autenticação, transporte, contrato e o fluxo de consulta
para a fixture; não demonstram uma causa comprovada nem uma aprovação humana.

Os testes PostgreSQL verificam aprovação explicitamente sintética, ação
PENDING_VERIFICATION, avaliação posterior, prova VERIFIED e idempotência.
O cliente Codex real foi validado no ensaio anterior descrito acima; nesta
atualização a validação real da TypeSafe usou REST e as consultas MCP.
Nenhuma ocorrência real recebeu aprovação ou chamada de escrita do agente.

O primeiro build foi bloqueado pelo sandbox ao acessar o cache Next.js em
AppData; a repetição com a permissão de desenvolvimento apropriada passou.
Build e testes de navegador foram sequenciais. Os serviços temporários de
validação são encerrados ao final e o token temporário MCP é removido.
A configuração permanente do usuário/Codex não foi alterada.

HEAD permanece `51be8061f0d7c0030bc62b266c9cd4915e39a455`, staging vazio,
.env ignorado e nenhum secret encontrado nas fontes versionáveis.
Não houve commit, push nem alteração no GitHub.
