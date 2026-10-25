package com.xander.files.data.transfer

import dagger.Binds
import dagger.MapKey
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@MapKey annotation class TransferTypeKey(val value: TransferType)

@Module
@InstallIn(SingletonComponent::class)
abstract class TransferModule {
    @Binds @IntoMap @TransferTypeKey(TransferType.COPY) abstract fun copy(o: CopyOperation): TransferOperation
    @Binds @IntoMap @TransferTypeKey(TransferType.MOVE) abstract fun move(o: MoveOperation): TransferOperation
    @Binds @IntoMap @TransferTypeKey(TransferType.DELETE) abstract fun delete(o: DeleteOperation): TransferOperation
    @Binds @IntoMap @TransferTypeKey(TransferType.RECYCLE) abstract fun recycle(o: RecycleOperation): TransferOperation
    @Binds @IntoMap @TransferTypeKey(TransferType.RESTORE) abstract fun restore(o: RestoreOperation): TransferOperation
    @Binds @IntoMap @TransferTypeKey(TransferType.EXTRACT) abstract fun extract(o: com.xander.files.data.archive.ExtractOperation): TransferOperation
    @Binds @IntoMap @TransferTypeKey(TransferType.COMPRESS) abstract fun compress(o: com.xander.files.data.archive.CompressOperation): TransferOperation
    @Binds @IntoMap @TransferTypeKey(TransferType.ENCRYPT) abstract fun encrypt(o: com.xander.files.data.archive.EncryptOperation): TransferOperation
    @Binds @IntoMap @TransferTypeKey(TransferType.DECRYPT) abstract fun decrypt(o: com.xander.files.data.archive.DecryptOperation): TransferOperation
}
