const config = require('./config')

App({
  globalData: config,
  onUnhandledRejection({ reason }) {
    console.error('Unhandled mini-program error', reason)
  }
})
