package build.wallet.ui.app.core.form

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import build.wallet.android.ui.core.R
import build.wallet.platform.web.BrowserNavigator
import build.wallet.statemachine.core.LabelModel
import build.wallet.statemachine.core.LabelModel.StringModel
import build.wallet.statemachine.core.LabelModel.StringWithStyledSubstringModel
import build.wallet.statemachine.core.LabelModel.StringWithStyledSubstringModel.SubstringStyle.BoldStyle
import build.wallet.statemachine.core.LabelModel.StringWithStyledSubstringModel.SubstringStyle.ColorStyle
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.form.FormMainContentModel.AddressInput
import build.wallet.statemachine.core.form.FormMainContentModel.Button
import build.wallet.statemachine.core.form.FormMainContentModel.DataList
import build.wallet.statemachine.core.form.FormMainContentModel.DatePicker
import build.wallet.statemachine.core.form.FormMainContentModel.Explainer
import build.wallet.statemachine.core.form.FormMainContentModel.Explainer.Statement
import build.wallet.statemachine.core.form.FormMainContentModel.FeeOptionList
import build.wallet.statemachine.core.form.FormMainContentModel.ListGroup
import build.wallet.statemachine.core.form.FormMainContentModel.Loader
import build.wallet.statemachine.core.form.FormMainContentModel.MoneyHomeHero
import build.wallet.statemachine.core.form.FormMainContentModel.Picker
import build.wallet.statemachine.core.form.FormMainContentModel.Spacer
import build.wallet.statemachine.core.form.FormMainContentModel.TextArea
import build.wallet.statemachine.core.form.FormMainContentModel.TextInput
import build.wallet.statemachine.core.form.FormMainContentModel.Timer
import build.wallet.statemachine.core.form.FormMainContentModel.VerificationCodeInput
import build.wallet.statemachine.core.form.FormMainContentModel.WebView
import build.wallet.statemachine.core.form.RenderContext.Screen
import build.wallet.ui.app.account.toWalletTheme
import build.wallet.ui.app.core.fadingEdge
import build.wallet.ui.components.button.Button
import build.wallet.ui.components.explainer.Explainer
import build.wallet.ui.components.explainer.Statement
import build.wallet.ui.components.fee.FeeOption
import build.wallet.ui.components.forms.DatePickerField
import build.wallet.ui.components.forms.ItemPickerField
import build.wallet.ui.components.forms.TextField
import build.wallet.ui.components.forms.TextFieldOverflowCharacteristic.Multiline
import build.wallet.ui.components.header.Header
import build.wallet.ui.components.label.Label
import build.wallet.ui.components.label.LabelTreatment
import build.wallet.ui.components.list.ListGroup
import build.wallet.ui.components.loading.FormLoader
import build.wallet.ui.components.timer.Timer
import build.wallet.ui.components.toolbar.Toolbar
import build.wallet.ui.components.webview.WebView
import build.wallet.ui.compose.thenIf
import build.wallet.ui.data.DataGroup
import build.wallet.ui.model.button.ButtonModel
import build.wallet.ui.model.button.ButtonModel.Size.Footer
import build.wallet.ui.model.label.CallToActionModel
import build.wallet.ui.system.KeepScreenOn
import build.wallet.ui.theme.WalletTheme
import build.wallet.ui.tokens.LabelType
import kotlinx.collections.immutable.ImmutableList

