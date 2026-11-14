import SwiftUI
import UIKit
import ScleraCore

@main
struct ScleraCheckApp: App {
    var body: some Scene {
        WindowGroup { RootView().preferredColorScheme(.light) }
    }
}

enum Screen: Equatable { case disclaimer, home, check(baseline: Bool), history, clinician }

struct RootView: View {
    @State private var screen: Screen = Prefs.disclaimerAccepted ? .home : .disclaimer
    @StateObject private var store = Store.shared

    var body: some View {
        Group {
            switch screen {
            case .disclaimer:
                DisclaimerView { Prefs.disclaimerAccepted = true; screen = .home }
            case .home:
                HomeView(onCheck: { screen = .check(baseline: false) }, onHistory: { screen = .history }, onClinician: { screen = .clinician })
            case .check(let baseline):
                // A new CheckView (and session) is created each time we navigate here from another screen.
                CheckView(isBaseline: baseline) { screen = baseline ? .clinician : .home }
            case .history:
                HistoryView { screen = .home }
            case .clinician:
                ClinicianView(onBack: { screen = .home }, onRecordBaseline: { screen = .check(baseline: true) }, onHistory: { screen = .history })
            }
        }
        .environmentObject(store)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
    }
}

let nhsBlue = Color(red: 0, green: 0.37, blue: 0.72)
let okGreen = Color(red: 0.12, green: 0.55, blue: 0.24)
let amber = Color(red: 0.78, green: 0.47, blue: 0)
let alertRed = Color(red: 0.78, green: 0.16, blue: 0.16)

/// CLAUDE.md §1.1: on first run and on every result.
struct NotDiagnosticNotice: View {
    var body: some View {
        Text("ScleraCheck is a screening aid, not a diagnostic device. It cannot tell you whether you have jaundice. If you feel unwell, or are worried, contact your GP or call NHS 111 — whatever this app says.")
            .font(.footnote).foregroundStyle(.secondary)
    }
}

struct DisclaimerView: View {
    let onAccept: () -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Before you start").font(.largeTitle.bold())
                Text("This app looks at the colour of the white of your eye and compares it with the baseline your GP recorded. It can tell you if there are signs of yellowing so you know to contact your GP.")
                Text("This is NOT a diagnostic device. It does not diagnose jaundice or any other condition.")
                    .bold().padding().frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.orange.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
                Text("During each check the screen flashes white (or the phone's light turns on) for about a second. It is not a flashing light.")
                Text("Images of your face are never stored. Only colour measurements are kept, on this phone.")
                NotDiagnosticNotice()
                Button(action: onAccept) { Text("I understand").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
            }.padding(24)
        }
    }
}
