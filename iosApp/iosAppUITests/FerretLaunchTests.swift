import XCTest

final class FerretLaunchTests: XCTestCase {
    func testSharedComposeRootRenders() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["Ferret"].waitForExistence(timeout: 20))
    }
}
