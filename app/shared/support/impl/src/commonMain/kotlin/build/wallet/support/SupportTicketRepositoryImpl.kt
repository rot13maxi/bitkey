package build.wallet.support

import build.wallet.account.analytics.AppInstallationDao
import build.wallet.analytics.events.PlatformInfoProvider
import build.wallet.analytics.v1.OSType
import build.wallet.bitkey.f8e.AccountId
import build.wallet.f8e.F8eEnvironment
import build.wallet.f8e.support.CreateTicketDTO
import build.wallet.f8e.support.SupportTicketService
import build.wallet.f8e.support.TicketDebugDataDTO
import build.wallet.f8e.support.TicketFormFieldDTO
import build.wallet.feature.FeatureFlag
import build.wallet.firmware.FirmwareDeviceInfoDao
import build.wallet.logging.LogLevel
import build.wallet.logging.dev.LogStore
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import okio.Buffer

class SupportTicketRepositoryImpl(
  private val supportTicketService: SupportTicketService,
  private val logStore: LogStore,
  private val appInstallationDao: AppInstallationDao,
  private val firmwareDeviceInfoDao: FirmwareDeviceInfoDao,
  private val platformInfoProvider: PlatformInfoProvider,
  private val allFeatureFlags: List<FeatureFlag<*>>,
) : SupportTicketRepository {
  override suspend fun createTicket(
    f8eEnvironment: F8eEnvironment,
    accountId: AccountId,
    form: SupportTicketForm,
    data: SupportTicketData,
    addAttachmentsEnabled: Boolean,
  ): Result<Unit, Error> {
    val logAttachments = logAttachmentsIfEnabled(data, addAttachmentsEnabled)

    val attachmentUploadResults =
      uploadAttachments(
        f8eEnvironment = f8eEnvironment,
        accountId = accountId,
        attachments = data.attachments + logAttachments
      )

    val subject =
      form[SupportTicketField.KnownFieldType.Subject]?.let {
        data[it] ?: "[ERROR] Empty subject - validation failed."
      } ?: "[ERROR] Subject field not found!"
    val description =
      form[SupportTicketField.KnownFieldType.Description]?.let {
        data[it] ?: "[ERROR] Empty description - validation failed."
      } ?: "[ERROR] Description field not found!"

    // For privacy, we want to only include fields that were visible when the user submitted the form
    val visibleFieldsData =
      data.asRawValueMap()
        .filterKeys { form.conditions.evaluate(it, data) is ConditionEvaluationResult.Visible }

    val ticket =
      CreateTicketDTO(
        email = data.email.value,
        formId = form.id,
        subject = subject,
        description = description,
        customFieldValues =
          visibleFieldsData
            .mapKeys { (field, _) -> field.id }
            .mapValues { (_, rawValue) ->
              when (rawValue) {
                is SupportTicketField.RawValue.Bool -> TicketFormFieldDTO.Value.Bool(rawValue.value)
                is SupportTicketField.RawValue.Text -> TicketFormFieldDTO.Value.Text(rawValue.value)
                is SupportTicketField.RawValue.MultiChoice ->
                  TicketFormFieldDTO.Value.MultiChoice(
                    rawValue.values
                  )
              }
            },
        attachments =
          attachmentUploadResults.map { (attachment, result) ->
            when (result) {
              is Ok -> CreateTicketDTO.AttachmentUploadResultDTO.Success(result.value)
              is Err ->
                CreateTicketDTO.AttachmentUploadResultDTO.Failure(
                  filename = attachment.name,
                  mimeType = attachment.mimeType.name,
                  error = result.error.toString()
                )
            }
          },
        debugData =
          if (data.sendDebugData) {
            getDebugData()
          } else {
            null
          }
      )

    return supportTicketService
      .createTicket(
        f8eEnvironment = f8eEnvironment,
        accountId = accountId,
        ticket = ticket
      )
  }

  override suspend fun loadFormStructure(
    f8eEnvironment: F8eEnvironment,
    accountId: AccountId,
  ): Result<SupportTicketForm, Error> {
    return supportTicketService.getFormStructure(f8eEnvironment, accountId)
      .map { structureDto ->
        val fields =
          structureDto.fields.map { field ->
            when (field.type) {
              TicketFormFieldDTO.Type.Text ->
                SupportTicketField.TextField(
                  id = field.id,
                  title = field.title,
                  isRequired = field.required,
                  knownType = resolveKnownType(field.knownType)
                )

              TicketFormFieldDTO.Type.Picker ->
                SupportTicketField.Picker(
                  id = field.id,
                  title = field.title,
                  isRequired = field.required,
                  items =
                    field.options?.map {
                      SupportTicketField.Picker.Item(
                        title = it.name,
                        value = it.value
                      )
                    } ?: emptyList(),
                  knownType = resolveKnownType(field.knownType)
                )
              TicketFormFieldDTO.Type.CheckBox ->
                SupportTicketField.CheckBox(
                  id = field.id,
                  title = field.title,
                  isRequired = field.required,
                  knownType = resolveKnownType(field.knownType)
                )

              TicketFormFieldDTO.Type.TextArea ->
                SupportTicketField.TextArea(
                  id = field.id,
                  title = field.title,
                  isRequired = field.required,
                  knownType = resolveKnownType(field.knownType)
                )
              TicketFormFieldDTO.Type.Date ->
                SupportTicketField.Date(
                  id = field.id,
                  title = field.title,
                  isRequired = field.required,
                  knownType = resolveKnownType(field.knownType)
                )
              TicketFormFieldDTO.Type.MultiSelect ->
                SupportTicketField.MultiSelect(
                  id = field.id,
                  title = field.title,
                  isRequired = field.required,
                  items =
                    field.options?.map {
                      SupportTicketField.MultiSelect.Item(
                        title = it.name,
                        value = it.value
                      )
                    } ?: emptyList(),
                  knownType = resolveKnownType(field.knownType)
                )
            }
          }
        val fieldsById = fields.associateBy { it.id }
        val conditions =
          structureDto.conditions.map { condition ->
            SupportTicketFieldCondition(
              parentField = fieldsById[condition.parentFieldId]!!,
              expectedValue =
                when (val value = condition.value) {
                  is TicketFormFieldDTO.Value.Bool -> SupportTicketField.RawValue.Bool(value.value)
                  is TicketFormFieldDTO.Value.Text -> SupportTicketField.RawValue.Text(value.value)
                  is TicketFormFieldDTO.Value.MultiChoice ->
                    SupportTicketField.RawValue.MultiChoice(
                      value.values
                    )
                },
              children =
                condition.childFields.map { childVisibility ->
                  SupportTicketFieldCondition.Child(
                    field = fieldsById[childVisibility.id]!!,
                    isRequired = childVisibility.isRequired
                  )
                }
            )
          }

        SupportTicketForm(
          id = structureDto.id,
          fields = fields,
          conditions = conditions.optimize()
        )
      }
  }

  override suspend fun prefillKnownFields(form: SupportTicketForm): SupportTicketData {
    val debugData = getDebugData()

    return buildSupportTicketData {
      this[form, SupportTicketField.KnownFieldType.AppInstallationID] = debugData.appInstallationId
      this[form, SupportTicketField.KnownFieldType.AppVersion] = debugData.appVersion
      this[form, SupportTicketField.KnownFieldType.PhoneMakeAndModel] = debugData.phoneMakeAndModel
      this[form, SupportTicketField.KnownFieldType.SystemNameAndVersion] = debugData.systemNameAndVersion

      this[form, SupportTicketField.KnownFieldType.HardwareSerialNumber] = debugData.hardwareSerialNumber
      this[form, SupportTicketField.KnownFieldType.HardwareFirmwareVersion] = debugData.hardwareFirmwareVersion
    }
  }

  private inline fun <reified Field : SupportTicketField<Value>, reified Value : Any> resolveKnownType(
    dto: TicketFormFieldDTO.KnownType?,
  ): SupportTicketField.KnownFieldType<Field>? {
    val mappedType =
      when (dto) {
        TicketFormFieldDTO.KnownType.Subject -> SupportTicketField.KnownFieldType.Subject
        TicketFormFieldDTO.KnownType.Description -> SupportTicketField.KnownFieldType.Description
        TicketFormFieldDTO.KnownType.Country -> SupportTicketField.KnownFieldType.Country
        TicketFormFieldDTO.KnownType.AppVersion -> SupportTicketField.KnownFieldType.AppVersion
        TicketFormFieldDTO.KnownType.AppInstallationID -> SupportTicketField.KnownFieldType.AppInstallationID
        TicketFormFieldDTO.KnownType.PhoneMakeAndModel -> SupportTicketField.KnownFieldType.PhoneMakeAndModel
        TicketFormFieldDTO.KnownType.SystemNameAndVersion -> SupportTicketField.KnownFieldType.SystemNameAndVersion
        TicketFormFieldDTO.KnownType.HardwareSerialNumber -> SupportTicketField.KnownFieldType.HardwareSerialNumber
        TicketFormFieldDTO.KnownType.HardwareFirmwareVersion -> SupportTicketField.KnownFieldType.HardwareFirmwareVersion
        null -> return null
      }

    return if (Field::class == mappedType.fieldClass) {
      mappedType as SupportTicketField.KnownFieldType<Field>
    } else {
      // TODO: Let's log that an unexpected known field was assigned to this type
      null
    }
  }

  private operator fun <Field : SupportTicketField<Value>, Value : Any> MutableSupportTicketData.set(
    form: SupportTicketForm,
    type: SupportTicketField.KnownFieldType<Field>,
    value: Value,
  ) {
    form[type]?.let { field ->
      this[field] = value
    }
  }

  private fun logAttachmentsIfEnabled(
    data: SupportTicketData,
    addAttachmentsEnabled: Boolean,
  ): List<SupportTicketAttachment> {
    return if (data.sendDebugData && addAttachmentsEnabled) {
      listOf(
        SupportTicketAttachment.Logs(
          name = "app.log",
          data = {
            val wholeLog =
              logStore.getCurrentLogs(minimumLevel = LogLevel.Verbose, tag = null)
                .joinToString("\n") { item ->
                  item.toString()
                }

            Buffer().writeUtf8(wholeLog)
          }
        )
      )
    } else {
      emptyList()
    }
  }

  private suspend fun uploadAttachments(
    f8eEnvironment: F8eEnvironment,
    accountId: AccountId,
    attachments: List<SupportTicketAttachment>,
  ): List<Pair<SupportTicketAttachment, Result<String, Any>>> {
    return attachments.map { attachment ->
      val result =
        attachment.data()?.let { source ->
          supportTicketService.uploadAttachment(
            f8eEnvironment = f8eEnvironment,
            accountId = accountId,
            filename = attachment.name,
            mimeType = attachment.mimeType,
            source = source
          )
        } ?: Err("Could not obtain data for ${attachment.name} (${attachment.mimeType})")

      attachment to result
    }
  }

  private suspend fun getDebugData(): TicketDebugDataDTO {
    val appInstallation = appInstallationDao.getOrCreateAppInstallation().get()
    val deviceInfo = firmwareDeviceInfoDao.getDeviceInfo().get()
    val platformInfo = platformInfoProvider.getPlatformInfo()

    return TicketDebugDataDTO(
      appInstallationId = appInstallation?.localId.orEmpty(),
      appVersion = platformInfo.application_version,
      phoneMakeAndModel =
        listOfNotNull(
          platformInfo.device_make,
          platformInfo.device_model,
          platformInfo.device_id.takeIf { it.isNotBlank() }?.let { "($it)" }
        ).joinToString(" "),
      systemNameAndVersion =
        listOf(
          platformInfo.os_type.systemName,
          platformInfo.os_version
        ).joinToString(" "),
      hardwareSerialNumber = deviceInfo?.serial.orEmpty(),
      hardwareFirmwareVersion = deviceInfo?.version.orEmpty(),
      featureFlags =
        allFeatureFlags.associate { flag ->
          flag.identifier to flag.flagValue().value.toString()
        }
    )
  }

  private val OSType.systemName: String get() =
    when (this) {
      OSType.OS_TYPE_UNSPECIFIED -> "Unspecified"
      OSType.OS_TYPE_ANDROID -> "Android"
      OSType.OS_TYPE_IOS -> "iOS"
      OSType.OS_TYPE_WINDOWS -> "Windows"
      OSType.OS_TYPE_UNIX -> "Unix"
    }
}
