# Atualização automática — ThamisMibro

## Estado definitivo

A partir da linha **v1.2.x** o aplicativo possui atualização própria ao abrir.

Fluxo:

1. o APK consulta a Release mais recente de `tsvalencio-IA/ThamisMibro`;
2. compara o `versionCode` instalado com o publicado;
3. baixa o APK mais novo;
4. confere o SHA-256 publicado;
5. confere `applicationId = br.com.thiaguinhosolucoes.thamismibro`;
6. confere se o certificado de assinatura é exatamente o mesmo do app instalado;
7. usa o `PackageInstaller` do Android para atualizar o próprio aplicativo;
8. se o Android exigir confirmação, abre somente a tela oficial do sistema.

## Regra de segurança

A chave privada de assinatura **NÃO fica no repositório**.

O workflow lê apenas o secret:

`ANDROID_SIGNING_BUNDLE`

Sem esse secret, o GitHub Actions gera apenas um APK de diagnóstico temporário e **não publica Release atualizável**.

## Configuração única do GitHub

No repositório:

`Settings → Secrets and variables → Actions → New repository secret`

Nome:

`ANDROID_SIGNING_BUNDLE`

Valor:

copiar a linha completa do arquivo privado entregue ao proprietário:

`THAMISMIBRO_ANDROID_SIGNING_SECRET.txt`

Depois executar:

`Actions → Build APK • Thamis Mibro → Run workflow`

## Migração da v1.1.x

As versões v1.1.x foram builds de diagnóstico assinados pela chave debug temporária do runner do GitHub Actions.

Como runners diferentes geraram certificados diferentes, elas não formam uma cadeia de atualização Android confiável.

Portanto existe **uma única reinstalação obrigatória** para migrar para a assinatura definitiva v1.2.x:

1. desinstalar a v1.1.x;
2. instalar a primeira v1.2.x definitiva;
3. autorizar “Instalar apps desconhecidos” para o atletIA Mibro quando o Android pedir pela primeira vez.

Depois disso, novas versões v1.2.x+ usam o mesmo certificado e o atualizador passa a funcionar pelo próprio aplicativo.

## Versionamento

Cada GitHub Actions recebe:

`versionCode = 200000 + github.run_number`

e

`versionName = 1.2.<github.run_number>`

Assim todo APK novo possui versionCode maior que o anterior.

## Proteções do atualizador

Uma atualização é recusada se:

- o hash SHA-256 não for igual ao publicado;
- o package/applicationId não for o mesmo;
- o certificado de assinatura não for o mesmo;
- o APK estiver corrompido;
- a instalação for bloqueada pelo Android.

Nesses casos a versão instalada continua funcionando.

Powered by thIAguinho Soluções Digitais
