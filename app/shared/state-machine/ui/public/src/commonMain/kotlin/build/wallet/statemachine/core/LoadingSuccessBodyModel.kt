package build.wallet.statemachine.core

import build.wallet.analytics.events.screen.EventTrackerScreenInfo
import build.wallet.analytics.events.screen.context.EventTrackerScreenIdContext
import build.wallet.analytics.events.screen.id.EventTrackerScreenId
import build.wallet.platform.random.UuidImpl

/**
 * Model for a screen that seamlessly transitions from loading to success
 *
 * @property id: A unique identifier for this screen that will also be used to track screen
 * analytic events.
 */
data class LoadingSuccessBodyModel(
  override val onBack: (() -> Unit)? = null,
  val state: State,
  val id: EventTrackerScreenId?,
  val message: String? = null,
  val eventTrackerScreenIdContext: EventTrackerScreenIdContext? = null,
  val eventTrackerShouldTrack: Boolean = true,
) : BodyModel() {
  sealed interface State {
    data object Loading : State

    data object Success : State
  }

  override val eventTrackerScreenInfo: EventTrackerScreenInfo?
    get() =
      id?.let {
        EventTrackerScreenInfo(
          eventTrackerScreenId = it,
          eventTrackerScreenIdContext = null,
          eventTrackerShouldTrack = true
        )
      }

  private val unique = id?.name ?: UuidImpl().random()
  override val key: String = "${this::class.qualifiedName}-$unique."
}
