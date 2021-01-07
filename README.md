# support change ktlint version
# support add customize ktlint rule
、、、
spotless {
  kotlin {
    target '**/*.kt'
    ktlint('0.36.0').libs("packageName.CstRuleSetProvider").mainMaven("group:plugin:version").replace()
  }
}

、、、
