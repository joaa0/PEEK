# JEV / AI: contrato 1.0

Jev interpreta uma E02 já persistida. `EvaluationService` e `StockCalculator`
detectam a divergência e registram as evidências. Uma avaliação do modelo não
é evento, alteração de estoque, aprovação humana ou prova de reconciliação.

## Entrada e evidências

Os schemas executáveis estão em `backend/src/main/resources/intelligence/`.
`JevInputFactory` projeta somente a detecção original:

- estados esperado e físico, regra, versão e tolerância aplicada;
- `facts`: contagem, baseline/checkpoint e movimentos observados;
- `calculations`: saldo esperado e delta calculados pelo PEEK;
- até 20 eventos do histórico original, com `historyTruncated`, e até 64 evidências.

A ordem do histórico segue occurredAt, receivedAt e id. Os tempos transmitidos
são relativos à contagem. Não são enviados SKU, nomes, UUIDs, pedidos,
documentos, referências externas, timestamps absolutos, notas, metadados,
payloads de origem nem texto livre das evidências. Fontes e evidências usam
aliases; o mapa alias → UUID permanece no backend. Eventos posteriores não
substituem o estado da detecção.

Somente `SYNTHETIC_DEMO` é permitido. A integração exige perfil `demo`,
enabled, synthetic-demo, produto categoria Demo no namespace de reset
`CAM|SKU-E02|SKU-E04` + `DEMO-*|QA-*`, e eventos provenientes dos mock
adapters compatíveis. Essas marcações identificam fixtures; não certificam
anonimização. Nunca rotule dados reais como demo.

## TypeSafe / Jev

O fornecedor escolhido é **TypeSafe AI**, com modelo **jev-latest**.
O adaptador `HttpLlmClient`, atrás do SPI `LlmClient`, usa
`POST https://api.typesafe.ai/v1/systemone`, Bearer token, e os campos
`model`, `state` e `questions`. Não usa Chat Completions, messages,
response_format ou um prompt para gerar JSON.

