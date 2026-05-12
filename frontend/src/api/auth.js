import { http } from './http'

function unwrap(resp) {
  return resp.data?.data
}

export function requestCaptcha(payload) {
  return http.post('/api/auth/captcha', payload).then(unwrap)
}

export function login(payload) {
  return http.post('/api/auth/login', payload).then(unwrap)
}

export function smsLogin(payload) {
  return http.post('/api/auth/sms-login', payload).then(unwrap)
}

export function register(payload) {
  return http.post('/api/auth/register', payload).then(unwrap)
}

export function resetPassword(payload) {
  return http.post('/api/auth/reset-password', payload).then(unwrap)
}

export function fetchMe() {
  return http.get('/api/auth/me').then(unwrap)
}
