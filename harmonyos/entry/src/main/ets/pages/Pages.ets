@Component
struct HomePage {
  @Prop onStart: () => void
  @Prop onSettings: () => void

  build() {
    Column({ space: 12 }) {
      Text('VerseMentor').fontSize(28)
      Button('Start').onClick(() => this.onStart())
      Button('Settings').onClick(() => this.onSettings())
    }.padding(24)
  }
}

@Component
struct SessionPage {
  @Prop onBack: () => void
  @State status: string = 'Idle'

  build() {
    Column({ space: 12 }) {
      Text('Session').fontSize(20)
      Text(this.status)
      Button('Back').onClick(() => this.onBack())
    }.padding(24)
  }
}

@Component
struct SettingsPage {
  @Prop onBack: () => void
  @State followSystem: boolean = true
  @State toneRemind: boolean = true
  @State variantsEnable: boolean = true

  build() {
    Column({ space: 12 }) {
      Text('Settings').fontSize(20)
      Row({ space: 8 }) {
        Text('Language')
        Button('System').onClick(() => this.followSystem = true)
        Button('中文').onClick(() => this.followSystem = false)
      }
      Row({ space: 8 }) {
        Text('Tone Remind')
        Toggle({ type: ToggleType.Switch, isOn: this.toneRemind }).onChange((v: boolean) => this.toneRemind = v)
      }
      Row({ space: 8 }) {
        Text('Variants Online')
        Toggle({ type: ToggleType.Switch, isOn: this.variantsEnable }).onChange((v: boolean) => this.variantsEnable = v)
      }
      Button('Back').onClick(() => this.onBack())
    }.padding(24)
  }
}
