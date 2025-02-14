import Combine
import Foundation
import Shared
import SwiftUI

// MARK: -

struct FormViewBottomSheet: View {

    // MARK: - Private Properties

    @SwiftUI.State
    private var totalHeight: CGFloat = 0
    private let totalHeightSubject: PassthroughSubject<CGFloat, Never>

    private let viewModel: FormBodyModel

    @SwiftUI.State
    private var safariUrl: URL?

    // MARK: - Life Cycle

    init(viewModel: FormBodyModel, totalHeightSubject: PassthroughSubject<CGFloat, Never>) {
        self.viewModel = viewModel
        self.totalHeightSubject = totalHeightSubject
    }

    // MARK: - View

    var body: some View {
        VStack(alignment: .leading) {
            // Don't use a scroll view or else showing in the bottom sheet will not work
            FormContentView(
                toolbarModel: viewModel.toolbar,
                headerModel: viewModel.header,
                mainContentList: viewModel.mainContentList,
                renderContext: .sheet
            )
            Spacer()
                .frame(height: 24)
            FormFooterView(viewModel: viewModel)
        }
        .padding(.horizontal, DesignSystemMetrics.horizontalPadding)
        .padding(.vertical, 16)
        .background(GeometryReader { gp -> Color in
            DispatchQueue.main.async {
                // This allows us to dynamically size the bottom sheet to the height of the contents
                self.totalHeight = gp.size.height
            }
            return Color.clear
        })
        .onChange(of: totalHeight) { newHeight in
            totalHeightSubject.send(newHeight)
        }
        .fullScreenCover(item: $safariUrl) { url in
            SafariView(url: url)
                .ignoresSafeArea()
        }
        .onAppear {
            self.viewModel.onLoaded(
                NativeBrowserNavigator(openSafariView: { self.safariUrl = URL(string: $0) })
            )
        }
        Spacer()
    }

}
