# Arquitetura e testes — ThamisMibro v1.0.0

## Fluxo

`atletIA principal → Firebase → app ThamisMibro → GPS Android → motor de pace → notificação Android → Mibro Fit → Mibro GS Pro`

## Dados lidos

O cliente só consulta o UID autenticado. Não existe busca por código da atletIA nem leitura global de `/users`.

## Chaves

Somente `firebaseConfig` público foi reutilizado. A chave Gemini que existia em versões antigas do `config.js` do LeRunners **não foi copiada**.

## Testes estáticos exigidos

- `node --check web/app.js`
- `node --check web/config.js`
- `node --check web/sw.js`
- XML Android bem-formado
- build Android via GitHub Actions

## Testes físicos obrigatórios

1. login da Thamis no APK;
2. leitura do treino correto do Firebase;
3. botão Testar alerta;
4. alerta aparecer no GS Pro;
5. corrida curta de 10–15 min;
6. validar ACELERE/MANTENHA/REDUZA;
7. apagar a tela do celular e confirmar continuidade;
8. pausar/continuar e validar cronômetro;
9. encerrar e confirmar fim dos alertas.

## Limitação conhecida

Esta versão não lê frequência cardíaca diretamente do GS Pro e não injeta um app no firmware do relógio. Não afirmar o contrário sem nova evidência técnica/teste.
