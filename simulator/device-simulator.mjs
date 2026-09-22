import net from 'node:net'
import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto'

const host = process.env.DEVICE_GATEWAY_HOST ?? '127.0.0.1'
const port = Number(process.env.DEVICE_GATEWAY_PORT ?? '9000')
const deviceCode = process.env.PILOT_DEVICE_CODE ?? 'PILE001'
const secret = process.env.PILOT_DEVICE_SECRET

if (!secret || secret.length < 32) throw new Error('PILOT_DEVICE_SECRET must contain at least 32 characters')

let sequenceNo = 0
let buffer = ''
const activeSessions = new Map()
const socket = net.createConnection({ host, port }, () => {
  console.log(`Simulator connected as ${deviceCode}`)
  sendEvent('BOOT', { firmwareVersion: 'simulator-1.0', connectorCount: 12 })
})

socket.setKeepAlive(true, 30_000)
socket.on('data', (chunk) => {
  buffer += chunk.toString('utf8')
  while (buffer.includes('\n')) {
    const newline = buffer.indexOf('\n')
    const line = buffer.slice(0, newline)
    buffer = buffer.slice(newline + 1)
    if (line) receive(line)
  }
})
socket.on('error', (error) => console.error('Simulator connection error:', error.message))
socket.on('close', () => console.log('Simulator disconnected'))

setInterval(() => sendEvent('HEARTBEAT', { sequenceNo: ++sequenceNo }), 20_000).unref()

function receive(line) {
  const message = JSON.parse(line)
  if (message.accepted !== undefined) return
  verifyCommand(message)
  const command = message.command
  console.log(`Received ${command.commandType} command ${command.commandId}`)
  sendEvent('COMMAND_ACK', { commandId: command.commandId, accepted: true })
  if (command.commandType === 'START_CHARGING') simulateCharge(command)
  if (command.commandType === 'STOP_CHARGING') stopCharge(JSON.parse(command.payload).orderId, 'REMOTE_STOP')
}

function verifyCommand(message) {
  const command = message.command
  const signingText = [command.commandId, command.tenantId, command.deviceCode,
    command.connectorNo ?? '', command.commandType, command.expiresAt, command.payload,
    message.issuedAt, message.nonce].join('\n')
  const expected = Buffer.from(hmac(signingText), 'hex')
  const supplied = Buffer.from(message.signature, 'hex')
  if (expected.length !== supplied.length || !timingSafeEqual(expected, supplied)) {
    throw new Error('Gateway command signature is invalid')
  }
}

function simulateCharge(command) {
  const commandPayload = JSON.parse(command.payload)
  const meterStartWh = 10_000
  const session = { command, orderId: commandPayload.orderId, meterStartWh, sample: 0, timer: null }
  activeSessions.set(session.orderId, session)
  sendEvent('SESSION_STARTED', { orderId: commandPayload.orderId, meterStartWh })
  session.timer = setInterval(() => {
    session.sample += 1
    sendEvent('METER_SAMPLE', {
      orderId: commandPayload.orderId,
      connectorNo: command.connectorNo,
      sequenceNo: session.sample,
      energyWh: meterStartWh + session.sample * 70,
      powerW: 420,
      voltageMv: 220_000,
      currentMa: 1_910
    })
    if (session.sample === 5) stopCharge(session.orderId, 'SIMULATION_COMPLETE')
  }, 2_000)
}

function stopCharge(orderId, reason) {
  const session = activeSessions.get(orderId)
  if (!session) return
  clearInterval(session.timer)
  activeSessions.delete(orderId)
  sendEvent('SESSION_STOPPED', {
    orderId,
    meterStopWh: session.meterStartWh + session.sample * 70,
    reason
  })
}

function sendEvent(eventType, value) {
  if (!socket.writable) return
  const payload = JSON.stringify(value)
  const envelope = {
    protocolVersion: '1.0',
    messageId: randomUUID(),
    deviceCode,
    occurredAt: canonicalNow(),
    nonce: randomUUID(),
    eventType,
    payload
  }
  const signingText = [envelope.protocolVersion, envelope.messageId, envelope.deviceCode,
    envelope.occurredAt, envelope.nonce, envelope.eventType, envelope.payload].join('\n')
  envelope.signature = hmac(signingText)
  socket.write(`${JSON.stringify(envelope)}\n`)
}

function canonicalNow() {
  return new Date().toISOString().replace('.000Z', 'Z')
}

function hmac(value) {
  return createHmac('sha256', secret).update(value, 'utf8').digest('hex')
}