@Composable
fun FormScreen(model: FormBodyModel) {
  if (model.keepScreenOn) {
    KeepScreenOn()
  }

  val uriHandler = LocalUriHandler.current
  LaunchedEffect("load-browser-navigator") {
    model.onLoaded(
      BrowserNavigator {
        uriHandler.openUri(it)
      }
    )
  }

  Box(
    modifier =
      Modifier.thenIf(model.renderContext == Screen) {
        Modifier.fillMaxSize()
      }
  ) {
    FormScreen(
      onBack = model.onBack,
      fullHeight = model.renderContext == Screen,
      headerToMainContentSpacing =
        when (val header = model.header) {
          null -> 16
          else ->
            when (header.sublineModel) {
              null -> 24
              else -> 16
            }
        },
      toolbarContent = {
        model.toolbar?.let {
          Toolbar(it)
        }
      },
      headerContent =
        model.header?.let { header ->
          {
            Header(
              model = header,
              headlineLabelType = LabelType.Title1
            )
          }
        },
      mainContent = {
        model.mainContentList.forEachIndexed { index, mainContent ->
          when (mainContent) {
            is Spacer ->
              Spacer(
                modifier =
                  mainContent.height?.let { Modifier.height(it.dp) }
                    ?: Modifier.weight(1F)
              )

            is Explainer -> Explainer(statements = mainContent.items)
            is DataList -> DataGroup(rows = mainContent)
            is FeeOptionList -> FeeOptionList(mainContent)
            is VerificationCodeInput -> VerificationCodeInput(mainContent)
            is TextInput -> TextInput(mainContent)
            is TextArea -> TextArea(mainContent)
            is AddressInput -> AddressTextField(mainContent)
            is DatePicker -> DatePicker(mainContent)
            is Timer -> Timer(model = mainContent)
            is WebView -> WebView(mainContent)
            is Button -> Button(model = mainContent.item)
            is ListGroup -> ListGroup(model = mainContent.listGroupModel)
            is Loader -> FormLoader()
            is MoneyHomeHero -> MoneyHomeHero(model = mainContent)
            is Picker -> Picker(model = mainContent)
          }
          if (index < model.mainContentList.lastIndex) {
            Spacer(modifier = Modifier.height(16.dp))
          }
        }
      },
      footerContent =
        when {
          model.primaryButton != null || model.secondaryButton != null -> {
            {
              model.ctaWarning?.let {
                CallToActionLabel(model = it)
                Spacer(Modifier.height(12.dp))
              }
              model.primaryButton?.toFooterButton()
              model.secondaryButton?.let { secondaryButton ->
                Spacer(Modifier.height(16.dp))
                secondaryButton.toFooterButton()
              }
              model.tertiaryButton?.let { tertiaryButton ->
                Spacer(Modifier.height(16.dp))
                tertiaryButton.toFooterButton()
              }
            }
          }

          else -> null
        }
    )
  }
}

@Composable
private fun Explainer(statements: ImmutableList<Statement>) {
  Explainer(modifier = Modifier.padding(end = 12.dp)) {
    statements.map { item ->
      Statement(
        icon = item.leadingIcon,
        title = item.title,
        onClick = (item.body as? LabelModel.LinkSubstringModel)?.let { linkedLabelModel ->
          { clickPosition ->
            linkedLabelModel.linkedSubstrings.find { ls ->
              ls.range.contains(clickPosition)
            }?.let { matchedLs ->
              matchedLs.onClick()
            }
          }
        },
        body =
          when (val body = item.body) {
            is StringModel -> AnnotatedString(body.string)
            is StringWithStyledSubstringModel ->
              buildAnnotatedString {
                append(body.string)
                body.styledSubstrings.forEach { styledSubstring ->
                  addStyle(
                    style =
                      when (val substringStyle = styledSubstring.style) {
                        is ColorStyle -> SpanStyle(color = substringStyle.color.toWalletTheme())
                        is BoldStyle -> SpanStyle(fontWeight = FontWeight.W600)
                      },
                    start = styledSubstring.range.first,
                    end = styledSubstring.range.last + 1
                  )
                }
              }
            is LabelModel.LinkSubstringModel -> {
              buildAnnotatedString {
                append(body.string)
                body.linkedSubstrings.forEach { linkedSubstring ->
                  addStyle(
                    style = SpanStyle(color = WalletTheme.colors.primary),
                    start = linkedSubstring.range.first,
                    end = linkedSubstring.range.last + 1
                  )
                }
              }
            }
          },
        tint =
          when (item.treatment) {
            Statement.Treatment.PRIMARY -> WalletTheme.colors.foreground
            Statement.Treatment.WARNING -> WalletTheme.colors.warningForeground
          }
      )
    }
  }
}

