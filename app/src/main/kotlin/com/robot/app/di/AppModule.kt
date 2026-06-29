package com.robot.app.di

import android.content.Context
import com.robot.app.OverlayLogger
import com.robot.net.NetConfig
import com.robot.net.VerticalSafetyMonitor
import com.robot.net.WebSocketEsp32Client
import com.robot.slam.ArSessionManager
import com.robot.tsdf.TsdfVolume
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
object AppModule {

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideArSessionManager(@ApplicationContext ctx: Context): ArSessionManager =
        ArSessionManager(ctx, onLog = OverlayLogger::log)

    @Provides
    @Singleton
    fun provideTsdfVolume(): TsdfVolume = TsdfVolume(onLog = OverlayLogger::log)

    @Provides
    @Singleton
    fun provideEsp32Client(scope: CoroutineScope): WebSocketEsp32Client =
        WebSocketEsp32Client(
            scope = scope,
            ip    = NetConfig.ESP32_IP,
            port  = NetConfig.ESP32_PORT,
        )

    @Provides
    @Singleton
    fun provideSafetyMonitor(): VerticalSafetyMonitor = VerticalSafetyMonitor()
}
