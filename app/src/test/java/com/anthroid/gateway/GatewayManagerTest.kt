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

  private fun dispatchNotice(payload: String?): GatewayNotice? {
    val manager = manager()
    var received: GatewayNotice? = null
    manager.onNotification = { received = it }
    GatewayManager::class.java.getDeclaredMethod(
      "handleGatewayEvent", String::class.java, String::class.java,
    ).apply { isAccessible = true }.invoke(manager, "notification.push", payload)
    return received
  }

  @Test
  fun notificationPush_carriesTopLevelMetadataThroughCallback() {
    assertEquals(
      GatewayNotice("Ready", "Build finished", "Build agent", 1_700_000_000_123L),
      dispatchNotice(
        """{"title":"Ready","body":"Build finished","sender":"Build agent","sentAtMs":1700000000123}""",
      ),
    )
  }

  @Test
  fun notificationPush_legacyPayloadHasNoInventedMetadata() {
    assertEquals(
      GatewayNotice("Legacy", "Body"),
      dispatchNotice("""{"title":"Legacy","body":"Body"}"""),
    )
    assertEquals(GatewayNotice("Anthroid", ""), dispatchNotice(null))
  }

  @Test
  fun notificationPush_rejectsMalformedMetadataWithoutLosingText() {
    for (metadata in listOf(
      """"sender":null,"sentAtMs":null""",
      """"sender":{},"sentAtMs":"1700000000123""",
      """"sender":"  ","sentAtMs":true""",
      """"sender":42,"sentAtMs":1.5""",
    )) {
      assertEquals(
        GatewayNotice("Title", "Body"),
        dispatchNotice("""{"title":"Title","body":"Body",$metadata}"""),
      )
    }
  }

  @Test
  fun notificationPush_doesNotReadNestedMetadataOrReceivedTimestamp() {
    assertEquals(
      GatewayNotice("Anthroid", "Body"),
      dispatchNotice(
        """{"body":"Body","receivedAtMs":1700000000123,"metadata":{"sender":"Agent","sentAtMs":1700000000123}}""",
      ),
    )
  }

  @Test
  fun notificationPush_preservesEpochZeroAndTrimsSender() {
    assertEquals(
      GatewayNotice("Anthroid", "", "Agent", 0L),
      dispatchNotice("""{"sender":" Agent ","sentAtMs":0}"""),
    )
  }

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
  fun agentScopeKey_mergesCronAndRunSessionsByAgent() {
    assertEquals(
      "agent:main",
      GatewayManager.agentScopeKey("agent:main:cron:job-a"),
    )
    assertEquals(
      "agent:main",
      GatewayManager.agentScopeKey("agent:main:cron:job-b:run:123"),
    )
    assertEquals(
      "agent:other",
      GatewayManager.agentScopeKey("agent:other:main"),
    )
  }

  @Test
  fun agentScopeKey_preservesNonAgentSessionKeys() {
    assertEquals("gateway", GatewayManager.agentScopeKey("gateway"))
    assertEquals("other:session", GatewayManager.agentScopeKey("other:session"))
    assertEquals("agent:", GatewayManager.agentScopeKey("agent:"))
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