@Composable
private fun FeeOptionList(model: FeeOptionList) {
  Column(
    modifier = Modifier.selectableGroup(),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    model.options.forEach { option ->
      FeeOption(
        leadingText = option.optionName,
        trailingPrimaryText = option.transactionTime,
        trailingSecondaryText = option.transactionFee,
        selected = option.selected,
        enabled = option.enabled,
        infoText = option.infoText,
        onClick = option.onClick
      )
    }
  }
}

@Composable
private fun TextInput(model: TextInput) {
  Column(
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    model.title?.let {
      Label(
        text = it,
        type = LabelType.Title3,
        treatment = LabelTreatment.Primary
      )
    }

    TextField(
      modifier = Modifier.fillMaxWidth(),
      model = model.fieldModel
    )
  }
}

@Composable
private fun TextArea(model: TextArea) {
  Column(
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    model.title?.let {
      Label(
        text = it,
        type = LabelType.Title3,
        treatment = LabelTreatment.Primary
      )
    }

    TextField(
      modifier = Modifier.fillMaxWidth(),
      model = model.fieldModel,
      textFieldOverflowCharacteristic = Multiline
    )
  }
}

@Composable
private fun AddressTextField(model: AddressInput) {
  TextField(
    modifier = Modifier.fillMaxWidth(),
    model = model.fieldModel,
    labelType = LabelType.Body2Mono,
    textFieldOverflowCharacteristic = Multiline,
    trailingButtonModel = model.trailingButtonModel
  )
}

@Composable
private fun DatePicker(model: DatePicker) {
  Column(
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    model.title?.let {
      Label(
        text = it,
        type = LabelType.Title3,
        treatment = LabelTreatment.Primary
      )
    }

    DatePickerField(
      modifier = Modifier.fillMaxWidth(),
      model = model.fieldModel
    )
  }
}

@Composable
private fun MoneyHomeHero(model: MoneyHomeHero) {
  val image = painterResource(R.drawable.money_home_hero)
  Box {
    Image(
      painter = image,
      contentDescription = "money home hero",
      contentScale = ContentScale.Crop,
      alignment = Alignment.TopCenter,
      modifier =
        Modifier
          .align(Alignment.Center)
          .width(210.dp)
          .height(224.dp)
    )

    Column(
      modifier =
        Modifier
          .padding(vertical = 64.dp)
          .align(Alignment.TopCenter),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Label(model.primaryAmount, type = LabelType.Title1)
      Label(
        model.secondaryAmount,
        type = LabelType.Body4Medium,
        treatment = LabelTreatment.Secondary
      )
    }

    Box(
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .size(
            width = image.intrinsicSize.width.dp + 10.dp,
            height = 50.dp
          )
          .fadingEdge(
            Brush.verticalGradient(
              0f to Color.Transparent,
              0.7f to Color.Red
            )
          )
          .background(
            color = WalletTheme.colors.background,
            shape = RectangleShape
          )
    )
  }
}

@Composable
private fun Picker(model: Picker) {
  Column(
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    model.title?.let {
      Label(
        text = it,
        type = LabelType.Title3,
        treatment = LabelTreatment.Primary
      )
    }

    ItemPickerField(
      modifier = Modifier.fillMaxWidth(),
      model = model.fieldModel
    )
  }
}

@Composable
private fun CallToActionLabel(model: CallToActionModel) {
  Label(
    modifier = Modifier.fillMaxWidth(),
    text = model.text,
    type = LabelType.Body4Regular,
    treatment = when (model.treatment) {
      CallToActionModel.Treatment.SECONDARY -> LabelTreatment.Secondary
      CallToActionModel.Treatment.WARNING -> LabelTreatment.Warning
    },
    alignment = TextAlign.Center
  )
}

@SuppressLint("ComposableNaming")
@Composable
fun ButtonModel.toFooterButton() =
  Button(
    text = text,
    enabled = isEnabled,
    isLoading = isLoading,
    treatment = treatment,
    leadingIcon = leadingIcon,
    size = Footer,
    onClick = onClick
  )
