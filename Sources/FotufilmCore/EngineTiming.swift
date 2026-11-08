import Dispatch
import Foundation

/// Where the engine says what its stages cost, for a host that shows it.
///
/// Instrumentation only: the numbers are read off clocks around work that happens anyway, and
/// nothing here decides anything. A host that wants them sets `sink` once; with no sink the
/// measurements are not even taken.
public enum EngineTiming {
    /// Set once by the host.
    nonisolated(unsafe) public static var sink: ((_ name: String, _ milliseconds: Double) -> Void)?

    /// Reports how long something took, when anyone is listening.
    @inline(__always)
    public static func note(_ name: String, since started: UInt64) {
        guard let sink else { return }
        sink(name, Double(DispatchTime.now().uptimeNanoseconds - started) / 1e6)
    }

    /// Reports a measurement already made.
    @inline(__always)
    public static func note(_ name: String, milliseconds: Double) {
        sink?(name, milliseconds)
    }
}
