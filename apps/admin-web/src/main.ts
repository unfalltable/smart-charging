import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElAlert, ElLoading } from 'element-plus'
import 'element-plus/es/components/base/style/css'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/loading/style/css'
import './styles.css'
import App from './App.vue'

const app = createApp(App)
app.use(createPinia())
app.use(ElLoading)
app.component('ElAlert', ElAlert)
app.mount('#app')
