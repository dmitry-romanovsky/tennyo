import SwiftUI
import TennyoKit
import UIKit

// Debug builds define TENNYO_ALLOW_UNSAFE_TRAFFIC in the target's active compilation conditions.
#if TENNYO_ALLOW_UNSAFE_TRAFFIC
private let allowUnsafeTraffic = true
#else
private let allowUnsafeTraffic = false
#endif

/// The shared Compose UI from TennyoKit, hosted in SwiftUI.
struct RootView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(allowUnsafeTraffic: allowUnsafeTraffic)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
