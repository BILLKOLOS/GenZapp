package org.thoughtcrime.securesms.database

import android.database.Cursor
import org.GenZapp.core.util.Base64
import org.GenZapp.core.util.Hex
import org.GenZapp.core.util.requireString
import org.GenZapp.libGenZapp.zkgroup.profiles.ExpiringProfileKeyCredential
import org.GenZapp.spinner.ColumnTransformer
import org.GenZapp.spinner.DefaultColumnTransformer
import org.thoughtcrime.securesms.database.model.databaseprotos.ExpiringProfileKeyCredentialColumnData
import org.thoughtcrime.securesms.util.toLocalDateTime
import java.security.MessageDigest

object ProfileKeyCredentialTransformer : ColumnTransformer {
  override fun matches(tableName: String?, columnName: String): Boolean {
    return columnName == RecipientTable.EXPIRING_PROFILE_KEY_CREDENTIAL && (tableName == null || tableName == RecipientTable.TABLE_NAME)
  }

  override fun transform(tableName: String?, columnName: String, cursor: Cursor): String? {
    val columnDataString = cursor.requireString(RecipientTable.EXPIRING_PROFILE_KEY_CREDENTIAL) ?: return DefaultColumnTransformer.transform(tableName, columnName, cursor)
    val columnDataBytes = Base64.decode(columnDataString)
    val columnData = ExpiringProfileKeyCredentialColumnData.ADAPTER.decode(columnDataBytes)
    val credential = ExpiringProfileKeyCredential(columnData.expiringProfileKeyCredential.toByteArray())

    return """
      Credential: ${Hex.toStringCondensed(MessageDigest.getInstance("SHA-256").digest(credential.serialize()))}
      Expires:    ${credential.expirationTime.toLocalDateTime()}
      
      Matching Profile Key: 
        ${Base64.encodeWithPadding(columnData.profileKey.toByteArray())}
    """.trimIndent().replace("\n", "<br>")
  }
}