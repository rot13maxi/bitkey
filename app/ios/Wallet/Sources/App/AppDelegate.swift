import AVFAudio
import Bugsnag
import DatadogCore
import DatadogCrashReporting
import DatadogRUM
import DatadogLogs
import DatadogTrace
import Shared
import UIKit
import UserNotifications

// MARK: -

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    // MARK: - Internal Properties

    let appContext: AppContext

    var window: UIWindow?
    
    // MARK: - Life Cycle

    override init() {
        // Initialize crash reporters first.
        let appVariant = AppVariant.current()
        initializeBugsnag(appVariant: appVariant)
        initializeDatadog(appVariant: appVariant)

        self.appContext = AppContext(appVariant: appVariant)

        appContext.appComponent.bugsnagContext.configureCommonMetadata()

        super.init()

        appContext.appUiStateMachineManager.connectSharedStateMachine()
    }

    // MARK: - UIApplicationDelegate

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = appContext.appUiStateMachineManager.appViewController

        window?.makeKeyAndVisible()

        appContext.sharingManager.mainWindow = window
        FwupNfcMaskOverlayViewController.mainWindow = window
        
        UNUserNotificationCenter.current().delegate = appContext.notificationManager

        // We have videos in our app which, even though they don't have audio, means when they play they
        // take over audio control from other apps. This prevents that.
        try? AVAudioSession.sharedInstance().setCategory(
            .playback,
            mode: .default,
            options: [.mixWithOthers]
        )

        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        appContext.notificationManager.application(application, didRegisterForRemoteNotificationsWithDeviceToken: deviceToken)
    }

    func application(_: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        log(.error, error: error) { "Failed to register for remote notifications" }
    }
    
    func applicationDidBecomeActive(_ application: UIApplication) {
        // Notify the session provider that we are now in the foreground.
        // This is to refresh the session ID if a certain amount of time has passed in the background.
        appContext.appComponent.sessionIdProvider.applicationDidEnterForeground()

        // Notify the notification manager that we are now in the foreground so it can perform relevant tasks.
        appContext.notificationManager.applicationDidEnterForeground(application)
    }

    func applicationWillResignActive(_ application: UIApplication) {
        appContext.appComponent.sessionIdProvider.applicationDidEnterBackground()
    }
    
    func application(_ application: UIApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
        // Get URL components from the incoming user activity.
        guard userActivity.activityType == NSUserActivityTypeBrowsingWeb, let incomingURL = userActivity.webpageURL else {
            return false
        }
    
        if let route = Route.companion.fromUrl(url: incomingURL.absoluteString) {
            Router.shared.route = route
            return true
        }
        
        return false
    }
}

// MARK: - Private Methods

    fileprivate func initializeDatadog(appVariant: AppVariant) {
        let config = DatadogConfig.companion.create(appVariant: appVariant)
        // TODO(W-1144): extract builder configuration to Info.plist
        Datadog.initialize(
            with: .init(
                clientToken: "pubc98a5dc771810d55d228a00baa7c4cdd",
                env: config.environmentName,
                site: .us1
            ),
            trackingConsent: .granted
        )
        Datadog.verbosityLevel = .debug
        CrashReporting.enable()
        RUM.enable(
            with: RUM.Configuration(
                applicationID: "4a66b458-4cb8-444e-9298-cf9ac3d1f25e",
                uiKitViewsPredicate: DefaultUIKitRUMViewsPredicate(),
                uiKitActionsPredicate: DefaultUIKitRUMActionsPredicate(),
                urlSessionTracking: RUM.Configuration.URLSessionTracking(
                    firstPartyHostsTracing: .trace(hosts: ["wallet.build", "bitkey.build"], sampleRate: 100)
                ),
                telemetrySampleRate: 100
            )
        )
        Trace.enable(
            with: Trace.Configuration(
                sampleRate: 100,
                urlSessionTracking: .init(
                    firstPartyHostsTracing: .trace(hosts: ["wallet.build", "bitkey.build"], sampleRate: 100)
                ),
                networkInfoEnabled: true
            )
        )
        Logs.enable()
    }
    
    fileprivate func initializeBugsnag(appVariant: AppVariant) {
        // Get common config
        let config = BugsnagConfig(appVariant: appVariant)
        // Initialize Bugsnag using iOS SDK
        let bugsnagConfig = BugsnagConfiguration.loadConfig()
        bugsnagConfig.enabledBreadcrumbTypes = [.all]
        bugsnagConfig.releaseStage = config.releaseStage
        Bugsnag().initialize(config: bugsnagConfig)
    }
