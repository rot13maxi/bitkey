import Foundation
import Shared

// MARK: -

/**
 * A set of dependencies that persist across all states of the app.
 */
class AppContext {

    // MARK: - Classes

    let appUiStateMachineManager: AppUiStateMachineManager

    let notificationManager: NotificationManager

    let appComponent: AppComponent
    let activityComponent: ActivityComponent

    let bdkAddressBuilder: BdkAddressBuilder
    let bdkBlockchainFactory: BdkBlockchainFactory
    let bdkBumpFeeTxBuilderFactory: BdkBumpFeeTxBuilderFactory
    let bdkMnemonicGenerator: BdkMnemonicGenerator
    let bdkDescriptorSecretKeyGenerator: BdkDescriptorSecretKeyGenerator
    let bdkDescriptorFactory: BdkDescriptorFactory
    let bdkDescriptorSecretKeyFactory: BdkDescriptorSecretKeyFactory
    let bdkWalletFactory: BdkWalletFactory
    let bdkPsbtBuilder: BdkPartiallySignedTransactionBuilder
    let bdkTxBuilderFactory: BdkTxBuilderFactory

    let deviceTokenProvider: DeviceTokenProvider
    let sharingManager: SharingManagerImpl
    let datadogTracer: DatadogTracer
    let secp256k1KeyGenerator: Secp256k1KeyGenerator

    // MARK: - Life Cycle

