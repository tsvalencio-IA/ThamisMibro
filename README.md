# ThamisMibro — atletIA Mibro GS Pro

Cliente leve e dedicado do **atletIA Live** para o **Mibro Watch GS Pro** da Thamis.

## O que este repositório faz

- usa o mesmo Firebase Authentication e o mesmo Realtime Database do LeRunners/atletIA;
- lê somente os treinos do usuário autenticado em `users/{uid}/workouts` e `data/{uid}/workouts`;
- interpreta a mesma estrutura `livePlan / atletiaLive / structuredWorkout`;
- executa o pacer no Android com Foreground Service, inclusive com a tela apagada;
- usa GPS do celular com filtro de precisão e suavização de pace;
- orienta `ACELERE`, `MANTENHA`, `REDUZA`, troca de bloco, pausa e conclusão;
- envia alertas Android para o Mibro Fit espelhar no GS Pro;
- fala as orientações em pt-BR no celular/fone Bluetooth;
- possui diagnóstico da ponte com o Mibro Fit;
- possui **atualização automática do próprio APK ao abrir** a partir da linha assinada v1.2.x.

## Atualização automática

O aplicativo consulta a Release oficial mais recente no GitHub quando é aberto.

Quando existe uma versão superior ele:

1. baixa o APK;
2. valida SHA-256;
3. valida o mesmo applicationId;
4. valida o mesmo certificado de assinatura;
5. instala a atualização pelo PackageInstaller do Android.

Se o Android exigir uma ação do usuário, o aplicativo abre somente a tela oficial necessária. O app instalado nunca é apagado se a validação falhar.

Detalhes técnicos: [AUTOUPDATE.md](AUTOUPDATE.md).

## Assinatura definitiva

A chave privada de assinatura **não fica no repositório público**.

O GitHub Actions usa somente o secret:

`ANDROID_SIGNING_BUNDLE`

Sem esse secret, o workflow gera apenas um APK de diagnóstico e não publica uma Release atualizável.

## Primeiro uso

1. Instalar a primeira APK definitiva v1.2.x.
2. Entrar com o mesmo login da Thamis no atletIA Live.
3. Autorizar notificações e localização.
4. No Mibro Fit, habilitar notificações do `atletIA Mibro`.
5. Usar `CORRIGIR PONTE MIBRO` se o diagnóstico indicar falta de acesso.
6. Usar `TESTAR ALERTA NO MIBRO`.
7. Selecionar o treino, preparar e iniciar.

## Segurança

- nenhum segredo Gemini/OpenAI ou service-account está no repositório;
- a atualização rejeita APK com hash, pacote ou certificado diferente;
- o aplicativo não escreve comandos BLE proprietários desconhecidos no GS Pro;
- a chave de assinatura definitiva é mantida fora do GitHub público.

Powered by thIAguinho Soluções Digitais
