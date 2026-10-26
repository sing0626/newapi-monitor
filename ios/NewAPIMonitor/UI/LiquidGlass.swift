import SwiftUI
import UIKit

enum LiquidGlassMath {
    static let barScale: CGFloat = 1.03
    static let bubbleScale: CGFloat = 1.08
    static let flingSeconds: CGFloat = 0.15

    static func snapIndex(currentIdx: Int, dragPx: CGFloat, velocityX: CGFloat, tabWidth: CGFloat, count: Int) -> Int {
        guard count > 0 else { return 0 }
        let current = min(max(currentIdx, 0), count - 1)
        guard tabWidth > 0, dragPx.isFinite, velocityX.isFinite, tabWidth.isFinite else { return current }
        let units = (dragPx + velocityX * flingSeconds) / tabWidth
        return min(max(current + Int(units.rounded()), 0), count - 1)
    }

    static func alignment(currentIdx: Int, dragPx: CGFloat?, tabWidth: CGFloat, count: Int) -> CGFloat {
        guard count > 1 else { return 0 }
        let current = CGFloat(min(max(currentIdx, 0), count - 1))
        let cell = (dragPx != nil && tabWidth > 0) ? current + dragPx! / tabWidth : current
        return min(max(cell / CGFloat(count - 1) * 2 - 1, -1), 1)
    }

    static func bubbleOffset(alignment: CGFloat, width: CGFloat, count: Int) -> CGFloat {
        guard count > 0, width > 0 else { return 0 }
        let clamped = min(max(alignment, -1), 1)
        return (clamped + 1) / 2 * max(0, width - width / CGFloat(count))
    }

    static func smoothedVibrancy(previous: Double, signal: Double?) -> Double {
        let clampedPrevious = min(max(previous.isFinite ? previous : 0.5, 0), 1)
        guard let signal, signal.isFinite else { return clampedPrevious }
        return min(max(clampedPrevious + (min(max(signal, 0), 1) - clampedPrevious) * 0.5, 0), 1)
    }

    static func signal(rgbBytes: [UInt8]) -> Double? {
        let pixels = rgbBytes.count / 4
        guard pixels > 0 else { return nil }
        var sum = 0.0
        var brightSum = 0.0
        var brightCount = 0
        for index in stride(from: 0, to: pixels * 4, by: 4) {
            let r = Double(rgbBytes[index])
            let g = Double(rgbBytes[index + 1])
            let b = Double(rgbBytes[index + 2])
            let lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            sum += lum
            if lum > 90 { brightSum += lum; brightCount += 1 }
        }
        let average = sum / Double(pixels) / 255
        let bright = brightCount > 0 ? brightSum / Double(brightCount) / 255 : average
        return min(max(((average + bright) / 2 - 0.18) / 0.42, 0), 1)
    }
}

struct GlassTheme {
    let mode: String
    let accent: String
    let scheme: ColorScheme

    var isLight: Bool { mode == "light" || (mode == "system" && scheme == .light) }
    var isOLED: Bool { mode == "oled" }
    var background: Color { isOLED ? .black : (isLight ? Color(hex: 0xFAF9FC) : Color(hex: 0x14101F)) }
    var card: Color { isOLED ? Color(hex: 0x161221) : (isLight ? .white : Color(hex: 0x251E33)) }
    var stroke: Color { isOLED ? Color(hex: 0x2E2740) : (isLight ? Color(hex: 0xECE9F2) : Color(hex: 0x3B3152)) }
    var chip: Color { isLight ? Color(hex: 0xF3F1FA) : .white.opacity(isOLED ? 0.08 : 0.09) }
    var text: Color { isLight ? Color(hex: 0x17141F) : Color(hex: 0xF2F0FA) }
    var secondaryText: Color { isLight ? Color(hex: 0x4C4477) : Color(hex: 0xC7C0DE) }
    var muted: Color { isLight ? Color(hex: 0x9B96AB) : Color(hex: 0xA89FC0) }
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
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

struct GlassCard<Content: View>: View {
    let theme: GlassTheme
    var padding: CGFloat = 16
    @ViewBuilder var content: () -> Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        VStack(alignment: .leading, spacing: 10) { content() }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.card, in: shape)
            .overlay(shape.strokeBorder(theme.stroke, lineWidth: 1))
            .shadow(color: theme.isLight ? Color(hex: 0x281E50, alpha: 0.08) : .black.opacity(0.28), radius: 10, y: 4)
    }
}

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
            context.stroke(path, with: .color(theme.accent2.opacity(0.18)),
                           style: StrokeStyle(lineWidth: 7.5 * s, lineCap: .round, lineJoin: .round))
            context.stroke(path, with: .linearGradient(Gradient(colors: [theme.accent1, theme.accent2]),
                           startPoint: CGPoint(x: 4 * s, y: 24 * s), endPoint: CGPoint(x: 43 * s, y: 24 * s)),
                           style: StrokeStyle(lineWidth: 4.4 * s, lineCap: .round, lineJoin: .round))
            context.fill(Path(ellipseIn: CGRect(x: 40.6 * s, y: 21.6 * s, width: 4.8 * s, height: 4.8 * s)),
                         with: .color(theme.accent2))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

