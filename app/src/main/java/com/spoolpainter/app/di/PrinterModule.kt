package com.spoolpainter.app.di

import android.content.Context
import android.os.PowerManager
import com.spoolpainter.app.hardware.printer.KozenPrinterPort
import com.spoolpainter.app.hardware.printer.PrinterController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PrinterModule {
    @Provides
    @Singleton
    fun controller(@ApplicationContext context: Context): PrinterController {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpoolPainter:print")
        wakeLock.setReferenceCounted(false)
        return PrinterController(
            KozenPrinterPort(context),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            holdAwake = { awake ->
                // Bounded lock: a lost vendor callback must not hold the CPU indefinitely.
                if (awake) wakeLock.acquire(120_000L) else if (wakeLock.isHeld) wakeLock.release()
            },
        )
    }
}
