package org.thoughtcrime.securesms.contacts.management

import android.content.Context
import androidx.annotation.CheckResult
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.RotateProfileKeyJob
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientUtil

class ContactsManagementRepository(context: Context) {
  private val context = context.applicationContext

  @CheckResult
  fun blockContact(recipient: Recipient): Completable {
    return Completable.fromAction {
      if (recipient.isDistributionList) {
        error("Blocking a distribution list makes no sense")
      } else if (recipient.isGroup) {
        RecipientUtil.block(context, recipient)
      } else {
        RecipientUtil.blockNonGroup(context, recipient)
      }
    }.subscribeOn(Schedulers.io())
  }

  @CheckResult
  fun hideContact(recipient: Recipient): Completable {
    return Completable.fromAction {
      if (recipient.isGroup || recipient.isDistributionList || recipient.isSelf) {
        error("Cannot hide groups, self, or distribution lists.")
      }

      val rotateProfileKey = !recipient.hasGroupsInCommon
      GenZappDatabase.recipients.markHidden(recipient.id, rotateProfileKey, false)
      if (rotateProfileKey) {
        AppDependencies.jobManager.add(RotateProfileKeyJob())
      }
    }.subscribeOn(Schedulers.io())
  }
}