struct GlassBottomBar: View {
    @Binding var selection: Int
    let active: Bool
    private let items: [(Int, String, String)] = [
        (0, "總覽", "square.grid.2x2.fill"),
        (1, "報表", "chart.bar.xaxis"),
        (2, "測速", "speedometer"),
        (3, "設定", "slider.horizontal.3")
    ]
    @State private var dragPx: CGFloat?
    @State private var pressed = false
    @State private var touch = CGPoint.zero
    @State private var innerWidth: CGFloat = 0
    @State private var barFrame = CGRect.zero
    @State private var vibrancy = 0.5
    @State private var lastVelocity: CGFloat = 0
    @State private var lastSample: (CGFloat, TimeInterval)?
    @GestureState private var gestureActive = false

    var body: some View {
        let count = items.count
        let width = innerWidth / CGFloat(count)
        let align = LiquidGlassMath.alignment(currentIdx: selection, dragPx: dragPx, tabWidth: width, count: count)
        ZStack {
            surface
            GeometryReader { geo in
                let track = geo.size.width
                let cellWidth = track / CGFloat(count)
                ZStack(alignment: .leading) {
                    // Keep the moving bubble free of blur/lens filters to avoid one-frame ghosting.
                    Capsule(style: .continuous)
                        .fill(.white.opacity(0.14))
                        .overlay(Capsule(style: .continuous).strokeBorder(.white.opacity(0.40), lineWidth: 1))
                        .padding(.horizontal, 2)
                        .frame(width: cellWidth, height: geo.size.height)
                        .scaleEffect(pressed ? LiquidGlassMath.bubbleScale : 1)
                        .offset(x: LiquidGlassMath.bubbleOffset(alignment: align, width: track, count: count))
                        .animation(dragPx == nil ? .timingCurve(0.22, 1, 0.36, 1, duration: 0.26) : nil, value: align)
                        .animation(.interpolatingSpring(stiffness: 320, damping: 20), value: pressed)
                    HStack(spacing: 0) {
                        ForEach(items, id: \.0) { id, label, icon in
                            let selected = selection == id
                            Button {
                                commit(id)
                            } label: {
                                VStack(spacing: 2) {
                                    Image(systemName: icon).font(.system(size: 18, weight: selected ? .bold : .medium))
                                    Text(label).font(.system(size: 12, weight: selected ? .bold : .medium)).lineLimit(1)
                                }
                                .foregroundStyle(selected ? Color(hex: 0xD9CDFF) : .white)
                                .frame(maxWidth: .infinity, minHeight: 48, maxHeight: .infinity)
                            }
                            .buttonStyle(.plain)
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel(label)
                            .accessibilityAddTraits(selected ? [.isButton, .isSelected] : [.isButton])
                        }
                    }
                }
                .onAppear { innerWidth = track }
                .onChange(of: track) { _, value in innerWidth = value }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
        }
        .frame(height: 62)
        .background(GeometryReader { geo in
            Color.clear
                .onAppear { barFrame = geo.frame(in: .global) }
                .onChange(of: geo.frame(in: .global)) { _, value in barFrame = value }
        })
        .scaleEffect(pressed ? LiquidGlassMath.barScale : 1)
        .animation(.interpolatingSpring(stiffness: 320, damping: 20), value: pressed)
        .shadow(color: .black.opacity(0.28), radius: 10, y: 5)
        .simultaneousGesture(gesture)
        .onChange(of: gestureActive) { _, isActive in if !isActive { cancelGesture() } }
        .onChange(of: selection) { _, _ in cancelGesture() }
        .onDisappear { cancelGesture() }
        .task(id: active) {
            guard active else { return }
            while !Task.isCancelled && active {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, active else { return }
                vibrancy = LiquidGlassMath.smoothedVibrancy(previous: vibrancy, signal: BackdropSampler.sample(excluding: barFrame))
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private var surface: some View {
        let v = min(max(vibrancy, 0), 1)
        return Capsule(style: .continuous)
            .fill(Color(hex: 0x1B1429).opacity(0.97))
            .overlay(
                Capsule(style: .continuous).fill(
                    LinearGradient(stops: [
                        .init(color: .white.opacity(0.06 + 0.05 * v), location: 0),
                        .init(color: Color(hex: 0x101F33).opacity(0.24 + 0.16 * v), location: 0.3),
                        .init(color: .clear, location: 1)
                    ], startPoint: .topLeading, endPoint: .bottomTrailing)
                )
            )
            .overlay(GeometryReader { geo in
                if pressed {
                    RadialGradient(colors: [.white.opacity(0.20), .clear],
                                   center: UnitPoint(x: min(max(touch.x / max(geo.size.width, 1), 0), 1),
                                                     y: min(max(touch.y / max(geo.size.height, 1), 0), 1)),
                                   startRadius: 0, endRadius: 1.1 * min(geo.size.width, geo.size.height))
                        .blendMode(.plusLighter)
                        .clipShape(Capsule(style: .continuous))
                }
            })
            .overlay(
                Capsule(style: .continuous).strokeBorder(
                    LinearGradient(stops: [
                        .init(color: .white.opacity(0.45 + 0.25 * v), location: 0),
                        .init(color: .white.opacity(0.25 + 0.15 * v), location: 0.5),
                        .init(color: .white.opacity(0.08 + 0.10 * v), location: 1)
                    ], startPoint: .topLeading, endPoint: .bottomTrailing),
                    lineWidth: 1
                )
            )
    }

    private var gesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .updating($gestureActive) { _, state, _ in state = true }
            .onChanged { value in
                touch = value.location
                pressed = true
                let now = value.time.timeIntervalSinceReferenceDate
                if let (previousX, previousTime) = lastSample {
                    let dt = now - previousTime
                    if dt > 0.001 && dt < 0.25 { lastVelocity = (value.translation.width - previousX) / CGFloat(dt) }
                }
                lastSample = (value.translation.width, now)
                if dragPx != nil || abs(value.translation.width) >= 6 { dragPx = value.translation.width }
            }
            .onEnded { value in
                let moved = dragPx
                let velocity = lastVelocity
                cancelGesture()
                let width = innerWidth / CGFloat(items.count)
                guard width > 0 else { return }
                if let moved {
                    commit(LiquidGlassMath.snapIndex(currentIdx: selection, dragPx: moved, velocityX: velocity,
                                                     tabWidth: width, count: items.count))
                } else {
                    let tapped = min(max(Int((value.location.x - 12) / width), 0), items.count - 1)
                    commit(items[tapped].0)
                }
            }
    }

    private func commit(_ index: Int) {
        withAnimation(.timingCurve(0.22, 1, 0.36, 1, duration: 0.26)) {
            dragPx = nil
            selection = index
        }
    }

    private func cancelGesture() {
        pressed = false
        dragPx = nil
        lastVelocity = 0
        lastSample = nil
    }
}

@MainActor
private enum BackdropSampler {
    static func sample(excluding barFrame: CGRect) -> Double? {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }),
              let window = scene.windows.first(where: \.isKeyWindow) else { return nil }
        let bounds = window.bounds
        let sampleHeight = max(1, min(bounds.height, barFrame.minY > 24 ? barFrame.minY : bounds.height - 96))
        guard bounds.width >= 24, sampleHeight >= 24 else { return nil }
        let width = 24
        let height = min(max(Int((24 * sampleHeight / bounds.width).rounded()), 1), 64)
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let space = CGColorSpaceCreateDeviceRGB()
        let ok = bytes.withUnsafeMutableBytes { ptr -> Bool in
            guard let base = ptr.baseAddress,
                  let context = CGContext(data: base, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: space,
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.translateBy(x: 0, y: CGFloat(height))
            context.scaleBy(x: CGFloat(width) / bounds.width, y: -CGFloat(height) / sampleHeight)
            window.layer.render(in: context)
            return true
        }
        return ok ? LiquidGlassMath.signal(rgbBytes: bytes) : nil
    }
}
