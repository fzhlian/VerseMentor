import { installSharedCoreBridgeHooks } from '../shared/shared_core_bridge_host'

@Entry
@Component
struct MainApp {
  @State page: string = 'Home'

  aboutToAppear() {
    installSharedCoreBridgeHooks()
  }

  build() {
    Column() {
      if (this.page === 'Home') {
        HomePage({ onStart: () => this.page = 'Session', onSettings: () => this.page = 'Settings' })
      } else if (this.page === 'Session') {
        SessionPage({ onBack: () => this.page = 'Home' })
      } else if (this.page === 'Settings') {
        SettingsPage({ onBack: () => this.page = 'Home' })
      }
    }.width('100%').height('100%')
  }
}