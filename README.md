# ThamisMibro — atletIA Mibro GS Pro

Cliente leve e dedicado do **atletIA Live** para o **Mibro Watch GS Pro** da Thamis.

## O que este repositório faz

- usa o **mesmo Firebase Authentication** e o mesmo Realtime Database do LeRunners/atletIA;
- lê somente os treinos do usuário autenticado em `users/{uid}/workouts` e `data/{uid}/workouts`;
- interpreta a mesma estrutura `livePlan / atletiaLive / structuredWorkout` já gravada pelo atletIA;
- mantém compatibilidade com treinos contínuos e intervalados que tenham duração/distância e pace explícitos;
- executa o pacer no Android com **Foreground Service**, inclusive com a tela apagada;
- usa o GPS do celular com filtro de precisão e suavização de pace;
- envia alertas `ACELERE`, `MANTENHA`, `REDUZA`, troca de bloco, pausa e conclusão como notificações Android;
- essas notificações podem ser espelhadas no **Mibro GS Pro** pelo **Mibro Fit**, desde que as notificações deste app estejam habilitadas no Mibro Fit;
- fala as orientações em pt-BR no celular/fone Bluetooth;
- inclui botão de **teste de alerta Mibro** antes da corrida.

## O que NÃO está neste repositório

Este projeto não duplica painel administrativo, Strava, nutrição, GPX, GPT/Gemini ou outras telas do LeRunners. A criação/importação do treino continua no atletIA principal. Aqui existe apenas o cliente de execução do treino para a Thamis.

Nenhuma chave privada/segredo foi copiada. O `web/config.js` contém somente a configuração pública do Firebase necessária ao SDK web.

## APK

O workflow `.github/workflows/build-apk.yml` compila automaticamente a cada alteração na `main` e também pode ser executado manualmente.

Artefato esperado:

`atletIA-Mibro-Thamis-v1.0.0.apk`

## Primeiro uso

1. Instalar o APK gerado pelo GitHub Actions.
2. Abrir o app e entrar com o **mesmo login da Thamis no atletIA Live**.
3. No Mibro Fit, habilitar notificações para **atletIA Mibro • Thamis**.
4. No app, tocar em **TESTAR ALERTA NO MIBRO**.
5. Confirmar vibração/aviso no GS Pro.
6. Selecionar o treino, preparar e iniciar.

## Segurança

O aplicativo não tenta escrever comandos BLE desconhecidos no relógio nem alterar firmware. A ponte Mibro desta versão usa o mecanismo documentado de notificações Android → Mibro Fit → relógio.

Powered by thIAguinho Soluções Digitais
