import Combine
import Foundation
import Shared
import SwiftUI

class BottomSheetViewController: UIHostingController<FormViewBottomSheet>, UISheetPresentationControllerDelegate {

    // MARK: - Private Properties

    private let totalHeightSubject = PassthroughSubject<CGFloat, Never>()
    private var cancellablesBag = Set<AnyCancellable>()
    private var viewModel: SheetModel

    // MARK: - Life Cycle

    public init(viewModel: SheetModel) {
        self.viewModel = viewModel

        // We only support forms as bottom sheets right now
        if let formBottomSheetModel = viewModel.body as? FormBodyModel {
            super.init(rootView: .init(viewModel: formBottomSheetModel, totalHeightSubject: totalHeightSubject))
        } else {
            fatalError("\(viewModel.body) not supported as a bottom sheet (W-3497)")
        }

        // Create a publisher for when the total height of the sheet gets updated by the view
        // and use that to send to the presentation detents
        totalHeightSubject
            .receive(on: RunLoop.main)
            .sink(receiveValue: { newTotalHeight in
                self.updateSheetDetents(totalHeight: newTotalHeight)
            })
            .store(in: &cancellablesBag)
    }

    @MainActor required dynamic init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    // MARK: - UIViewController

    override var navigationController: UINavigationController? {
        nil
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        guard let sheetController = presentationController as? UISheetPresentationController else {
            return
        }

        sheetController.prefersGrabberVisible = viewModel.dragIndicatorVisible
        sheetController.delegate = self

        // Start out with medium detents (unless the size is full) when the view loads and then we'll readjust
        // when the total height calculation of the view changes
        switch viewModel.size {
        case .default_, .min40:
            sheetController.detents = [.medium()]
        case .full:
            break
        default:
            fatalError("Unexpected bottom sheet size")
        }
    }

    // MARK: - UISheetPresentationControllerDelegate

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        viewModel.onClosed()
    }

    // MARK: - Public Methods

    public func update(viewModel: SheetModel) {
        self.viewModel = viewModel
        // We only support forms as bottom sheets right now
        if let formBottomSheetModel = viewModel.body as? FormBodyModel {
            rootView = .init(viewModel: formBottomSheetModel, totalHeightSubject: totalHeightSubject)
        } else {
            fatalError("\(viewModel.body) not supported as a bottom sheet (W-3497)")
        }
    }

    // MARK: - Private Methods

    private func updateSheetDetents(totalHeight: CGFloat) {
        guard let sheetController = presentationController as? UISheetPresentationController else {
            return
        }

        if #available(iOS 16.0, *) {
            switch viewModel.size {
            case .default_:
                sheetController.detents = [.custom(resolver: { _ in totalHeight })]
            case .min40:
                sheetController.detents = [.custom(resolver: { _ in
                    // Make sure the minimum height of 40% of the screen is respected
                    if let screenHeight = self.view.window?.windowScene?.screen.bounds.height {
                        return max(screenHeight * 0.4, totalHeight)
                    } else {
                        return totalHeight
                    }
                })]
            case .full:
                sheetController.detents = [.large()]
            default:
                fatalError("Unexpected bottom sheet size")
            }
        }
    }

}
