Page({
  data: { url: '' },
  onLoad(options) {
    try {
      const url = decodeURIComponent(options.url || '')
      if (!url.startsWith('https://')) throw new Error('invalid URL')
      this.setData({ url })
    } catch {
      wx.showToast({ title: '协议地址无效', icon: 'none' })
    }
  }
})
