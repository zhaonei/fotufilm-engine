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

/// The film grain populations being laid down behind the develop that wanted them.
///
/// Laying one down takes a minute on a phone for a roll nobody has developed before -- an expired
/// or pushed setting, say -- and a develop cannot spend a minute: it goes without grain, the
/// population is prepared beside it, and the next develop has it. `pending` is how a develop says
/// so, and the host asks for the picture again once it is ready.
public enum FilmGrainWarmth {
    /// The last develop had to go without grain because its roll was still being prepared.
    nonisolated(unsafe) public static var pending = false

    private static let lock = NSLock()
    nonisolated(unsafe) private static var preparing: Set<Data> = []
    /// Populations that failed once: the develop after one would queue the same minute again.
    nonisolated(unsafe) private static var failed: Set<Data> = []

    /// True when nobody is preparing this population and it has not failed before.
    static func beginPreparing(_ identity: Data) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !preparing.contains(identity), !failed.contains(identity) else { return false }
        preparing.insert(identity)
        return true
    }

    static func finishedPreparing(_ identity: Data, failed didFail: Bool) {
        lock.lock()
        defer { lock.unlock() }
        preparing.remove(identity)
        if didFail { failed.insert(identity) }
    }
}
