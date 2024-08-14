package org.thoughtcrime.securesms.absbackup.backupables

import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.absbackup.AndroidBackupItem
import org.thoughtcrime.securesms.absbackup.protos.SvrAuthToken
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import java.io.IOException

/**
 * This backs up the not-secret KBS Auth tokens, which can be combined with a PIN to prove ownership of a phone number in order to complete the registration process.
 */
object SvrAuthTokens : AndroidBackupItem {
  private const val TAG = "KbsAuthTokens"

  override fun getKey(): String {
    return TAG
  }

  override fun getDataForBackup(): ByteArray {
    val proto = SvrAuthToken(svr2Tokens = GenZappStore.svr.svr2AuthTokens)
    return proto.encode()
  }

  override fun restoreData(data: ByteArray) {
    if (GenZappStore.svr.svr2AuthTokens.isNotEmpty()) {
      return
    }

    try {
      val proto = SvrAuthToken.ADAPTER.decode(data)

      GenZappStore.svr.putSvr2AuthTokens(proto.svr2Tokens)
    } catch (e: IOException) {
      Log.w(TAG, "Cannot restore KbsAuthToken from backup service.")
    }
  }
}
