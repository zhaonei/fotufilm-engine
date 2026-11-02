import XCTest
@testable import FotufilmCore

final class NoFilmNormalTests: XCTestCase {
    // 如果是 macOS 环境，这里的测试会运行
    #if canImport(Metal)
    func testGpuNormalOnMacOSDevelops() throws {
        guard let gpu = HalideMetalFilmRenderer.shared else { throw XCTSkip("no Metal") }
        let width = 96, height = 48
        // 原文件里剩余的测试代码...
        // 你可以把报错日志里提到的其他测试代码贴进来
    }
    #endif
}
