import Lottie
import Shared
import SwiftUI

// MARK: -

public struct RotatingLoadingIcon: View {

    // MARK: - Public Types

    public enum Tint {
        case white
        case black
    }

    // MARK: - Private Properties

    private let size: IconSize
    private let tint: Tint
    private var animation: LottieAnimation {
        // Unfortunately, Lottie doesn't easily support changing the color of animations on iOS like it does on Android.
        // So we can only support white or black loaders. If additional colors are needed, we need
        // an additional lottie json file for each color.
        switch tint {
        case .black: return .loading
        case .white: return .loadingWhite
        }
    }

    // MARK: - Life Cycle

    public init(size: IconSize, tint: Tint) {
        self.size = size
        self.tint = tint
    }

    public var body: some View {
        // We need to manually pause the animation when performing snapshot tests to avoid flakes
        if ProcessInfo.isTesting {
            LottieView(animation: animation)
                .paused(at: .progress(0.5))
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(iconSize: size)
        } else {
            LottieView(animation: animation)
                .playing(loopMode: .loop)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(iconSize: size)
        }
    }

}
