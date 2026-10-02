package br.com.thiaguinhosolucoes.thamismibro;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public class UpdateInstallReceiver extends BroadcastReceiver {
    public static final String ACTION_INSTALL_STATUS =
            "br.com.thiaguinhosolucoes.thamismibro.UPDATE_INSTALL_STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) return;

        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
        );

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            AutoUpdateManager.onInstallSuccess(context);
            Toast.makeText(context, "atletIA Mibro atualizado.", Toast.LENGTH_SHORT).show();
            return;
        }

        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (msg == null || msg.trim().isEmpty()) msg = "Atualização não concluída";
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();

        // Mantém o app atual intacto. A próxima abertura verifica novamente.
        if (context instanceof Activity) {
            // Não esperado para BroadcastReceiver, apenas evita cast acidental futuro.
        }
    }
}
