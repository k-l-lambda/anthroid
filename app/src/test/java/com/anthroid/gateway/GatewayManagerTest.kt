package com.anthroid.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GatewayManagerTest {

  private fun manager(): GatewayManager = GatewayManager(
    org.robolectric.RuntimeEnvironment.getApplication(),
    CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
  )

  @Test
  fun canonicalSessionKey_stripsRunSuffixOnlyFromAgentSessions() {
    assertEquals(
      "agent:financer:cron:gold-monitor",
      GatewayManager.canonicalSessionKey("agent:financer:cron:gold-monitor:run:123"),
    )
    assertEquals(
      "other:session:run:123",
      GatewayManager.canonicalSessionKey("other:session:run:123"),
    )
  }

  @Test
  fun setSessionLabel_upsertsAndUsesCanonicalCacheKey() {
    val manager = manager()

    manager.setSessionLabel("agent:financer:cron:gold-monitor:run:123", "Gold Monitor")

    assertEquals("Gold Monitor", manager.getSessionLabel("agent:financer:cron:gold-monitor"))
    assertEquals("Gold Monitor", manager.getSessionLabel("agent:financer:cron:gold-monitor:run:456"))
    assertEquals(
      "Gold Monitor",
      manager.getObservedSessions().single().displayName,
    )
  }

  @Test
  fun messageTitle_prefersTitleAndFallsBackToJobName() {
    assertEquals(
      "Explicit Title",
      GatewayManager.messageTitle(JSONObject("""{"title":" Explicit Title ","jobName":"Job Name"}""")),
    )
    assertEquals(
      "Job Name",
      GatewayManager.messageTitle(JSONObject("""{"jobName":" Job Name "}""")),
    )
    assertNull(GatewayManager.messageTitle(JSONObject("{}")))
  }
}
