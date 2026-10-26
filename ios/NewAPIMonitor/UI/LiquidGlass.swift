import SwiftUI

// MARK: - Apple-Native Theme & Liquid Glass Modifiers

struct GlassTheme: Equatable, Sendable {
    let mode: String
    let accent: String
    let scheme: ColorScheme

    var isLight: Bool { mode == "light" || (mode == "system" && scheme == .light) }
    var isOLED: Bool { mode == "oled" }

    var background: Color {
        if isOLED { return .black }
        if isLight { return Color(hex: 0xF5F5FA) }
        return Color(hex: 0x0E0B16)
    }

    var text: Color { isLight ? Color(hex: 0x17141F) : Color(hex: 0xF4F2FA) }
    var secondaryText: Color { isLight ? Color(hex: 0x4C4477) : Color(hex: 0xC7C0DE) }
    var muted: Color { isLight ? Color(hex: 0x8E88A0) : Color(hex: 0x9E95B8) }

    var accent1: Color {
        switch accent {
        case "blue": return Color(hex: 0x2563EB)
        case "green": return Color(hex: 0x059669)
        case "orange": return Color(hex: 0xEA580C)
        default: return Color(hex: 0x8B5CF6)
        }
    }

    var accent2: Color {
        switch accent {
        case "blue": return Color(hex: 0x06B6D4)
        case "green": return Color(hex: 0x84CC16)
        case "orange": return Color(hex: 0xFBBF24)
        default: return Color(hex: 0x22D3EE)
        }
    }

    static let okGreen = Color(hex: 0x34D399)
    static let warnAmber = Color(hex: 0xFBBF24)
    static let errRed = Color(hex: 0xF87171)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}

// MARK: - Apple Native Liquid Glass Surface Modifiers (iOS 26 .glassEffect + iOS 17-18 Material Fallback)

extension View {
    /// Applies Apple's native Liquid Glass (`glassEffect`) on iOS 26+, falling back to SwiftUI `.ultraThinMaterial` on iOS 17–18.
    @ViewBuilder
    func nativeLiquidGlassCard(
        cornerRadius: CGFloat = 20,
        tint: Color? = nil,
        interactive: Bool = false
    ) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        if #available(iOS 26.0, *) {
            if let tint {
                if interactive {
                    self.glassEffect(.regular.tint(tint).interactive(), in: shape)
                } else {
                    self.glassEffect(.regular.tint(tint), in: shape)
                }
            } else {
                if interactive {
                    self.glassEffect(.regular.interactive(), in: shape)
                } else {
                    self.glassEffect(.regular, in: shape)
                }
            }
        } else {
            self
                .background {
                    shape
                        .fill(.ultraThinMaterial)
                        .overlay {
                            if let tint {
                                shape.fill(tint.opacity(0.14))
                            }
                        }
                        .overlay {
                            shape.strokeBorder(
                                LinearGradient(
                                    colors: [.white.opacity(0.32), .white.opacity(0.08)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 0.8
                            )
                        }
                }
        }
    }

    /// Applies Apple's native capsule Liquid Glass (`glassEffect(..., in: .capsule)`) on iOS 26+.
    @ViewBuilder
    func nativeLiquidGlassCapsule(tint: Color? = nil, interactive: Bool = false) -> some View {
        if #available(iOS 26.0, *) {
            if let tint {
                if interactive {
                    self.glassEffect(.regular.tint(tint).interactive(), in: .capsule)
                } else {
                    self.glassEffect(.regular.tint(tint), in: .capsule)
                }
            } else {
                if interactive {
                    self.glassEffect(.regular.interactive(), in: .capsule)
                } else {
                    self.glassEffect(.regular, in: .capsule)
                }
            }
        } else {
            self
                .background(.ultraThinMaterial, in: Capsule(style: .continuous))
                .overlay {
                    Capsule(style: .continuous)
                        .strokeBorder(.white.opacity(0.24), lineWidth: 0.8)
                }
        }
    }
}

/// Wraps grouped glass elements in Apple's `GlassEffectContainer` on iOS 26+ so adjacent glass views blend and morph natively.
struct NativeGlassContainer<Content: View>: View {
    var spacing: CGFloat = 16
    @ViewBuilder var content: () -> Content

    var body: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) {
                content()
            }
        } else {
            content()
        }
    }
}

struct GlassCard<Content: View>: View {
    let theme: GlassTheme
    var padding: CGFloat = 16
    var cornerRadius: CGFloat = 20
    var tint: Color? = nil
    var interactive: Bool = false
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            content()
        }
        .padding(padding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .nativeLiquidGlassCard(cornerRadius: cornerRadius, tint: tint, interactive: interactive)
    }
}

// MARK: - Ambient Mesh / Glow Backdrop for Liquid Glass Refraction

struct LiquidGlassBackdrop: View {
    let theme: GlassTheme

    var body: some View {
        ZStack {
            theme.background
            if !theme.isOLED {
                RadialGradient(
                    colors: [theme.accent1.opacity(theme.isLight ? 0.16 : 0.28), .clear],
                    center: .topLeading,
                    startRadius: 20,
                    endRadius: 420
                )
                RadialGradient(
                    colors: [theme.accent2.opacity(theme.isLight ? 0.12 : 0.20), .clear],
                    center: .bottomTrailing,
                    startRadius: 20,
                    endRadius: 380
                )
            }
        }
        .ignoresSafeArea()
    }
}

// MARK: - Brand Pulse Logo & Status Indicator

struct BrandLogo: View {
    let theme: GlassTheme
    var size: CGFloat = 24

    var body: some View {
        Canvas { context, canvas in
            let s = canvas.width / 48
            var path = Path()
            path.move(to: CGPoint(x: 4 * s, y: 24 * s))
            path.addLine(to: CGPoint(x: 16.5 * s, y: 24 * s))
            path.addLine(to: CGPoint(x: 23 * s, y: 11 * s))
            path.addLine(to: CGPoint(x: 30.5 * s, y: 37 * s))
            path.addLine(to: CGPoint(x: 37 * s, y: 24 * s))
            path.addLine(to: CGPoint(x: 43 * s, y: 24 * s))
            context.stroke(
                path,
                with: .color(theme.accent2.opacity(0.18)),
                style: StrokeStyle(lineWidth: 7.5 * s, lineCap: .round, lineJoin: .round)
            )
            context.stroke(
                path,
                with: .linearGradient(
                    Gradient(colors: [theme.accent1, theme.accent2]),
                    startPoint: CGPoint(x: 4 * s, y: 24 * s),
                    endPoint: CGPoint(x: 43 * s, y: 24 * s)
                ),
                style: StrokeStyle(lineWidth: 4.4 * s, lineCap: .round, lineJoin: .round)
            )
            context.fill(
                Path(ellipseIn: CGRect(x: 40.6 * s, y: 21.6 * s, width: 4.8 * s, height: 4.8 * s)),
                with: .color(theme.accent2)
            )
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

struct StatusDot: View {
    let color: Color
    var size: CGFloat = 8

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .shadow(color: color.opacity(0.55), radius: 3)
    }
}
