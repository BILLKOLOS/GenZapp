package org.thoughtcrime.securesms.registration

import org.whispersystems.GenZappservice.api.account.PreKeyCollection
import org.whispersystems.GenZappservice.api.kbs.MasterKey
import org.whispersystems.GenZappservice.internal.ServiceResponse
import org.whispersystems.GenZappservice.internal.push.VerifyAccountResponse

data class VerifyResponse(
  val verifyAccountResponse: VerifyAccountResponse,
  val masterKey: MasterKey?,
  val pin: String?,
  val aciPreKeyCollection: PreKeyCollection?,
  val pniPreKeyCollection: PreKeyCollection?
) {
  companion object {
    fun from(
      response: ServiceResponse<VerifyAccountResponse>,
      masterKey: MasterKey?,
      pin: String?,
      aciPreKeyCollection: PreKeyCollection?,
      pniPreKeyCollection: PreKeyCollection?
    ): ServiceResponse<VerifyResponse> {
      return if (response.result.isPresent) {
        ServiceResponse.forResult(VerifyResponse(response.result.get(), masterKey, pin, aciPreKeyCollection, pniPreKeyCollection), 200, null)
      } else {
        ServiceResponse.coerceError(response)
      }
    }
  }
}
