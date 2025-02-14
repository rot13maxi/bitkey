package build.wallet.statemachine.transactions

import build.wallet.analytics.events.screen.id.MoneyHomeEventTrackerScreenId
import build.wallet.platform.web.BrowserNavigator
import build.wallet.statemachine.core.Icon.Bitcoin
import build.wallet.statemachine.core.Icon.LargeIconCheckFilled
import build.wallet.statemachine.core.Icon.LargeIconWarningFilled
import build.wallet.statemachine.core.Icon.SmallIconArrowUpRight
import build.wallet.statemachine.core.Icon.SmallIconLightning
import build.wallet.statemachine.core.LabelModel
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.form.FormHeaderModel
import build.wallet.statemachine.core.form.FormHeaderModel.Alignment.CENTER
import build.wallet.statemachine.core.form.FormHeaderModel.SublineTreatment.MONO
import build.wallet.statemachine.core.form.FormMainContentModel.DataList
import build.wallet.ui.model.StandardClick
import build.wallet.ui.model.button.ButtonModel
import build.wallet.ui.model.button.ButtonModel.Size.Footer
import build.wallet.ui.model.icon.IconBackgroundType
import build.wallet.ui.model.icon.IconImage
import build.wallet.ui.model.icon.IconModel
import build.wallet.ui.model.icon.IconSize
import build.wallet.ui.model.toolbar.ToolbarAccessoryModel.IconAccessory.Companion.CloseAccessory
import build.wallet.ui.model.toolbar.ToolbarModel
import kotlinx.collections.immutable.ImmutableList

fun TransactionDetailModel(
  feeBumpEnabled: Boolean,
  txStatusModel: TxStatusModel,
  isLoading: Boolean,
  onLoaded: (BrowserNavigator) -> Unit,
  onViewTransaction: () -> Unit,
  onClose: () -> Unit,
  onSpeedUpTransaction: () -> Unit,
  content: ImmutableList<DataList>,
) = FormBodyModel(
  onLoaded = onLoaded,
  primaryButton =
    if (feeBumpEnabled) {
      ButtonModel(
        leadingIcon = SmallIconLightning,
        text = "Speed Up",
        treatment = ButtonModel.Treatment.Secondary,
        size = Footer,
        isLoading = isLoading,
        onClick = StandardClick(onSpeedUpTransaction)
      )
    } else {
      ButtonModel(
        leadingIcon = SmallIconArrowUpRight,
        text = "View Transaction",
        size = Footer,
        onClick = StandardClick(onViewTransaction)
      )
    },
  secondaryButton =
    if (feeBumpEnabled) {
      ButtonModel(
        leadingIcon = SmallIconArrowUpRight,
        text = "View Transaction",
        size = Footer,
        onClick = StandardClick(onViewTransaction)
      )
    } else {
      null
    },
  onBack = onClose,
  onSwipeToDismiss = onClose,
  header = txStatusModel.toFormHeaderModel(),
  toolbar =
    ToolbarModel(
      leadingAccessory = CloseAccessory(onClick = onClose)
    ),
  mainContentList = content,
  id = MoneyHomeEventTrackerScreenId.TRANSACTION_DETAIL
)

sealed interface TxStatusModel {
  val isIncoming: Boolean
  val recipientAddress: String

  fun toFormHeaderModel(): FormHeaderModel

  data class Pending(
    override val isIncoming: Boolean,
    override val recipientAddress: String,
    val isLate: Boolean,
  ) : TxStatusModel {
    override fun toFormHeaderModel(): FormHeaderModel {
      return when {
        isLate -> FormHeaderModel(
          icon = LargeIconWarningFilled,
          headline = "Transaction delayed",
          subline = recipientAddress,
          sublineTreatment = MONO,
          alignment = CENTER
        )
        else -> FormHeaderModel(
          iconModel = IconModel(
            iconImage = IconImage.Loader,
            iconSize = IconSize.Large,
            iconBackgroundType = IconBackgroundType.Circle(circleSize = IconSize.Avatar)
          ),
          headline = "Transaction pending",
          sublineModel = LabelModel.StringWithStyledSubstringModel.from(
            string = recipientAddress,
            substringToColor = emptyMap()
          ),
          sublineTreatment = MONO,
          alignment = CENTER
        )
      }
    }
  }

  data class Confirmed(
    override val isIncoming: Boolean,
    override val recipientAddress: String,
  ) : TxStatusModel {
    override fun toFormHeaderModel(): FormHeaderModel {
      return FormHeaderModel(
        icon = when {
          isIncoming -> Bitcoin
          else -> LargeIconCheckFilled
        },
        headline = when {
          isIncoming -> "Transaction received"
          else -> "Transaction sent"
        },
        subline = recipientAddress,
        sublineTreatment = MONO,
        alignment = CENTER
      )
    }
  }
}
