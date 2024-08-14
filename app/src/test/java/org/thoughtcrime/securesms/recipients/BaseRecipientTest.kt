package org.thoughtcrime.securesms.recipients

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.GenZappStore

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
abstract class BaseRecipientTest {

  @Rule
  @JvmField
  val mockitoRule: MockitoRule = MockitoJUnit.rule()

  @Mock
  private lateinit var applicationDependenciesStaticMock: MockedStatic<AppDependencies>

  @Mock
  private lateinit var attachmentSecretProviderStaticMock: MockedStatic<AttachmentSecretProvider>

  @Mock
  private lateinit var GenZappStoreStaticMock: MockedStatic<GenZappStore>

  @Mock
  private lateinit var mockedGenZappStoreConstruction: MockedConstruction<GenZappStore>

  @Before
  fun superSetUp() {
    val application = ApplicationProvider.getApplicationContext<Application>()

    `when`(AppDependencies.application).thenReturn(application)
    `when`(AttachmentSecretProvider.getInstance(ArgumentMatchers.any())).thenThrow(RuntimeException::class.java)
  }
}
