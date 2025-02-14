package com.lagradost.cloudstream3.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import com.lagradost.cloudstream3.mvvm.logError
import java.io.InputStream

const val INSTALL_ACTION = "ApkInstaller.INSTALL_ACTION"


class ApkInstaller(private val service: PackageInstallerService) {
    private val packageInstaller = service.packageManager.packageInstaller

    enum class InstallProgressStatus {
        Preparing,
        Downloading,
        Installing,
        Failed,
    }

    private val installActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
            )) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val userAction = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    userAction?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(userAction)
                }
            }
        }
    }

    fun installApk(
        context: Context,
        inputStream: InputStream,
        size: Long,
        installProgress: (bytesRead: Int) -> Unit,
        installProgressStatus: (InstallProgressStatus) -> Unit
    ) {
        installProgressStatus.invoke(InstallProgressStatus.Preparing)
        var activeSession: Int? = null

        try {
            val installParams =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                installParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            activeSession = packageInstaller.createSession(installParams)
            installParams.setSize(size)

            val session = packageInstaller.openSession(activeSession)
            installProgressStatus.invoke(InstallProgressStatus.Downloading)

            session.openWrite(context.packageName, 0, size)
                .use { outputStream ->
                    val buffer = ByteArray(1024)
                    var bytesRead = inputStream.read(buffer)

                    while (bytesRead >= 0) {
                        outputStream.write(buffer, 0, bytesRead)
                        bytesRead = inputStream.read(buffer)
                        installProgress.invoke(bytesRead)
                    }

                    inputStream.close()
                }

            installProgressStatus.invoke(InstallProgressStatus.Installing)

            val intentSender = PendingIntent.getBroadcast(
                service,
                activeSession,
                Intent(INSTALL_ACTION),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
            ).intentSender

            session.commit(intentSender)
        } catch (e: Exception) {
            logError(e)

            service.unregisterReceiver(installActionReceiver)
            installProgressStatus.invoke(InstallProgressStatus.Failed)

            activeSession?.let { sessionId ->
                packageInstaller.abandonSession(sessionId)
            }
        }
    }

    init {
        service.registerReceiver(installActionReceiver, IntentFilter(INSTALL_ACTION))
        service.receivers.add(installActionReceiver)
    }
}

