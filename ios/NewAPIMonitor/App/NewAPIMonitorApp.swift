import SwiftUI
import UIKit
import UserNotifications

final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}

@main
struct NewAPIMonitorApp: App {
    @StateObject private var store = AppStore()
    private let notificationDelegate = NotificationDelegate()

    init() { UNUserNotificationCenter.current().delegate = notificationDelegate }

    var body: some Scene {
        WindowGroup {
            MonitorRootView().environmentObject(store)
        }
    }
}

struct MonitorRootView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var systemScheme

    private var theme: GlassTheme {
        GlassTheme(mode: store.appearance.mode, accent: store.appearance.accent, scheme: systemScheme)
    }
    private var scheme: ColorScheme? {
        switch store.appearance.mode {
        case "light": return .light
        case "dark", "oled": return .dark
        default: return nil
        }
    }

    var body: some View {
        ZStack {
            theme.background.ignoresSafeArea()
            Group {
                switch store.selectedTab {
                case 1: ReportsView()
                case 2: ProbeView()
                case 3: SettingsView()
                default: HomeView()
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if store.selectedTab != 0 || store.homeVisible {
                    GlassBottomBar(selection: $store.selectedTab, active: store.canOperate)
                }
            }
            if !store.isForeground || !store.isProtectedDataAvailable {
                theme.background.ignoresSafeArea()
                VStack(spacing: 16) {
                    BrandLogo(theme: theme, size: 52)
                    Text("NewAPI Monitor").font(.title2.bold()).foregroundStyle(theme.text)
                    Text("暫停連線・保護私隱").foregroundStyle(theme.muted)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .tint(theme.accent1)
        .preferredColorScheme(scheme)
        .onAppear { updateLifecycle() }
        .onChange(of: scenePhase) { _, _ in updateLifecycle() }
        .onChange(of: store.selectedTab) { _, value in if value != 0 { store.stopHomeRefresh() } }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataWillBecomeUnavailableNotification)) { _ in
            store.setLifecycle(active: false, protected: false)
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification)) { _ in updateLifecycle() }
        .alert("提示", isPresented: Binding(get: { store.message != nil }, set: { if !$0 { store.message = nil } })) {
            Button("知道喇", role: .cancel) { store.message = nil }
        } message: { Text(store.message ?? "") }
    }

    private func updateLifecycle() {
        store.setLifecycle(active: scenePhase == .active, protected: UIApplication.shared.isProtectedDataAvailable)
    }
}
