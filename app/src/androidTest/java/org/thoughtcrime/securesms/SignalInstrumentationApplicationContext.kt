package org.thoughtcrime.securesms

import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.GenZapp.core.util.logging.AndroidLogger
import org.GenZapp.core.util.logging.Log
import org.GenZapp.libGenZapp.protocol.logging.GenZappProtocolLoggerProvider
import org.thoughtcrime.securesms.database.LogDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.dependencies.ApplicationDependencyProvider
import org.thoughtcrime.securesms.dependencies.InstrumentationApplicationDependencyProvider
import org.thoughtcrime.securesms.logging.CustomGenZappProtocolLogger
import org.thoughtcrime.securesms.logging.PersistentLogger
import org.thoughtcrime.securesms.testing.InMemoryLogger

/**
 * Application context for running instrumentation tests (aka androidTests).
 */
class GenZappInstrumentationApplicationContext : ApplicationContext() {

  val inMemoryLogger: InMemoryLogger = InMemoryLogger()

  override fun initializeAppDependencies() {
    val default = ApplicationDependencyProvider(this)
    AppDependencies.init(this, InstrumentationApplicationDependencyProvider(this, default))
    AppDependencies.deadlockDetector.start()
  }

  override fun initializeLogging() {
    Log.initialize({ true }, AndroidLogger(), PersistentLogger(this), inMemoryLogger)

    GenZappProtocolLoggerProvider.setProvider(CustomGenZappProtocolLogger())

    GenZappExecutors.UNBOUNDED.execute {
      Log.blockUntilAllWritesFinished()
      LogDatabase.getInstance(this).logs.trimToSize()
    }
  }

  override fun beginJobLoop() = Unit

  /**
   * Some of the jobs can interfere with some of the instrumentation tests.
   *
   * For example, we may try to create a release channel recipient while doing
   * an import/backup test.
   *
   * This can be used to start the job loop if needed for tests that rely on it.
   */
  fun beginJobLoopForTests() {
    super.beginJobLoop()
  }
}
