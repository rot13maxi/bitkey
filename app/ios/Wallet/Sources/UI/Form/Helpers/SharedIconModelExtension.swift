import Shared
import SwiftUI

// MARK: - IconTint

extension IconTint {
    var color: Color {
        switch self {
        case .primary:
            return .primary
        case .on30:
            return .foreground30
        case .on60:
            return .foreground60
        case .destructive:
            return .destructiveForeground
        case .outofdate:
            return .outOfDate
        case .ontranslucent:
            return .translucentForeground
        case .green:
            return .positiveForeground
        default:
            return .foreground
        }
    }

}
