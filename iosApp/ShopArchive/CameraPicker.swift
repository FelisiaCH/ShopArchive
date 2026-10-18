import SwiftUI
import UIKit
import ClientCore

/// Whether this device has a camera to take a slip with; Compose shows the camera buttons only then.
let cameraAvailable = UIImagePickerController.isSourceTypeAvailable(.camera)

/// The system camera, to be shown full screen. `onFinish` gets the shot, or nil when the user cancels.
struct CameraPicker: UIViewControllerRepresentable {
    let onFinish: (UIImage?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ picker: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onFinish: (UIImage?) -> Void

        init(onFinish: @escaping (UIImage?) -> Void) { self.onFinish = onFinish }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            onFinish(info[.originalImage] as? UIImage)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { onFinish(nil) }
    }
}

/// A shot as the JPEG bytes the flow takes. Encoding and copying a photo take a while: call it off the main thread.
func slipBytes(_ image: UIImage) -> KotlinByteArray? {
    guard let data = image.jpegData(compressionQuality: 0.9) else { return nil }
    let raw = KotlinByteArray(size: Int32(data.count))
    for (i, byte) in data.enumerated() { raw.set(index: Int32(i), value: Int8(bitPattern: byte)) }
    return raw
}
