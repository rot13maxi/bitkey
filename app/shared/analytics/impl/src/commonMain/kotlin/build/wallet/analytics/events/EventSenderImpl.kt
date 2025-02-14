package build.wallet.analytics.events

import build.wallet.analytics.v1.Event
import build.wallet.analytics.v1.EventBundle
import build.wallet.f8e.F8eEnvironment
import build.wallet.f8e.analytics.EventTrackerService
import build.wallet.queueprocessor.Processor
import com.github.michaelbull.result.Result

class EventSenderImpl(
  private val eventTrackerService: EventTrackerService,
) : Processor<QueueAnalyticsEvent> {
  override suspend fun processBatch(batch: List<QueueAnalyticsEvent>): Result<Unit, Error> {
    val f8eEnvironment = batch.first().f8eEnvironment
    return eventTrackerService.trackEvent(
      f8eEnvironment = f8eEnvironment,
      eventBundle = EventBundle(batch.map { it.event })
    )
  }
}

data class QueueAnalyticsEvent(
  val f8eEnvironment: F8eEnvironment,
  val event: Event,
)
