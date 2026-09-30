import XCTest

final class FerretLaunchTests: XCTestCase {
    func testWalletUnlockSurfaceRenders() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.buttons["Unlock"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Your wallet stays encrypted on this device."].exists)
    }
}
