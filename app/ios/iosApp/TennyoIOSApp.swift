import SwiftUI

@main
struct TennyoIOSApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .ignoresSafeArea(.container, edges: [.top, .bottom])
                .ignoresSafeArea(.keyboard, edges: .bottom)
        }
    }
}
