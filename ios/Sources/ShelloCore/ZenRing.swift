import Foundation

/// Geometry of the Zen ring: actions sit evenly around the touch point, the first at 12 o'clock, clockwise.
public enum ZenRing {
    public static let radius: Double = 92
    public static let deadZone: Double = 36
    /// Index of the action the finger points at, or nil inside the dead zone (release there cancels).
    public static func index(dx: Double, dy: Double, count: Int) -> Int? {
        guard count > 0, (dx * dx + dy * dy).squareRoot() >= deadZone else { return nil }
        var a = atan2(dx, -dy)                         // 0 = up, clockwise positive
        if a < 0 { a += 2 * .pi }
        let step = 2 * Double.pi / Double(count)
        return Int((a / step).rounded()) % count
    }
    /// Centre offset of item `i` of `count` from the touch point.
    public static func offset(_ i: Int, of count: Int, radius r: Double = radius) -> (x: Double, y: Double) {
        let a = Double(i) * 2 * Double.pi / Double(max(1, count))
        return (r * sin(a), -r * cos(a))
    }
}