Referências oficiais:
[API System One](https://docs.typesafe.ai/api),
[modelos Jev](https://docs.typesafe.ai/models) e
[primitivas](https://docs.typesafe.ai/introduction).

`TypeSafeEvaluation` envia uma pergunta `choice` chamada `hypothesis`.
O conjunto de opções contém recontagem e causa indeterminada, mais conferência
de venda, recebimento ou ajuste somente quando existe o respectivo evento.
Os critérios esclarecem que uma venda/recebimento/ajuste isolado não comprova
uma lacuna. Na falta de sinais que distingam causas, existe a opção
`UNEXPLAINED_DIVERGENCE`. Os códigos são hipóteses de investigação, não novas
famílias de exceção. A detecção objetiva não é delegada ao modelo.

Jev retorna uma escolha, distribuição de probabilidades e confiança da escolha.
**Jev não gera o texto explicativo.** O PEEK compõe descrição, justificativa,
impacto e recomendação em português com templates ligados às evidências;
isso é identificado por `explanationSource=PEEK_EVIDENCE_TEMPLATES`.

## Saída estruturada e confiança

O contrato 1.0 mantém resumo, hipótese principal, até duas alternativas,
impacto, recomendação e `nature=HYPOTHESIS_NOT_FACT`. Cada hipótese cita
evidências existentes. Os números da TypeSafe são preservados:

- `confidence.meaning=TYPESAFE_CHOICE_PROBABILITY`: value é a probabilidade
  atribuída àquela hipótese entre as opções enviadas;
- `evaluation.provider=TYPESAFE` e `evaluation.model`: fornecedor e versão
  efetivamente retornada, inclusive quando a configuração usa um alias;
- `evaluation.probabilities`: distribuição completa das opções avaliadas;
- `evaluation.confidence`: confiança da escolha retornada pela TypeSafe,
  distinta da probabilidade da hipótese;
- `evaluation.explanationSource`: origem dos textos do PEEK.

A UI identifica o modelo, a origem dos textos e que a probabilidade não comprova
a causa. O valor é condicionado às opções propostas; não é uma validação
estatística da operação real pelo PEEK. O significado legado
`MODEL_SELF_REPORTED_RANKING` continua aceito para fixtures/projeções antigas,
mas não é emitido pelo adaptador TypeSafe. A extensão `evaluation` é opcional
no contrato para manter compatibilidade; é obrigatória com probabilidades TypeSafe.

A validação rejeita respostas sem answers/choice, tipo/modelo incompatível,
JSON duplicado ou truncado, valores fora de 0–1, opções ausentes/desconhecidas,
soma distante de 1 (tolerância numérica 0,000001), escolha fora do conjunto ou
abaixo da maior probabilidade. Exige usage com contagens de tokens não negativas.
O schema local e a validação contextual permanecem obrigatórios: códigos,
referências existentes, suporte por tipo de evento e alternativas ordenadas.
Não há troca silenciosa de resposta inválida por uma escolha fabricada.

## Configuração e fallback

As variáveis pertencem ao processo backend; Spring Boot não carrega .env
automaticamente. O arquivo local é ignorado pelo Git. Nunca use NEXT_PUBLIC
para endpoint/chave e nunca coloque a credencial no frontend.

| Variável | Padrão | Uso |
| --- | --- | --- |
| PEEK_LLM_ENABLED | false | Habilita a interpretação externa |
| PEEK_LLM_SYNTHETIC_DEMO | false | Opt-in explícito para fixtures fictícias |
| PEEK_LLM_ENDPOINT | vazio | URL completa de System One; TypeSafe HTTPS ou mock HTTP loopback |
| PEEK_LLM_MODEL | vazio | Modelo Jev, selecionado pelo operador |
| PEEK_LLM_API_KEY | vazio | Chave TypeSafe; obrigatória fora de loopback |
| PEEK_LLM_TIMEOUT | 3s | Prazo total incluindo corpo; 100ms–10s |
| PEEK_LLM_CONNECT_TIMEOUT | 1s | Conexão; 100ms até o prazo total |

`PEEK_LLM_SCHEMA_MODE` foi removido: não pertence ao protocolo TypeSafe.
Configurações externas aceitam somente o host api.typesafe.ai e o caminho
/v1/systemone, sem userinfo, query, fragment ou porta alternativa. Isso impede
que uma chave TypeSafe seja enviada acidentalmente a outro fornecedor.
HTTP loopback é reservado aos mocks locais.

O transporte não segue redirects, não faz retry automático, não transmite
ferramentas e limita a resposta a 32 KiB. A saída projetada é limitada a 16 KiB.
HTTP não-2xx, timeout e resposta inválida causam fallback com motivo seguro,
sem registrar token, payload ou corpo do fornecedor. Não se envia store=false:
essa opção é de outro protocolo; o tratamento de dados é o da TypeSafe.

`JevService` enriquece apenas a consulta, sem gravações. REST acrescenta
`jev` ao contexto e remapeia aliases para UUIDs das evidências. MCP reutiliza
a análise, preserva fatos separados e identifica o significado da probabilidade.
Lista, detalhe simples, detecção, aprovação e verificação não chamam o modelo.
Cada consulta elegível pode fazer uma nova chamada; não há cache ou persistência
da interpretação como verdade operacional.

Falhas mantêm E02 e a recomendação estática. REST retorna FALLBACK com
DISABLED, NON_SYNTHETIC_CONTEXT, INVALID_CONFIGURATION, INVALID_CONTEXT,
TIMEOUT, API_FAILURE ou INVALID_RESPONSE; MCP retorna UNAVAILABLE e nenhuma
confiança inventada. A reconciliação continua independente da TypeSafe.

## Demo local com o Agent

A chave TypeSafe já configurada foi preservada no .env local. Configuração:

```dotenv
PEEK_LLM_ENDPOINT=https://api.typesafe.ai/v1/systemone
PEEK_LLM_MODEL=jev-latest
PEEK_LLM_ENABLED=true
PEEK_LLM_SYNTHETIC_DEMO=true
PEEK_LLM_TIMEOUT=10s
PEEK_LLM_CONNECT_TIMEOUT=3s
```

Exporte as atribuições simples do arquivo para o processo em PowerShell,
sem interpretar seu conteúdo como código:

```powershell
Get-Content -LiteralPath .env | ForEach-Object {
  if ($_ -match '^(PEEK_LLM_[A-Z_]+)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
  }
}
# Defina o banco local e o mesmo PEEK_MCP_TOKEN conforme mcp_agent.md.
mvn.cmd -f backend/pom.xml spring-boot:run '-Dspring-boot.run.profiles=demo,mcp'
```

Com backend/MCP ativos:

```bash
node scripts/mcp-e02-demo.mjs prepare --require-jev
```

O driver cria somente fixtures, exige AVAILABLE no MCP e REST e termina com
erro se houver fallback, preservando E02 aberta sem ação do agente.
O roteiro humano e os requisitos de aprovação estão em
[mcp_agent.md](mcp_agent.md). Uma aprovação simulada nos testes nunca autoriza
uma ocorrência real. Apenas a avaliação determinística posterior pode produzir
VERIFIED. E03/E04 continuam somente leitura no MCP.

As suítes usam HTTP TypeSafe mockado, PostgreSQL e MCP; a chamada ao fornecedor
real é uma validação manual separada, com fixtures. Consulte
[jev_agent_validation.md](jev_agent_validation.md) para resultados e limites.
