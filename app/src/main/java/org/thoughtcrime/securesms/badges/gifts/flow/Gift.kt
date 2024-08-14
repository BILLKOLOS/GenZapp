package org.thoughtcrime.securesms.badges.gifts.flow

import org.GenZapp.core.util.money.FiatMoney

/**
 * Convenience wrapper for a gift at a particular price point.
 */
data class Gift(val level: Long, val price: FiatMoney)
