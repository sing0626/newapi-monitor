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
            TabView(selection: $store.selectedTab) {
                HomeView()
                    .tabItem { Label("總覽", systemImage: "square.grid.2x2.fill") }
                    .tag(0)
                ReportsView()
                    .tabItem { Label("報表", systemImage: "chart.bar.xaxis") }
                    .tag(1)
                ProbeView()
                    .tabItem { Label("測速", systemImage: "speedometer") }
                    .tag(2)
                SettingsView()
                    .tabItem { Label("設定", systemImage: "slider.horizontal.3") }
                    .tag(3)
            }
            .sensoryFeedback(.selection, trigger: store.selectedTab)

            if !store.isForeground || !store.isProtectedDataAvailable {
                LiquidGlassBackdrop(theme: theme)
                VStack(spacing: 16) {
                    BrandLogo(theme: theme, size: 52)
                    Text("NewAPI Monitor").font(.title2.bold()).foregroundStyle(theme.text)
                    Text("暫停連線・保護私隱").foregroundStyle(theme.muted)
                }
                .padding(28)
                .nativeLiquidGlassCard(cornerRadius: 24)
                .padding(32)
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