    init(appVariant: AppVariant) {
        self.deviceTokenProvider = DeviceTokenProviderImpl()

        self.bdkMnemonicGenerator = BdkMnemonicGeneratorImpl()
        self.bdkAddressBuilder = BdkAddressBuilderImpl()
        self.bdkBlockchainFactory = BdkBlockchainFactoryImpl()
        self.bdkBumpFeeTxBuilderFactory = BdkBumpFeeTxBuilderFactoryImpl()
        self.bdkDescriptorFactory = BdkDescriptorFactoryImpl()
        self.bdkDescriptorSecretKeyFactory = BdkDescriptorSecretKeyFactoryImpl()
        self.bdkDescriptorSecretKeyGenerator = BdkDescriptorSecretKeyGeneratorImpl()
        self.bdkWalletFactory = BdkWalletFactoryImpl()
        self.bdkTxBuilderFactory = BdkTxBuilderFactoryImpl()
        self.bdkPsbtBuilder = BdkPartiallySignedTransactionBuilderImpl()
        self.datadogTracer = DatadogTracerImpl()
        self.secp256k1KeyGenerator = Secp256k1KeyGeneratorImpl()

        let iCloudAccountRepository = iCloudAccountRepositoryImpl()
        let cloudStoreAccountRepository = CloudStoreAccountRepositoryImpl(
            iCloudAccountRepository: iCloudAccountRepository
        )
        let datadogRumMonitor = DatadogRumMonitorImpl()

        self.appComponent = AppComponentImplKt.makeAppComponent(
            appVariant: appVariant,
            bdkAddressBuilder: bdkAddressBuilder,
            bdkBlockchainFactory: bdkBlockchainFactory,
            bdkBumpFeeTxBuilderFactory: bdkBumpFeeTxBuilderFactory,
            bdkDescriptorSecretKeyGenerator: bdkDescriptorSecretKeyGenerator,
            bdkMnemonicGenerator: bdkMnemonicGenerator,
            bdkPartiallySignedTransactionBuilder: BdkPartiallySignedTransactionBuilderImpl(),
            bdkTxBuilderFactory: bdkTxBuilderFactory,
            bdkWalletFactory: bdkWalletFactory,
            datadogRumMonitor: datadogRumMonitor,
            datadogTracer: DatadogTracerImpl(),
            deviceTokenConfigProvider: DeviceTokenConfigProviderImpl(
                deviceTokenProvider: deviceTokenProvider,
                appVariant: AppVariant.current()
            ),
            fileManagerProvider: { FileManagerImpl(fileDirectoryProvider: $0) },
            logWritersProvider: { [
                DatadogLogWriter(logWriterContextStore: $0, minSeverity: .info),
                OSLogWriter()
            ] },
            messageSigner: MessageSignerImpl(),
            signatureVerifier: SignatureVerifierImpl(),
            secp256k1KeyGenerator: secp256k1KeyGenerator,
            teltra: TeltraImpl(),
            hardwareAttestation: HardwareAttestationImpl(),
            deviceOs: DeviceOs.ios,
            wsmVerifier: WsmVerifierImpl()
        )

        self.notificationManager = NotificationManagerImpl(
            appVariant: appComponent.appVariant,
            deviceTokenManager: appComponent.deviceTokenManager,
            deviceTokenProvider: deviceTokenProvider,
            eventTracker: appComponent.eventTracker,
            pushNotificationPermissionStatusProvider: appComponent.pushNotificationPermissionStatusProvider
        )

        let fakeHardwareKeyStore = FakeHardwareKeyStoreImpl(
            bdkMnemonicGenerator: self.bdkMnemonicGenerator,
            bdkDescriptorSecretKeyGenerator: self.bdkDescriptorSecretKeyGenerator,
            secp256k1KeyGenerator: self.secp256k1KeyGenerator,
            encryptedKeyValueStoreFactory: appComponent.secureStoreFactory
        )

        let fakeHardwareSpendingWalletProvider = FakeHardwareSpendingWalletProvider(
            spendingWalletProvider: appComponent.spendingWalletProvider,
            descriptorBuilder: appComponent.bitcoinMultiSigDescriptorBuilder,
            fakeHardwareKeyStore: fakeHardwareKeyStore
        )

        let nfcCommandsProvider = NfcCommandsProvider(
            real: NfcCommandsImpl(),
            fake: NfcCommandsFake(
                messageSigner: appComponent.messageSigner,
                fakeHardwareKeyStore: fakeHardwareKeyStore,
                fakeHardwareSpendingWalletProvider: fakeHardwareSpendingWalletProvider
            )
        )

        self.sharingManager = SharingManagerImpl()
        let appViewController = HiddenBarNavigationController()

        self.activityComponent = ActivityComponentImpl(
            appComponent: appComponent,
            cloudKeyValueStore: CloudKeyValueStoreImpl(
                iCloudKeyValueStore: iCloudKeyValueStoreImpl(clock: appComponent.clock)
            ),
            cloudFileStore: CloudFileStoreImpl(iCloudDriveFileStore: iCloudDriveFileStore()),
            cloudSignInUiStateMachine: CloudSignInUiStateMachineImpl(
                cloudStoreAccountRepository: cloudStoreAccountRepository
            ),
            cloudDevOptionsStateMachine: CloudDevOptionsStateMachineImpl(
                iCloudAccountRepository: iCloudAccountRepository
            ),
            cloudStoreAccountRepository: cloudStoreAccountRepository,
            datadogRumMonitor: DatadogRumMonitorImpl(),
            phoneNumberLibBindings: PhoneNumberLibBindingsImpl(),
            symmetricKeyEncryptor: SymmetricKeyEncryptorImpl(),
            symmetricKeyGenerator: SymmetricKeyGeneratorImpl(),
            lightningInvoiceParser: LightningInvoiceParserImpl(),
            sharingManager: sharingManager,
            systemSettingsLauncher: SystemSettingsLauncherImpl(),
            inAppBrowserNavigator: InAppBrowserNavigatorImpl(appViewController: appViewController),
            nfcCommandsProvider: nfcCommandsProvider,
            nfcSessionProvider: NfcSessionProviderImpl(),
            xChaCha20Poly1305: XChaCha20Poly1305Impl(),
            xNonceGenerator: XNonceGeneratorImpl(),
            spake2: Spake2Impl(),
            cryptoBox: CryptoBoxImpl(),
            pdfAnnotatorFactory: PdfAnnotatorFactoryImpl()
        )

        self.appUiStateMachineManager = AppUiStateMachineManagerImpl(
            appUiStateMachine: activityComponent.appUiStateMachine,
            appViewController: appViewController,
            context: .init(
                qrCodeScannerViewControllerFactory: QRCodeScannerViewControllerFactoryImpl()
            )
        )
    }
}

class NfcSessionProviderImpl : NfcSessionProvider {
    func get(parameters: NfcSessionParameters) throws -> NfcSession {
        if (parameters.isHardwareFake) {
            return NfcSessionFake(parameters: parameters)
        } else {
            return try NfcSessionImpl(parameters: parameters)
        }
    }
}
