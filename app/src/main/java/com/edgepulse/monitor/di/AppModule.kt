package com.edgepulse.monitor.di

import android.content.Context
import com.edgepulse.monitor.security.ApiKeyRepository
import com.edgepulse.monitor.security.KeystoreAuditSigner
import com.edgepulse.monitor.security.SecureSettingsStore
import com.edgepulse.monitor.security.TinkKeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.edgepulse.core.cloud.MonitoringNetworkInterlock

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun keys(@ApplicationContext context: Context) = TinkKeyManager(context)
    @Provides @Singleton fun settings(@ApplicationContext context: Context, keys: TinkKeyManager) = SecureSettingsStore(context, keys)
    @Provides @Singleton fun apiKeys(settings: SecureSettingsStore) = ApiKeyRepository(settings)
    @Provides @Singleton fun auditSigner() = KeystoreAuditSigner()
    @Provides @Singleton fun networkInterlock() = MonitoringNetworkInterlock()
}
